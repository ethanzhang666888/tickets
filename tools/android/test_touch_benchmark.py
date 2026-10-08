"""Offline tests only: no device connection, sockets, or input injection."""
import copy
import unittest
from unittest.mock import patch

import touch_benchmark as tb


def snapshot():
    return {
        'package': tb.PACKAGE, 'activity': tb.ACTIVITY,
        'focusedPackage': tb.PACKAGE, 'focusedActivity': tb.ACTIVITY,
        'synthetic': True, 'unlocked': True, 'displayId': 0,
        'rotation': 0, 'width': 1080, 'height': 2340,
        'targetId': tb.TARGET, 'targetCount': 1,
        'bounds': [20, 100, 1000, 260], 'counter': 0, 'session': 'test-session',
    }


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class Harness:
    """In-memory synthetic fixture; never a live-device adapter."""
    def __init__(self, clock):
        self.clock = clock
        self.state = snapshot()
        self.calls = []
        self.no_effect = False
        self.fail_dispatch = False
        self.extra_click = False

    def observe(self):
        return copy.deepcopy(self.state)

    def dispatch(self, method, expected):
        self.calls.append(method)
        self.clock.sleep(.002)
        if self.fail_dispatch:
            raise TimeoutError('untrusted private transport message')
        if not self.no_effect:
            self.state['counter'] += 2 if self.extra_click else 1


class SafetyTests(unittest.TestCase):
    def test_valid_identity(self):
        tb.validate_snapshot(snapshot())

    def test_rejects_every_unsafe_identity_or_geometry(self):
        for field, value in (
            ('package', 'cn.damai'), ('activity', tb.PACKAGE + '.MainActivity'),
            ('focusedPackage', 'com.android.systemui'), ('focusedActivity', 'dialog'),
            ('synthetic', False), ('unlocked', False), ('displayId', 1),
            ('rotation', True), ('rotation', 4), ('width', 0), ('height', 0),
            ('counter', True), ('counter', -1), ('targetCount', 2),
            ('targetId', 'anything'), ('bounds', [0, 0, 2000, 3000]),
            ('bounds', [0, 0, 0, 10]), ('bounds', [True, 0, 10, 10]),
            ('session', ''),
        ):
            with self.subTest(field=field, value=value):
                state = snapshot()
                state[field] = value
                with self.assertRaises(tb.SafetyError):
                    tb.validate_snapshot(state)

    def test_missing_fields_rejected(self):
        for field in snapshot():
            state = snapshot()
            del state[field]
            with self.subTest(field=field), self.assertRaises(tb.SafetyError):
                tb.validate_snapshot(state)

    def test_exact_initial_surface_and_session_required(self):
        for field, value in [('rotation', 1), ('width', 1079), ('height', 2339),
                             ('bounds', [30, 100, 1000, 260]), ('session', 'another')]:
            changed = snapshot()
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(tb.SafetyError):
                tb.validate_snapshot(changed, snapshot())

    def test_counter_can_increment_without_identity_change(self):
        state = snapshot()
        state['counter'] = 3
        tb.validate_snapshot(state, snapshot())


