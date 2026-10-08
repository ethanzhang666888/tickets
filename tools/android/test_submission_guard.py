import json
import multiprocessing
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from submission_guard import SubmissionGuard, default_directory


TASK = '1' * 32


def crash_after_intent(directory):
    SubmissionGuard(directory).begin('crashed-device', TASK)
    os._exit(23)


def compete(directory, barrier, results):
    guard = SubmissionGuard(directory)
    barrier.wait()
    try:
        results.put(guard.begin('test-device', TASK)['attemptId'])
    except RuntimeError:
        results.put(None)


def compete_entry(home, entry, barrier, results):
    with patch('submission_guard.Path.home', return_value=Path(home)), \
            patch('submission_guard.sys.platform', 'darwin'), patch.dict(os.environ, {}, clear=True):
        canonical = Path(home) / 'Library/Application Support/com.tickets/android-submissions'
        legacy = Path(home) / 'Library/Application Support/Tickets/android'
        guard = SubmissionGuard(canonical) if entry == 'desktop' else SubmissionGuard()
        if entry == 'old-cli':
            # Emulate the pre-upgrade single-directory reader/writer and lock names.
            guard.directory, guard.directories = legacy, (legacy,)
        barrier.wait()
        try:
            with guard.operation('phone'):
                results.put(guard.begin('phone', TASK)['attemptId'])
        except RuntimeError:
            results.put(None)


class SubmissionGuardTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.guard = SubmissionGuard(self.directory.name)

    def test_intent_survives_recreation_and_contains_only_opaque_metadata(self):
        record = self.guard.begin('private-device-serial', TASK)
        state = SubmissionGuard(self.directory.name).state('private-device-serial')
        self.assertEqual(state, record)
        self.assertTrue(state['blocked'])
        self.assertEqual(state['orderState'], 'unknown')
        contents = Path(self.directory.name, 'submissions.json').read_text()
        self.assertNotIn('private-device-serial', contents)
        self.assertEqual(set(json.loads(contents)), {'version', 'devices'})
        self.assertFalse(self.guard.state('different-device')['blocked'])
        with self.assertRaises(RuntimeError):
            self.guard.begin('private-device-serial', '2' * 32)

    def test_handoff_remains_unresolved_until_matching_explicit_acknowledgement(self):
        intent = self.guard.begin('phone', TASK)
        self.guard.mark_handoff('phone', intent['attemptId'])
        self.assertEqual(self.guard.state('phone')['orderState'], 'alipay_handoff')
        for attempt, ack in [('2' * 32, True), (intent['attemptId'], False), (intent['attemptId'], 1)]:
            with self.assertRaises((ValueError, RuntimeError)):
                self.guard.acknowledge('phone', attempt, ack)
        result = self.guard.acknowledge('phone', intent['attemptId'], True)
        self.assertFalse(result['blocked'])
        self.assertEqual(result['status'], 'resolved')
        self.assertEqual(result['orderState'], 'alipay_handoff')
        with self.assertRaises(RuntimeError):
            self.guard.acknowledge('phone', intent['attemptId'], True)
        next_intent = self.guard.begin('phone', '2' * 32)
        self.assertNotEqual(next_intent['attemptId'], intent['attemptId'])
        with self.assertRaises(RuntimeError):
            self.guard.acknowledge('phone', intent['attemptId'], True)

    def test_corrupt_store_fails_closed_for_status_begin_and_acknowledgement(self):
        Path(self.directory.name, 'submissions.json').write_text('{broken')
        self.assertEqual(self.guard.state('phone')['status'], 'error')
        self.assertTrue(self.guard.state('phone')['blocked'])
        with self.assertRaises(RuntimeError):
            self.guard.begin('phone', TASK)
        with self.assertRaises(RuntimeError):
            self.guard.acknowledge('phone', TASK, True)
        self.assertEqual(Path(self.directory.name, 'submissions.json').read_text(), '{broken')

    def test_schema_corruption_is_not_treated_as_empty_store(self):
        for invalid in ({}, {'version': 2, 'devices': {}}, {'version': 1, 'devices': {'bad': {}}}):
            Path(self.directory.name, 'submissions.json').write_text(json.dumps(invalid))
            self.assertTrue(SubmissionGuard(self.directory.name).state('phone')['blocked'])

    def test_failed_atomic_write_preserves_existing_intent(self):
        intent = self.guard.begin('phone', TASK)
        with patch('submission_guard.os.replace', side_effect=OSError('disk error')):
            with self.assertRaises(RuntimeError):
                self.guard.acknowledge('phone', intent['attemptId'], True)
        self.assertTrue(SubmissionGuard(self.directory.name).state('phone')['blocked'])

    def test_concurrent_processes_can_only_create_one_intent(self):
        ctx = multiprocessing.get_context('spawn')
        barrier, results = ctx.Barrier(2), ctx.Queue()
        workers = [ctx.Process(target=compete, args=(self.directory.name, barrier, results)) for _ in range(2)]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join(10)
            self.assertEqual(worker.exitcode, 0)
        outcomes = [results.get(timeout=2) for _ in workers]
        self.assertEqual(sum(result is not None for result in outcomes), 1)
        self.assertTrue(self.guard.state('test-device')['blocked'])

    def test_abrupt_process_exit_leaves_unknown_intent(self):
        worker = multiprocessing.get_context('spawn').Process(target=crash_after_intent, args=(self.directory.name,))
        worker.start()
        worker.join(10)
        self.assertEqual(worker.exitcode, 23)
        restarted = SubmissionGuard(self.directory.name)
        self.assertEqual(restarted.state('crashed-device')['orderState'], 'unknown')
        with self.assertRaises(RuntimeError):
            restarted.assert_clear('crashed-device')

    def test_operation_lock_excludes_another_instance_but_not_status(self):
        second = SubmissionGuard(self.directory.name)
        with self.guard.operation('phone'):
            self.assertFalse(second.state('phone')['blocked'])
            with self.assertRaises(RuntimeError):
                with second.operation('phone'):
                    self.fail('Concurrent operation was permitted')
        with second.operation('phone'):
            pass

    def test_file_or_directory_sync_failure_never_allows_begin_to_succeed(self):
        for failure_at in (1, 2):
            with tempfile.TemporaryDirectory() as directory:
                guard = SubmissionGuard(directory)
                calls = []
                real_sync = os.fsync
                def fail_sync(fd):
                    calls.append(fd)
                    if len(calls) == failure_at:
                        raise OSError('sync failed')
                    real_sync(fd)
                with patch('submission_guard.os.fsync', side_effect=fail_sync):
                    with self.assertRaises(RuntimeError):
                        guard.begin('phone', TASK)
                self.assertTrue(guard.state('phone')['blocked'])
                if failure_at == 2:
                    self.assertTrue(SubmissionGuard(directory).state('phone')['blocked'])

    def test_default_directory_is_persistent_and_environment_override_is_used(self):
        with patch.dict(os.environ, {'TICKETS_ANDROID_STATE_DIR': self.directory.name}):
            self.assertEqual(SubmissionGuard().directory, Path(self.directory.name))
        with patch.dict(os.environ, {}, clear=True), patch('submission_guard.sys.platform', 'darwin'):
            self.assertEqual(SubmissionGuard().directory, Path.home() / 'Library/Application Support/com.tickets/android-submissions')


class CrossEntryGuardTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        for mock in (patch('submission_guard.Path.home', return_value=self.home),
                     patch('submission_guard.sys.platform', 'darwin'),
                     patch.dict(os.environ, {}, clear=True)):
            mock.start()
            self.addCleanup(mock.stop)
        self.canonical = self.home / 'Library/Application Support/com.tickets/android-submissions'
        self.legacy = self.home / 'Library/Application Support/Tickets/android'

    def seed(self, directory, record=None, serial='phone'):
        # Seed a pre-upgrade ledger without invoking the new compatibility behavior.
        directory.mkdir(parents=True, exist_ok=True)
        record = record or {'attemptId': 'a' * 32, 'taskId': TASK, 'orderState': 'unknown',
                            'createdAt': 1000, 'updatedAt': 1000, 'resolvedAt': None}
        data = {'version': 1, 'devices': {SubmissionGuard._key(serial): record}}
        (directory / 'submissions.json').write_text(json.dumps(data))
        return record

    def desktop(self):
        with patch.dict(os.environ, {'TICKETS_ANDROID_STATE_DIR': str(self.canonical)}):
            return SubmissionGuard()

    def test_platform_defaults_match_tauri_app_data_directory(self):
        cases = [('darwin', {}, self.canonical),
                 ('win32', {'APPDATA': str(self.home / 'Roaming'), 'LOCALAPPDATA': str(self.home / 'Local')},
                  self.home / 'Roaming/com.tickets/android-submissions'),
                 ('win32', {}, self.home / 'AppData/Roaming/com.tickets/android-submissions'),
                 ('linux', {}, self.home / '.local/share/com.tickets/android-submissions'),
                 ('linux', {'XDG_DATA_HOME': str(self.home / 'xdg')},
                  self.home / 'xdg/com.tickets/android-submissions'),
                 ('linux', {'XDG_DATA_HOME': 'relative-ignored'},
                  self.home / '.local/share/com.tickets/android-submissions')]
        for platform, env, expected in cases:
            with self.subTest(platform=platform, env=env), patch('submission_guard.sys.platform', platform), \
                    patch.dict(os.environ, env, clear=True):
                self.assertEqual(default_directory(), expected)

    def test_default_cli_and_explicit_desktop_fence_each_other(self):
        for first, second in [(SubmissionGuard(), self.desktop()),
                              (self.desktop(), SubmissionGuard())]:
            with self.subTest(first=first.directory, second=second.directory):
                intent = first.begin('phone', TASK)
                self.assertEqual(second.state('phone'), intent)
                with self.assertRaises(RuntimeError):
                    second.begin('phone', TASK)
                second.acknowledge('phone', intent['attemptId'], True)
                self.assertFalse(first.state('phone')['blocked'])

    def test_explicit_legacy_directory_is_an_alias_not_an_escape(self):
        guard = SubmissionGuard(self.legacy)
        self.assertEqual(guard.directory, self.canonical)
        intent = guard.begin('phone', TASK)
        self.assertEqual(self.desktop().state('phone'), intent)

    def test_unresolved_legacy_record_blocks_cli_and_desktop_without_rewriting(self):
        record = self.seed(self.legacy)
        before = (self.legacy / 'submissions.json').read_bytes()
        for guard in (SubmissionGuard(), self.desktop(), SubmissionGuard(self.canonical)):
            self.assertEqual(guard.state('phone')['attemptId'], record['attemptId'])
            with self.assertRaises(RuntimeError):
                guard.begin('phone', TASK)
        self.assertEqual((self.legacy / 'submissions.json').read_bytes(), before)
        self.assertFalse((self.canonical / 'submissions.json').exists())
        self.desktop().acknowledge('phone', record['attemptId'], True)
        self.assertFalse(SubmissionGuard().state('phone')['blocked'])

    def test_corrupt_legacy_store_cannot_be_ignored_or_overwritten(self):
        self.seed(self.canonical)
        self.legacy.mkdir(parents=True)
        ledger = self.legacy / 'submissions.json'
        ledger.write_text('{corrupt')
        for guard in (SubmissionGuard(), self.desktop()):
            self.assertEqual(guard.state('phone')['status'], 'error')
            with self.assertRaises(RuntimeError):
                guard.begin('other-phone', TASK)
            with self.assertRaises(RuntimeError):
                guard.acknowledge('phone', 'a' * 32, True)
        self.assertEqual(ledger.read_text(), '{corrupt')

    def test_newer_resolved_record_never_hides_legacy_unknown(self):
        legacy = self.seed(self.legacy)
        current = dict(legacy, attemptId='b' * 32, createdAt=5000, updatedAt=6000, resolvedAt=6000)
        self.seed(self.canonical, current)
        before = (self.canonical / 'submissions.json').read_bytes()
        guard = self.desktop()
        self.assertEqual(guard.state('phone')['attemptId'], legacy['attemptId'])
        with self.assertRaises(RuntimeError):
            guard.acknowledge('phone', current['attemptId'], True)
        guard.acknowledge('phone', legacy['attemptId'], True)
        self.assertFalse(guard.state('phone')['blocked'])
        self.assertEqual((self.canonical / 'submissions.json').read_bytes(), before)

    def test_conflicting_unresolved_records_fail_closed_without_choosing_newer(self):
        first = self.seed(self.legacy)
        self.seed(self.canonical, dict(first, attemptId='b' * 32, createdAt=5000, updatedAt=5000))
        before = [(directory / 'submissions.json').read_bytes() for directory in (self.canonical, self.legacy)]
        guard = self.desktop()
        self.assertTrue(guard.state('phone')['blocked'])
        self.assertEqual(guard.state('phone')['status'], 'error')
        for attempt in ('a' * 32, 'b' * 32):
            with self.assertRaises(RuntimeError):
                guard.acknowledge('phone', attempt, True)
        self.assertEqual([(directory / 'submissions.json').read_bytes() for directory in (self.canonical, self.legacy)], before)

    def test_new_intent_is_mirrored_for_old_cli_and_acknowledgement_preserves_files(self):
        guard = self.desktop()
        intent = guard.begin('phone', TASK)
        for directory in (self.canonical, self.legacy):
            stored = json.loads((directory / 'submissions.json').read_text())['devices'][SubmissionGuard._key('phone')]
            self.assertEqual(stored['attemptId'], intent['attemptId'])
            self.assertIsNone(stored['resolvedAt'])
        guard.acknowledge('phone', intent['attemptId'], True)
        for directory in (self.canonical, self.legacy):
            self.assertIsNotNone(json.loads((directory / 'submissions.json').read_text())['devices'][SubmissionGuard._key('phone')]['resolvedAt'])

    def test_new_intent_preserves_unrelated_devices_in_each_ledger(self):
        old = self.seed(self.legacy, serial='legacy-phone')
        current = self.seed(self.canonical, dict(old, attemptId='b' * 32), serial='desktop-phone')
        guard = self.desktop()
        guard.begin('new-phone', TASK)
        self.assertEqual(guard.state('legacy-phone')['attemptId'], old['attemptId'])
        self.assertEqual(guard.state('desktop-phone')['attemptId'], current['attemptId'])
        for directory, serial, expected in ((self.legacy, 'legacy-phone', old),
                                             (self.canonical, 'desktop-phone', current)):
            self.assertEqual(json.loads((directory / 'submissions.json').read_text())['devices'][guard._key(serial)], expected)

    def test_tauri_bundle_identifier_matches_canonical_namespace(self):
        config = Path(__file__).resolve().parents[2] / 'src-tauri/tauri.conf.json'
        self.assertEqual(json.loads(config.read_text())['tauri']['bundle']['identifier'], 'com.tickets')

    def test_partial_mirror_write_or_acknowledgement_failure_keeps_fence_after_restart(self):
        guard = self.desktop()
        import submission_guard
        replace = submission_guard._replace
        def fail_legacy(source, destination):
            if destination.parent == self.legacy:
                raise OSError('legacy disk failure')
            replace(source, destination)
        with patch('submission_guard._replace', side_effect=fail_legacy):
            with self.assertRaises(RuntimeError):
                guard.begin('phone', TASK)
        restarted = SubmissionGuard()
        self.assertTrue(restarted.state('phone')['blocked'])
        attempt = restarted.state('phone')['attemptId']
        restarted.acknowledge('phone', attempt, True)
        intent = restarted.begin('phone', TASK)
        with patch('submission_guard._replace', side_effect=fail_legacy):
            with self.assertRaises(RuntimeError):
                restarted.acknowledge('phone', intent['attemptId'], True)
        self.assertTrue(SubmissionGuard().state('phone')['blocked'])

    def test_explicit_temporary_override_never_reads_or_creates_home_stores(self):
        self.seed(self.legacy)
        with patch.dict(os.environ, {'TICKETS_ANDROID_STATE_DIR': str(self.home / 'isolated-env')}):
            overridden = SubmissionGuard()
        for guard in (SubmissionGuard(self.home / 'isolated-explicit'), overridden):
            self.assertFalse(guard.state('phone')['blocked'])
            guard.begin('phone', TASK)
        self.assertFalse(self.canonical.exists())
        self.assertFalse((self.legacy / 'submissions.lock').exists())

    def test_cross_entry_processes_cannot_race_into_two_intents(self):
        for entries in (('cli', 'desktop'), ('old-cli', 'desktop')):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as home:
                ctx = multiprocessing.get_context('spawn')
                barrier, results = ctx.Barrier(2), ctx.Queue()
                workers = [ctx.Process(target=compete_entry, args=(home, entry, barrier, results)) for entry in entries]
                for worker in workers:
                    worker.start()
                for worker in workers:
                    worker.join(10)
                    self.assertEqual(worker.exitcode, 0)
                outcomes = [results.get(timeout=2) for _ in workers]
                self.assertEqual(sum(result is not None for result in outcomes), 1)
                with patch('submission_guard.Path.home', return_value=Path(home)):
                    state = SubmissionGuard().state('phone')
                    self.assertTrue(state['blocked'])
                    self.assertIn(state['attemptId'], outcomes)
                results.close()
                results.join_thread()

    def test_both_ledger_locks_are_acquired_in_stable_order(self):
        from contextlib import contextmanager
        from submission_guard import _file_lock
        observed = []
        @contextmanager
        def record_lock(path, blocking=True):
            observed.append(path)
            with _file_lock(path, blocking=blocking):
                yield
        guard = self.desktop()
        expected = sorted((self.canonical.resolve() / 'submissions.lock', self.legacy.resolve() / 'submissions.lock'))
        for directories in ((self.canonical, self.legacy), (self.legacy, self.canonical)):
            guard.directories = directories
            observed.clear()
            with patch('submission_guard._file_lock', side_effect=record_lock):
                self.assertFalse(guard.state('phone')['blocked'])
            self.assertEqual(observed, expected)

    def test_same_attempt_partial_acknowledgement_remains_pending(self):
        intent = self.seed(self.legacy)
        self.seed(self.canonical, dict(intent, resolvedAt=2000, updatedAt=2000))
        guard = self.desktop()
        self.assertTrue(guard.state('phone')['blocked'])
        guard.acknowledge('phone', intent['attemptId'], True)
        self.assertFalse(SubmissionGuard().state('phone')['blocked'])

    def test_platform_legacy_paths_are_retained_for_windows_and_linux(self):
        for platform, env, canonical, legacy in [
                ('win32', {'APPDATA': str(self.home / 'Roaming'), 'LOCALAPPDATA': str(self.home / 'Local')},
                 self.home / 'Roaming/com.tickets/android-submissions', self.home / 'Local/Tickets/android'),
                ('linux', {'XDG_DATA_HOME': str(self.home / 'xdg')},
                 self.home / 'xdg/com.tickets/android-submissions', self.home / 'xdg/Tickets/android')]:
            with self.subTest(platform=platform), patch('submission_guard.sys.platform', platform), \
                    patch.dict(os.environ, env, clear=True):
                old = self.seed(legacy)
                guard = SubmissionGuard(canonical)
                self.assertEqual(guard.state('phone')['attemptId'], old['attemptId'])
                with self.assertRaises(RuntimeError):
                    guard.begin('phone', TASK)

    def test_operation_locks_cover_both_current_and_legacy_directories(self):
        from submission_guard import _file_lock
        guard = self.desktop()
        guard.state('phone')
        for directory in (self.canonical, self.legacy):
            with _file_lock(directory / (guard._key('phone') + '.operation.lock'), blocking=False):
                with self.assertRaises(RuntimeError):
                    with guard.operation('phone'):
                        self.fail('Legacy operation lock was bypassed')


if __name__ == '__main__':
    unittest.main()
