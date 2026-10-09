"""Task-scoped, authenticated ADB local-socket transport. No TCP listener or request files."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import threading
import time

from usb_protocol import VERSION, PACKAGE, RUNNER, UsbError, encode_request, encode_control, send_frame, receive_frame, _unique


def adb_call(adb, serial, arguments, timeout=8, check=True):
    if not isinstance(serial, str) or re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', serial) is None:
        raise UsbError('device_invalid')
    try:
        result = subprocess.run([str(adb), '-s', serial, *arguments], capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise UsbError('adb_unavailable_or_timeout') from None
    if check and result.returncode != 0:
        raise UsbError('adb_command_failed')
    return result


def artifact(apk_path):
    apk = Path(apk_path)
    try:
        metadata = json.loads(apk.with_name('manifest.json').read_text(encoding='utf-8'), object_pairs_hook=_unique)
        if (not isinstance(metadata, dict) or type(metadata.get('protocol')) is not int or metadata.get('protocol') != VERSION
                or metadata.get('automaticSubmissionSupported') is not False or metadata.get('startup') != 'authorized_adb_instrumentation'
                or type(metadata.get('versionCode')) is not int or metadata['versionCode'] <= 0 or metadata.get('package') != PACKAGE
                or metadata.get('runner') != RUNNER or metadata.get('apk') != apk.name
                or not isinstance(metadata.get('sha256'), str) or re.fullmatch(r'[0-9a-f]{64}', metadata['sha256']) is None):
            raise UsbError('usb_artifact_invalid')
        digest = hashlib.sha256()
        with apk.open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != metadata['sha256']:
            raise UsbError('usb_artifact_invalid')
        return apk, metadata
    except UsbError:
        raise
    except (OSError, ValueError, TypeError):
        raise UsbError('usb_executor_missing') from None


def verify_hello(frame, run_id, capability):
    data = frame.get('data', {})
    if (frame.get('type') != 'hello' or set(data) != {'protocol', 'package', 'pid', 'sdk', 'proof'}
            or type(data['protocol']) is not int or data['protocol'] != VERSION or data['package'] != PACKAGE or type(data['pid']) is not int or data['pid'] <= 0
            or type(data['sdk']) is not int or data['sdk'] < 29 or not isinstance(data['proof'], str)):
        raise UsbError('usb_hello_invalid')
    message = f"hello|{VERSION}|{run_id}|{PACKAGE}|{data['pid']}|{data['sdk']}".encode()
    expected = hmac.new(capability.encode('ascii'), message, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, data['proof']):
        raise UsbError('usb_server_not_authenticated')
    return data['pid']


def _label(value, empty=False):
    return (isinstance(value, str) and len(value) <= 300 and (empty or bool(value.strip()))
            and all(ord(c) >= 32 and not 127 <= ord(c) <= 159 for c in value))


def validate_report(data):
    """Allowlist desktop-visible fields, rejecting any unexpected private identity payload."""
    allowed = {'phase', 'reason', 'actions', 'metrics', 'lastNativeReadFailure', 'automaticSubmissionSupported',
               'attendeeIdentityExported', 'page', 'verification'}
    if (not isinstance(data, dict) or set(data) - allowed or data.get('automaticSubmissionSupported') is not False
            or data.get('attendeeIdentityExported') is not False
            or data.get('phase') not in {'ready', 'inspected', 'stopped', 'confirmed', 'running', 'waiting'}
            or not isinstance(data.get('reason'), str) or re.fullmatch(r'[a-z0-9_:]{1,120}', data['reason']) is None
            or type(data.get('actions')) is not int or not 0 <= data['actions'] <= 200):
        raise UsbError('usb_report_invalid')
    metrics = data.get('metrics', {})
    if (not isinstance(metrics, dict) or set(metrics) - {'readMs', 'nativeReadMs', 'visualMs', 'nativeReadRetries'}
            or any(type(v) not in (int, float) or not 0 <= v <= 10**9 for v in metrics.values())):
        raise UsbError('usb_report_invalid')
    if 'lastNativeReadFailure' in data and (not isinstance(data['lastNativeReadFailure'], str) or re.fullmatch(r'[a-z_]{1,80}', data['lastNativeReadFailure']) is None):
        raise UsbError('usb_report_invalid')
    if 'verification' in data and data['verification'] not in ('full', 'event_only'):
        raise UsbError('usb_report_invalid')
    if (data['reason'] == 'confirmation_verified_manual_submit') != (data['phase'] == 'confirmed'):
        raise UsbError('usb_confirmation_invalid')
    page = data.get('page')
    if page is None:
        if data['phase'] == 'confirmed':
            raise UsbError('usb_confirmation_invalid')
        return data
    allowed_page = {'kind', 'title', 'sessions', 'tiers', 'blockers', 'canOpenPicker', 'purchaseAvailability', 'buyers',
                    'buyersComplete', 'orderVerified', 'identityStaysOnPhone', 'quantity', 'totalCents', 'selectedBuyerCount', 'defaultFirstSelected'}
    if (not isinstance(page, dict) or set(page) - allowed_page
            or page.get('kind') not in {'detail', 'picker', 'confirm', 'risk', 'queue', 'loading', 'unknown', 'reservation'}
            or not _label(page.get('title'), empty=True) or page.get('buyers') != [] or page.get('buyersComplete') is not False
            or page.get('identityStaysOnPhone') is not True or type(page.get('orderVerified')) is not bool
            or type(page.get('canOpenPicker')) is not bool or page.get('purchaseAvailability') not in {'available', 'upcoming', 'sold_out', 'unavailable', 'unknown'}):
        raise UsbError('usb_page_invalid')
    blockers = page.get('blockers')
    if (not isinstance(blockers, list) or len(blockers) > 32 or any(not isinstance(v, str) or re.fullmatch(r'[A-Z_]{1,80}', v) is None for v in blockers)):
        raise UsbError('usb_page_invalid')
    for field in ('sessions', 'tiers'):
        choices = page.get(field)
        if not isinstance(choices, list) or len(choices) > 100:
            raise UsbError('usb_page_invalid')
        for choice in choices:
            if (not isinstance(choice, dict) or set(choice) - {'label', 'selected', 'selectionVerified', 'enabled', 'availability', 'unitPriceCents'}
                    or not _label(choice.get('label')) or any(type(choice.get(key)) is not bool for key in ('selected', 'selectionVerified', 'enabled'))
                    or choice.get('availability') not in {'available', 'upcoming', 'sold_out', 'unavailable', 'unknown'}):
                raise UsbError('usb_page_invalid')
            if field == 'tiers' and (type(choice.get('unitPriceCents')) is not int or not 1 <= choice['unitPriceCents'] <= 2_000_000_000):
                raise UsbError('usb_page_invalid')
    if page['kind'] not in ('detail', 'picker') and (page['title'] or page['sessions'] or page['tiers']):
        raise UsbError('usb_private_page_payload_rejected')
    for field, maximum in (('quantity', 20), ('totalCents', 2_000_000_000), ('selectedBuyerCount', 20)):
        if field in page and (type(page[field]) is not int or not 0 <= page[field] <= maximum):
            raise UsbError('usb_page_invalid')
    if data['phase'] == 'confirmed' and page['orderVerified'] is not True:
        raise UsbError('usb_confirmation_invalid')
    if page['orderVerified']:
        if (page['kind'] != 'confirm' or data['phase'] != 'confirmed' or data['reason'] != 'confirmation_verified_manual_submit'
                or type(page.get('quantity')) is not int or page['quantity'] <= 0 or page.get('selectedBuyerCount') != page['quantity']
                or type(page.get('totalCents')) is not int or page['totalCents'] <= 0 or type(page.get('defaultFirstSelected')) is not bool):
            raise UsbError('usb_confirmation_invalid')
    elif any(k in page for k in ('selectedBuyerCount', 'defaultFirstSelected', 'totalCents')):
        raise UsbError('usb_unverified_order_payload_rejected')
    return data


class UsbSession:
    def __init__(self, adb, serial, cancel_event, progress):
        self.adb, self.serial = str(adb), serial
        self.cancel_event, self.progress = cancel_event, progress
        self.run_id, self.capability = secrets.token_hex(16), secrets.token_hex(32)
        self.sock = None
        self.process = None
        self.phone_pid = None
        self.local = None
        self.directory = None
        self.sequence = 0
        self._write = threading.Lock()
        self._terminal = threading.Event()
        self._heartbeat = None
        self._failure = None
        self._public_reason = None

    def _adb(self, args, **kwargs):
        return adb_call(self.adb, self.serial, args, **kwargs)

    def _control(self, cancel=False):
        with self._write:
            if self.sock is None:
                return
            self.sequence += 1
            send_frame(self.sock, encode_control(self.sequence, cancel))

    def cancel(self):
        self.cancel_event.set()
        try:
            self._control(cancel=True)
        except (OSError, UsbError):
            pass

    def _heartbeat_loop(self):
        while not self._terminal.wait(.5):
            if self.cancel_event.is_set():
                self.cancel()
                return
            try:
                self._control()
            except (OSError, UsbError):
                if not self._terminal.is_set():
                    self._failure = 'usb_disconnected'
                    self.cancel_event.set()
                return

    def _drain_instrumentation(self):
        for raw in iter(self.process.stdout.readline, b''):
            if len(raw) <= 512:
                line = raw.decode('utf-8', errors='replace').strip()
                prefix = 'INSTRUMENTATION_RESULT: reason='
                if line.startswith(prefix) and re.fullmatch(r'[a-z0-9_:]{1,120}', line[len(prefix):]):
                    self._public_reason = line[len(prefix):]

    def run(self, operation, **arguments):
        try:
            if not hasattr(socket, 'AF_UNIX'):
                raise UsbError('private_usb_socket_unsupported')
            self.directory = tempfile.TemporaryDirectory(prefix='tusb-')
            path = str(Path(self.directory.name) / 'io')
            if len(os.fsencode(path)) >= 100:
                raise UsbError('private_usb_socket_path_too_long')
            self.local = 'localfilesystem:' + path
            self._adb(['forward', self.local, 'localabstract:tickets.usb.' + self.run_id])
            self.process = subprocess.Popen([self.adb, '-s', self.serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'runId', self.run_id, '-e', 'capability', self.capability, RUNNER], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            threading.Thread(target=self._drain_instrumentation, name='usb-instrumentation-status', daemon=True).start()
            until = time.monotonic() + 8
            while self.sock is None and time.monotonic() < until:
                if self.cancel_event.is_set():
                    raise UsbError('stopped_by_user')
                candidate = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                candidate.settimeout(.5)
                try:
                    candidate.connect(path)
                    hello = receive_frame(candidate, self.run_id, cancelled=self.cancel_event.is_set, silence_seconds=2)
                    self.phone_pid = verify_hello(hello, self.run_id, self.capability)
                    self.sock = candidate
                except (OSError, UsbError) as error:
                    candidate.close()
                    if isinstance(error, UsbError) and error.reason not in ('usb_disconnected', 'usb_reply_timeout'):
                        raise
                    if self.process.poll() is not None:
                        raise UsbError(self._public_reason or 'usb_executor_start_failed') from None
                    self.cancel_event.wait(.1)
            if self.sock is None:
                raise UsbError(self._public_reason or 'usb_executor_start_timeout')
            self.sock.settimeout(1)
            payload = encode_request(self.capability, operation, **arguments)
            with self._write:
                if self.cancel_event.is_set():
                    raise UsbError('stopped_by_user')
                send_frame(self.sock, payload)
            self._heartbeat = threading.Thread(target=self._heartbeat_loop, name='usb-heartbeat', daemon=True)
            self._heartbeat.start()
            while True:
                message = receive_frame(self.sock, self.run_id, cancelled=self.cancel_event.is_set, silence_seconds=10)
                if self._failure:
                    raise UsbError(self._failure)
                if message['type'] == 'error':
                    if set(message['data']) != {'reason'}:
                        raise UsbError('usb_reply_invalid')
                    raise UsbError(message['data'].get('reason', 'usb_execution_failed'))
                data = validate_report(message['data'])
                if message['type'] == 'progress':
                    self.progress(data)
                elif message['type'] == 'complete':
                    self._terminal.set()
                    return data
                else:
                    raise UsbError('usb_reply_invalid')
        except (OSError, subprocess.SubprocessError):
            raise UsbError('usb_disconnected') from None
        finally:
            self.close()

    def close(self):
        if not self._terminal.is_set():
            try:
                self._control(cancel=True)
            except (OSError, UsbError):
                pass
        self._terminal.set()
        if self.sock is not None:
            try:
                self.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.sock.close()
            self.sock = None
        if self._heartbeat is not None and self._heartbeat is not threading.current_thread():
            self._heartbeat.join(timeout=2)
        if self.process is not None:
            try:
                self.process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                # Terminate only a still-identical process in our dedicated executor package.
                if self.phone_pid is not None:
                    try:
                        pids = self._adb(['shell', 'pidof', PACKAGE], check=False, timeout=3).stdout.decode().split()
                        if pids == [str(self.phone_pid)]:
                            self._adb(['shell', 'am', 'force-stop', PACKAGE], timeout=3)
                    except UsbError:
                        pass
                self.process.terminate()
                try:
                    self.process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.process.kill(); self.process.wait(timeout=2)
        if self.local is not None:
            try:
                self._adb(['forward', '--remove', self.local], timeout=3, check=False)
            except UsbError:
                pass
            self.local = None
        if self.directory is not None:
            self.directory.cleanup()
            self.directory = None