class BenchmarkTests(unittest.TestCase):
    def run_fixture(self, fixture=None, **kwargs):
        clock = Clock()
        fixture = fixture or Harness(clock)
        return tb.run_benchmark(fixture.observe, fixture.dispatch,
            ['adb_input', 'uiautomator', 'minitouch'],
            warmups=1, samples=6, seed=42, timeout=.02, poll_interval=.005,
            clock=fixture.clock, sleep=fixture.clock.sleep, **kwargs), fixture

    def test_interleaved_reproducible_balanced_schedule(self):
        first = tb.schedule(tb.METHODS, 3, 6, 42)
        self.assertEqual(first, tb.schedule(tb.METHODS, 3, 6, 42))
        for offset in range(0, len(first), 3):
            block = first[offset:offset + 3]
            self.assertEqual({row['method'] for row in block}, set(tb.METHODS))
        self.assertEqual(sum(row['warmup'] for row in first), 9)
        self.assertEqual(len(first), 27)

    def test_warmups_excluded_dispatch_separate_from_observation(self):
        report, fixture = self.run_fixture()
        self.assertEqual(report['status'], 'complete')
        self.assertEqual(len(fixture.calls), 21)
        for result in report['methods'].values():
            self.assertEqual(result['successes'], 6)
            self.assertEqual(result['timeouts'], 0)
            self.assertEqual(result['warmupAttempts'], 1)
            self.assertEqual(result['dispatchMs']['count'], 6)
            self.assertEqual(result['confirmedEffectMs']['count'], 6)
            self.assertAlmostEqual(result['dispatchMs']['median'], 2)
        self.assertEqual(report['evidenceKind'], 'caller_supplied_harness')
        self.assertNotIn('winner', report)

    def test_delayed_effect_is_distinct_from_dispatch(self):
        clock = Clock()
        fixture = Harness(clock)
        fixture.no_effect = True
        original = fixture.observe
        def observe():
            if fixture.calls and clock.now >= .012:
                fixture.state['counter'] = 1
            return original()
        report = tb.run_benchmark(observe, fixture.dispatch, ['adb_input'],
                                  warmups=0, samples=1, timeout=.1, poll_interval=.005,
                                  clock=clock, sleep=clock.sleep)
        result = report['methods']['adb_input']
        self.assertEqual(result['dispatchMs']['median'], 2)
        self.assertEqual(result['confirmedEffectMs']['median'], 12)

    def test_late_observation_is_censored(self):
        clock = Clock()
        fixture = Harness(clock)
        original = fixture.observe
        def observe():
            if fixture.calls:
                clock.sleep(.1)
            return original()
        report = tb.run_benchmark(observe, fixture.dispatch, ['adb_input'],
                                  warmups=0, samples=2, timeout=.02,
                                  clock=clock, sleep=clock.sleep)
        self.assertEqual(report['methods']['adb_input']['timeouts'], 1)
        self.assertEqual(report['methods']['adb_input']['successes'], 0)
        self.assertIsNone(report['methods']['adb_input']['confirmedEffectMs'])
        self.assertEqual(len(fixture.calls), 1)

    def test_dispatch_timeout_is_separate_from_effect_timeout(self):
        clock = Clock()
        fixture = Harness(clock)
        original = fixture.dispatch
        def dispatch(method, expected):
            original(method, expected)
            clock.sleep(.1)
        report = tb.run_benchmark(fixture.observe, dispatch, ['adb_input'],
                                  warmups=0, samples=1, timeout=.02,
                                  clock=clock, sleep=clock.sleep)
        self.assertEqual(report['reason'], 'dispatch_timeout')
        self.assertIsNone(report['samples'][0]['confirmedEffectMs'])

    def test_timeout_aborts_without_retry_or_next_injection(self):
        clock = Clock()
        fixture = Harness(clock)
        fixture.no_effect = True
        report, fixture = self.run_fixture(fixture)
        self.assertEqual(report['status'], 'aborted')
        self.assertEqual(report['reason'], 'effect_timeout')
        self.assertEqual(len(fixture.calls), 1)
        self.assertIsNone(report['samples'][0]['confirmedEffectMs'])
        self.assertEqual(sum(r['warmupTimeouts'] for r in report['methods'].values()), 1)

    def test_dispatch_error_is_not_success_and_is_sanitized(self):
        clock = Clock()
        fixture = Harness(clock)
        fixture.fail_dispatch = True
        report, fixture = self.run_fixture(fixture)
        self.assertEqual(report['reason'], 'dispatch_uncertain')
        self.assertEqual(len(fixture.calls), 1)
        self.assertIsNone(report['samples'][0]['dispatchMs'])
        self.assertNotIn('private', str(report))

    def test_extra_effect_aborts(self):
        clock = Clock()
        fixture = Harness(clock)
        fixture.extra_click = True
        report, _ = self.run_fixture(fixture)
        self.assertEqual(report['reason'], 'counter_unexpected')

    def test_wrong_foreground_never_dispatches(self):
        clock = Clock()
        fixture = Harness(clock)
        fixture.state['activity'] = tb.PACKAGE + '.MainActivity'
        report, fixture = self.run_fixture(fixture)
        self.assertEqual(report['status'], 'aborted')
        self.assertEqual(fixture.calls, [])

    def test_focus_change_after_dispatch_invalidates_sample(self):
        clock = Clock()
        fixture = Harness(clock)
        original = fixture.dispatch
        def change(method, expected):
            original(method, expected)
            fixture.state['focusedPackage'] = 'com.android.systemui'
        fixture.dispatch = change
        report, fixture = self.run_fixture(fixture)
        self.assertEqual(report['reason'], 'unsafe_snapshot')
        self.assertIsNone(report['samples'][0]['confirmedEffectMs'])
        self.assertEqual(len(fixture.calls), 1)

    def test_count_change_between_trials_never_gets_attributed(self):
        clock = Clock()
        fixture = Harness(clock)
        original = fixture.observe
        count = 0
        def observe():
            nonlocal count
            count += 1
            if count == 4:
                fixture.state['counter'] += 1
            return original()
        fixture.observe = observe
        report, fixture = self.run_fixture(fixture)
        self.assertEqual(report['reason'], 'counter_unexpected')
        self.assertEqual(len(fixture.calls), 1)

    def test_invalid_parameters_have_no_side_effects(self):
        for kwargs in [dict(samples=0), dict(warmups=-1), dict(timeout=float('nan')),
                       dict(poll_interval=0), dict(samples=True), dict(seed=True)]:
            fixture = Harness(Clock())
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                tb.run_benchmark(fixture.observe, fixture.dispatch, ['adb_input'], **kwargs)
            self.assertEqual(fixture.calls, [])

    def test_unsupported_not_measured(self):
        report = tb.run_benchmark(lambda: self.fail('no observations required'),
                                  lambda *_: self.fail('must not dispatch'), [])
        self.assertEqual(report['status'], 'blocked')
        self.assertTrue(all(row['status'] == 'unsupported' for row in report['methods'].values()))

    def test_untrusted_observer_errors_are_not_exported(self):
        def observe():
            raise tb.SafetyError('private value from transport')
        report = tb.run_benchmark(observe, lambda *_: self.fail('must not dispatch'), ['adb_input'])
        self.assertEqual(report['reason'], 'observation_failed')
        self.assertNotIn('private', str(report))

    def test_nearest_rank_percentiles_and_empty(self):
        self.assertEqual(tb.summarize(range(1, 21))['p95'], 19)
        self.assertIsNone(tb.summarize([]))
        for values in [[True], [-1], [float('inf')]]:
            with self.assertRaises(ValueError):
                tb.summarize(values)


