"""Durable, device-wide submission fence. Never stores ticket or identity data.

An intent is fsynced before the caller dispatches a submit click. Unknown outcomes
and Alipay handoffs remain fenced until a matching, explicit acknowledgement.
Locks are separate from the atomically replaced ledger, so process restarts and
concurrent writers cannot lose an intent. Corruption is never repaired implicitly.

The standard namespace is Tauri's com.tickets/android-submissions. Its legacy
Tickets/android ledger remains locked and checked; new intents are mirrored so
older single-directory readers also see them. Custom directories are isolated.
Conflicting unresolved attempts fail closed rather than choosing by timestamp.
"""
from contextlib import contextmanager, ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import uuid


STORE_ERROR = '订单提交安全记录无法可靠读取或保存，已禁止继续购票；请检查本地数据目录并人工核对订单。'
UNRESOLVED = '此设备存在尚未人工核对的订单提交记录，请先检查订单，再明确确认解除限制。'


def canonical_directory():
    # Tauri app_data_dir() = platform data_dir / tauri.bundle.identifier.
    # Windows data_dir is Roaming (APPDATA), NOT LocalAppData.
    if sys.platform == 'darwin':
        base = Path.home() / 'Library/Application Support'
    elif sys.platform == 'win32':
        base = Path(os.environ.get('APPDATA', Path.home() / 'AppData/Roaming'))
    else:
        base = Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share'))
        if not base.is_absolute():
            base = Path.home() / '.local/share'
    return base / 'com.tickets/android-submissions'


def legacy_directory():
    if sys.platform == 'darwin':
        return Path.home() / 'Library/Application Support/Tickets/android'
    if sys.platform == 'win32':
        return Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData/Local')) / 'Tickets/android'
    return Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'Tickets/android'


def default_directory():
    override = os.environ.get('TICKETS_ANDROID_STATE_DIR')
    return Path(override) if override else canonical_directory()


def _path_key(directory):
    return os.path.normcase(os.path.abspath(directory))