class ProbeTests(unittest.TestCase):
    def test_probe_commands_have_no_injection_or_process_start(self):
        commands = ' '.join(tb.PROBE_COMMANDS.values())
        for forbidden in ['input tap', 'sendevent', 'am start', 'install ', 'chmod ',
                          'uiautomator dump', 'app_process /', 'setenforce', 'su ', 'kill ']:
            self.assertNotIn(forbidden, commands)

    def test_metadata_never_claims_runtime_success(self):
        output = {name: {'ok': True, 'text': ''} for name in tb.PROBE_COMMANDS}
        output['sdk']['text'] = '29'
        output['selinux']['text'] = 'Enforcing'
        output['event_access']['text'] = '/dev/input/event4 readable=yes writable=no'
        report = tb.classify_probe(output)
        self.assertEqual(report['methods']['minitouch']['status'], 'blocked')
        self.assertEqual(report['timingSamples'], [])
        self.assertFalse(report['liveInjectionAttempted'])

    def test_unknown_query_is_unknown_not_absent(self):
        report = tb.classify_probe({})
        self.assertIsNone(report['minitouch']['processObserved'])
        self.assertIsNone(report['minitouch']['stfPackageObserved'])
        self.assertIsNone(report['device']['sdk'])

    def test_foreground_redacts_unrelated_apps(self):
        report = tb.classify_probe({'foreground': {'ok': True, 'text':
            'mCurrentFocus=Window{abc u0 private.app/private.app.SecretActivity}'}})
        self.assertNotIn('private.app', str(report))
        self.assertFalse(report['foreground']['syntheticFocused'])

    @patch('touch_benchmark.subprocess.run')
    def test_invalid_serial_never_runs(self, run):
        with self.assertRaises(ValueError):
            tb.probe('different-device')
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