def opaque_id(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{32}', value) is not None


def validate_acknowledgement(attempt_id, acknowledged):
    if not opaque_id(attempt_id) or acknowledged is not True:
        raise ValueError('请明确确认已在手机检查订单，并提供当前提交记录的 attemptId。')


def _sync_directory(directory):
    # Windows replacement uses MOVEFILE_WRITE_THROUGH instead (see _replace).
    if os.name != 'nt':
        fd = os.open(directory, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def _mkdir(directory):
    if directory.is_dir():
        return
    _mkdir(directory.parent)
    try:
        directory.mkdir(mode=0o700)
    except FileExistsError:
        if not directory.is_dir():
            raise OSError('State directory is not a directory')
    _sync_directory(directory.parent)


def _replace(source, destination):
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        move = ctypes.WinDLL('kernel32', use_last_error=True).MoveFileExW
        move.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
        move.restype = wintypes.BOOL
        if not move(str(source), str(destination), 0x1 | 0x8):
            raise ctypes.WinError(ctypes.get_last_error())
    else:
        os.replace(source, destination)
        _sync_directory(destination.parent)


@contextmanager
def _file_lock(path, blocking=True):
    # The lock file is never replaced or removed. OS locks are released on crash.
    fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        if os.name == 'nt':
            import msvcrt
            if os.fstat(fd).st_size == 0:
                os.write(fd, b'\0')
            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_LOCK if blocking else msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
        yield
    finally:
        os.close(fd)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate store key')
        result[key] = value
    return result


class SubmissionGuard:
    def __init__(self, directory=None):
        self.directory = Path(directory) if directory is not None else default_directory()
        canonical, legacy = canonical_directory(), legacy_directory()
        if _path_key(self.directory) in (_path_key(canonical), _path_key(legacy)):
            self.directory = canonical
            self.directories = tuple(dict.fromkeys((canonical, legacy)))
        else:
            # Explicit custom/test stores are isolated; never probe the real home.
            self.directories = (self.directory,)
        self.failed = False

    @staticmethod
    def _key(serial):
        return hashlib.sha256(serial.encode('utf-8')).hexdigest()

    @contextmanager
    def _locks(self, filename, blocking=True):
        # Acquire BOTH stable lock files in the same order on every entry point.
        # Include absent legacy directories too: checking existence before locking
        # would race an old CLI creating its first ledger. Deduplicate symlink aliases.
        directories = sorted({directory.resolve() for directory in self.directories}, key=_path_key)
        with ExitStack() as stack:
            for directory in directories:
                _mkdir(directory)
                stack.enter_context(_file_lock(directory / filename, blocking=blocking))
            yield

    @contextmanager
    def _transaction(self):
        if self.failed:
            raise RuntimeError(STORE_ERROR)
        try:
            with self._locks('submissions.lock'):
                yield
        except (OSError, ValueError) as error:
            self.failed = True
            raise RuntimeError(STORE_ERROR) from error

    @contextmanager
    def operation(self, serial):
        """Exclude another process's task or acknowledgement for this device."""
        if self.failed:
            raise RuntimeError(STORE_ERROR)
        try:
            with self._locks(self._key(serial) + '.operation.lock', blocking=False):
                yield
        except OSError as error:
            raise RuntimeError('设备已有操作或订单安全目录不可用，请等待完成后再操作。') from error

    def _load(self, directory):
        try:
            with (directory / 'submissions.json').open(encoding='utf-8') as source:
                data = json.load(source, object_pairs_hook=_unique_object)
        except FileNotFoundError:
            return {'version': 1, 'devices': {}}
        if (not isinstance(data, dict) or set(data) != {'version', 'devices'}
                or type(data['version']) is not int or data['version'] != 1
                or not isinstance(data['devices'], dict)):
            raise ValueError('Invalid store')
        for key, record in data['devices'].items():
            if (not re.fullmatch(r'[0-9a-f]{64}', key) or not isinstance(record, dict)
                    or set(record) != {'attemptId', 'taskId', 'orderState', 'createdAt', 'updatedAt', 'resolvedAt'}
                    or not opaque_id(record['attemptId']) or not opaque_id(record['taskId'])
                    or record['orderState'] not in ('unknown', 'alipay_handoff')
                    or any(type(record[field]) is not int or record[field] <= 0 for field in ('createdAt', 'updatedAt'))
                    or record['updatedAt'] < record['createdAt']
                    or (record['resolvedAt'] is not None and (type(record['resolvedAt']) is not int
                        or not record['createdAt'] <= record['resolvedAt'] <= record['updatedAt']))):
                raise ValueError('Invalid submission record')
        return data

    def _stores(self):
        # Validate every ledger before any mutation, including unrelated devices.
        return [(directory, self._load(directory)) for directory in self.directories]

    @staticmethod
    def _current(stores, key):
        records = [data['devices'][key] for _, data in stores if key in data['devices']]
        pending = [record for record in records if record['resolvedAt'] is None]
        if pending:
            identities = {(record['attemptId'], record['taskId'], record['createdAt']) for record in pending}
            if len(identities) != 1:
                # Two independent orders may exist. Never discard either based on
                # timestamps, and never let one acknowledgement clear both.
                raise ValueError('Conflicting unresolved submission records')
            return next((record for record in pending if record['orderState'] == 'unknown'), pending[0])
        return records[0] if records else None

    def _write(self, data, directory):
        fd, name = tempfile.mkstemp(prefix='.submissions-', suffix='.tmp', dir=directory)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as target:
                json.dump(data, target, separators=(',', ':'), sort_keys=True)
                target.flush()
                os.fsync(target.fileno())
            _replace(Path(name), directory / 'submissions.json')
        finally:
            try:
                os.unlink(name)
            except FileNotFoundError:
                pass

    @staticmethod
    def _public(record):
        if record is None:
            return {'blocked': False, 'status': 'clear', 'attemptId': None, 'taskId': None,
                    'orderState': None, 'createdAt': None, 'updatedAt': None, 'resolvedAt': None}
        blocked = record['resolvedAt'] is None
        return {**record, 'blocked': blocked, 'status': 'unresolved' if blocked else 'resolved'}

    def state(self, serial):
        try:
            with self._transaction():
                return self._public(self._current(self._stores(), self._key(serial)))
        except RuntimeError:
            return {**self._public(None), 'blocked': True, 'status': 'error', 'message': STORE_ERROR}

    def assert_clear(self, serial):
        state = self.state(serial)
        if state['blocked']:
            raise RuntimeError(STORE_ERROR if state['status'] == 'error' else UNRESOLVED)

    def begin(self, serial, task_id):
        if not opaque_id(task_id):
            raise ValueError('Invalid opaque task ID')
        with self._transaction():
            stores = self._stores()
            key = self._key(serial)
            previous = self._current(stores, key)
            if previous and previous['resolvedAt'] is None:
                raise RuntimeError(UNRESOLVED)
            now = int(time.time() * 1000)
            record = {'attemptId': uuid.uuid4().hex, 'taskId': task_id, 'orderState': 'unknown',
                      'createdAt': now, 'updatedAt': now, 'resolvedAt': None}
            # Mirror into the legacy ledger so an already-running old CLI also
            # sees the fence. A partial write raises before the caller can tap;
            # any surviving intent still blocks an upgraded reader on restart.
            for directory, data in stores:
                data['devices'][key] = record
                self._write(data, directory)
            return self._public(record)

    def _change(self, serial, attempt_id, resolving):
        with self._transaction():
            stores = self._stores()
            key = self._key(serial)
            record = self._current(stores, key)
            if not record or record['attemptId'] != attempt_id or record['resolvedAt'] is not None:
                raise RuntimeError('提交记录已变化或已解除，请重新读取当前订单安全状态。')
            updated = dict(record, updatedAt=max(int(time.time() * 1000), record['updatedAt'] + 1))
            if resolving:
                updated['resolvedAt'] = updated['updatedAt']
            else:
                updated['orderState'] = 'alipay_handoff'
            for directory, data in stores:
                existing = data['devices'].get(key)
                if existing and existing['attemptId'] == attempt_id and existing['resolvedAt'] is None:
                    data['devices'][key] = updated
                    self._write(data, directory)
            # Other attempt IDs (including resolved history) are never overwritten.
            return self._public(self._current(stores, key))

    def mark_handoff(self, serial, attempt_id):
        return self._change(serial, attempt_id, resolving=False)

    def acknowledge(self, serial, attempt_id, acknowledged):
        validate_acknowledgement(attempt_id, acknowledged)
        return self._change(serial, attempt_id, resolving=True)
