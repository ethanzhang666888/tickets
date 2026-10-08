"""Read-only Huawei touch capability probe and bounded synthetic benchmark core.

CLI: python3 tools/android/touch_benchmark.py --serial 6HJDU19731013874
No CLI mode injects input, launches UIAutomator, installs software, opens a
minitouch socket, or changes the foreground. No live dispatch adapter is bundled:
the current feasibility APK exposes no remotely callable synthetic-only click.

run_benchmark is an in-process integration seam, NOT a security sandbox. Its
trusted caller must supply a scoped dispatcher that independently enforces the
expected synthetic snapshot at dispatch, bounded transport calls, and exclusive
ownership of the synthetic counter. Global adb/minitouch coordinates cannot be
made atomic with an external foreground check. Do not plug unguarded global
injection into this seam. Offline tests use only an in-memory fixture.
"""
import argparse
import copy
import json
import math
import random
import re
import shutil
import statistics
import subprocess
import time
from datetime import datetime, timezone

SERIAL = '6HJDU19731013874'
PACKAGE = 'local.tickets.companion'
ACTIVITY = PACKAGE + '.SyntheticActivity'
TARGET = PACKAGE + ':id/synthetic_target'
METHODS = ('adb_input', 'uiautomator', 'minitouch')
SOURCE_COMMIT = '0f881ad36cdab6745704c5dc39e2be5453035c4f'

# Fixed read-only commands; never interpolate caller input into the remote shell.
# getevent -lp lists device capabilities and exits; it does NOT stream events.
PROBE_COMMANDS = {
    'manufacturer': 'getprop ro.product.manufacturer',
    'model': 'getprop ro.product.model',
    'sdk': 'getprop ro.build.version.sdk',
    'release': 'getprop ro.build.version.release',
    'abi': 'getprop ro.product.cpu.abilist',
    'build_type': 'getprop ro.build.type',
    'debuggable': 'getprop ro.debuggable',
    'identity': 'id',
    'selinux': 'getenforce',
    'size': 'wm size',
    'density': 'wm density',
    'surface': 'dumpsys input | grep -E "SurfaceOrientation|SurfaceWidth|SurfaceHeight"',
    'foreground': 'dumpsys window | grep -E "mCurrentFocus=|isStatusBarKeyguard="',
    'companion': 'dumpsys package local.tickets.companion | grep -E "versionCode=|versionName="',
    'input_command': 'command -v input',
    'processes': 'ps -A -o ARGS',
    'stf_package': 'pm list packages jp.co.cyberagent.stf',
    'minitouch_files': 'for f in /data/local/tmp/minitouch /data/local/tmp/minitouch-nopie; '
                      'do if test -f "$f"; then echo "$f"; fi; done',
    'sockets': 'cat /proc/net/unix',
    'event_access': 'for f in /dev/input/event*; do if test -e "$f"; then '
                    'r=no; w=no; if test -r "$f"; then r=yes; fi; '
                    'if test -w "$f"; then w=yes; fi; '
                    'echo "$f readable=$r writable=$w"; fi; done',
    'event_labels': 'ls -lZ /dev/input/event*',
    'input_capabilities': 'getevent -lp',
}


class SafetyError(ValueError):
    pass


def _int(value, minimum, maximum):
    return type(value) is int and minimum <= value <= maximum


def validate_snapshot(state, expected=None):
    """Reject unknown fields needed for identity; export no UI text or coordinates."""
    if not isinstance(state, dict):
        raise SafetyError('unsafe_snapshot')
    exact = {'package': PACKAGE, 'activity': ACTIVITY, 'focusedPackage': PACKAGE,
             'focusedActivity': ACTIVITY, 'targetId': TARGET}
    if any(state.get(key) != value for key, value in exact.items()):
        raise SafetyError('unsafe_snapshot')
    if state.get('synthetic') is not True or state.get('unlocked') is not True:
        raise SafetyError('unsafe_snapshot')
    for key, low, high in [('displayId', 0, 0), ('rotation', 0, 3),
                           ('width', 1, 16384), ('height', 1, 16384),
                           ('targetCount', 1, 1), ('counter', 0, 2**53 - 1)]:
        if not _int(state.get(key), low, high):
            raise SafetyError('unsafe_snapshot')
    bounds = state.get('bounds')
    if (not isinstance(bounds, (list, tuple)) or len(bounds) != 4
            or any(type(value) is not int for value in bounds)):
        raise SafetyError('unsafe_snapshot')
    left, top, right, bottom = bounds
    if not (0 <= left < right <= state['width'] and 0 <= top < bottom <= state['height']):
        raise SafetyError('unsafe_snapshot')
    session = state.get('session')
    if not isinstance(session, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', session):
        raise SafetyError('unsafe_snapshot')
    if expected is not None:
        keys = (*exact, 'synthetic', 'unlocked', 'displayId', 'rotation', 'width',
                'height', 'targetCount', 'bounds', 'session')
        if any(state.get(key) != expected.get(key) for key in keys):
            raise SafetyError('unsafe_snapshot')


def summarize(values):
    values = list(values)
    if not values:
        return None
    if any(type(v) not in (int, float) or not math.isfinite(v) or v < 0 for v in values):
        raise ValueError('invalid_timing')
    ordered = sorted(values)
    return {'count': len(values), 'median': round(statistics.median(values), 4),
            'p95': round(ordered[math.ceil(.95 * len(values)) - 1], 4)}


def schedule(methods, warmups, samples, seed):
    if (not _int(warmups, 0, 30) or not _int(samples, 1, 300)
            or not _int(seed, 0, 2**32 - 1) or len(methods) != len(set(methods))
            or any(method not in METHODS for method in methods)):
        raise ValueError('invalid_schedule')
    rng = random.Random(seed)
    result = []
    # Each round contains each available method once: time drift affects all
    # methods, rather than running all samples of one method before the next.
    for warmup, count in [(True, warmups), (False, samples)]:
        for index in range(count):
            block = list(methods)
            rng.shuffle(block)
            result.extend({'method': name, 'warmup': warmup, 'round': index}
                          for name in block)
    return result


def run_benchmark(observe, dispatch, available_methods, *, warmups=3, samples=30,
                  seed=20261009, timeout=2.0, poll_interval=.01,
                  clock=time.perf_counter, sleep=time.sleep):
    """Measure a trusted, independently scoped harness; never invent live support.

    observe() returns a fresh Snapshot (see validate_snapshot).
    dispatch(method, expected_snapshot) returns when injection dispatch returns,
    NOT when the target click handler executes. It must reject stale expectations.
    Both callbacks must have bounded I/O; Python cannot preempt arbitrary callbacks.
    The host clock brackets dispatch and the first confirmed counter observation.
    Any uncertainty aborts the entire run without retries or further injections.
    """
    plan = schedule(available_methods, warmups, samples, seed)
    for value in (timeout, poll_interval):
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise ValueError('invalid_deadline')
    if timeout > 10 or poll_interval > timeout:
        raise ValueError('invalid_deadline')
    report = {'schemaVersion': 1, 'evidenceKind': 'caller_supplied_harness',
              'status': 'complete' if available_methods else 'blocked',
              'seed': seed, 'warmupsPerMethod': warmups, 'samplesPerMethod': samples,
              'effectTimeoutMs': timeout * 1000, 'pollIntervalMs': poll_interval * 1000,
              'samples': [], 'methods': {},
              'limitations': [
                  'No bundled live dispatcher; fixture timings are not device evidence.',
                  'Dispatch return does not prove Android accepted or displayed a touch.',
                  'Confirmed effect is target-handler counter observation, not frame presentation.',
                  'Host effect time includes transport and polling; not pure device injection time.',
                  'Counter attribution requires exclusive synthetic session with no manual touches.',
                  'No latency winner, confidence interval, or purchase success claim is inferred.',
              ]}
    expected = None
    counter = None
    try:
        if available_methods:
            expected = copy.deepcopy(observe())
            validate_snapshot(expected)
            counter = expected['counter']
        for entry in plan:
            before = copy.deepcopy(observe())
            validate_snapshot(before, expected)
            if before['counter'] != counter:
                raise SafetyError('counter_unexpected')
            row = dict(entry, outcome='pending', dispatchMs=None, confirmedEffectMs=None)
            report['samples'].append(row)
            began = clock()
            try:
                dispatch(entry['method'], copy.deepcopy(before))
            except (OSError, RuntimeError, ValueError):
                raise SafetyError('dispatch_uncertain') from None
            returned = clock()
            row['dispatchMs'] = (returned - began) * 1000
            if returned - began >= timeout:
                raise SafetyError('dispatch_timeout')
            while True:
                after = observe()
                observed = clock()
                validate_snapshot(after, expected)
                if after['counter'] not in (counter, counter + 1):
                    raise SafetyError('counter_unexpected')
                # Observations arriving after deadline are censored, not successes.
                if observed - began >= timeout:
                    raise SafetyError('effect_timeout')
                if after['counter'] == counter + 1:
                    row['confirmedEffectMs'] = (observed - began) * 1000
                    row['outcome'] = 'success'
                    counter += 1
                    break
                sleep(min(poll_interval, timeout - (observed - began)))
    except (OSError, RuntimeError, ValueError) as error:
        safe_reasons = {'unsafe_snapshot', 'counter_unexpected', 'dispatch_uncertain',
                        'dispatch_timeout', 'effect_timeout'}
        reason = str(error) if isinstance(error, SafetyError) and str(error) in safe_reasons else 'observation_failed'
        report.update(status='aborted', reason=reason)
        if report['samples'] and report['samples'][-1]['outcome'] == 'pending':
            report['samples'][-1]['outcome'] = reason
    for method in METHODS:
        rows = [row for row in report['samples'] if row['method'] == method]
        warm = [row for row in rows if row['warmup']]
        measured = [row for row in rows if not row['warmup']]
        timeouts = ('effect_timeout', 'dispatch_timeout')
        report['methods'][method] = {
            'status': ('unsupported' if method not in available_methods else
                       'measured' if len(measured) == samples and all(r['outcome'] == 'success' for r in measured)
                       else 'incomplete'),
            'warmupAttempts': len(warm),
            'warmupSuccesses': sum(row['outcome'] == 'success' for row in warm),
            'warmupTimeouts': sum(row['outcome'] in timeouts for row in warm),
            'attempts': len(measured),
            'successes': sum(row['outcome'] == 'success' for row in measured),
            'timeouts': sum(row['outcome'] in timeouts for row in measured),
            'errors': sum(row['outcome'] not in ('success', *timeouts) for row in measured),
            'dispatchMs': summarize(row['dispatchMs'] for row in measured if row['dispatchMs'] is not None),
            'confirmedEffectMs': summarize(row['confirmedEffectMs'] for row in measured if row['outcome'] == 'success'),
        }
    return report


def classify_probe(results):
    """Produce only allowlisted metadata; never export raw ps/window/socket output."""
    def text(key):
        result = results.get(key, {})
        return result.get('text', '').strip() if result.get('ok') is True else None

    def contains(key, pattern):
        value = text(key)
        return bool(re.search(pattern, value, re.M)) if value is not None else None

    def safe_property(key):
        value = text(key)
        return value if value and re.fullmatch(r'[A-Za-z0-9_,. -]{1,100}', value) else None

    def number(key, pattern):
        value = text(key)
        match = re.search(pattern, value) if value else None
        return int(match.group(1)) if match else None

    device = {key: safe_property(key) for key in ('manufacturer', 'model', 'release', 'abi', 'build_type')}
    device.update(sdk=number('sdk', r'^(\d{1,3})$'),
                  shellUid=number('identity', r'\buid=(\d+)\('),
                  shellHasInputGroup=contains('identity', r'\b1004\(input\)'),
                  selinux=safe_property('selinux'), debuggable=safe_property('debuggable'),
                  physicalWidth=number('size', r'Physical size: (\d+)x\d+'),
                  physicalHeight=number('size', r'Physical size: \d+x(\d+)'),
                  surfaceWidth=number('surface', r'SurfaceWidth: (\d+)px'),
                  surfaceHeight=number('surface', r'SurfaceHeight: (\d+)px'),
                  rotation=number('surface', r'SurfaceOrientation: ([0-3])'),
                  physicalDensity=number('density', r'Physical density: (\d+)'),
                  overrideDensity=number('density', r'Override density: (\d+)'))
    access = []
    for path, readable, writable in re.findall(
            r'(/dev/input/event\d+) readable=(yes|no) writable=(yes|no)', text('event_access') or ''):
        access.append({'path': path, 'readable': readable == 'yes', 'writableAccessCheck': writable == 'yes'})
    # Capture touchscreen axes only, not unrelated device names or key values.
    touches = []
    for block in re.split(r'add device \d+: ', text('input_capabilities') or ''):
        path = re.match(r'(/dev/input/event\d+)\b', block)
        x = re.search(r'ABS_MT_POSITION_X\s*:.*?min (-?\d+), max (-?\d+)', block)
        y = re.search(r'ABS_MT_POSITION_Y\s*:.*?min (-?\d+), max (-?\d+)', block)
        if path and x and y:
            touches.append({'path': path.group(1), 'xRange': [int(n) for n in x.groups()],
                            'yRange': [int(n) for n in y.groups()],
                            'direct': 'INPUT_PROP_DIRECT' in block})
    focus = text('foreground') or ''
    component = re.search(r'mCurrentFocus=Window\{[^\n]*?\bu\d+\s+([^\s}]+)', focus)
    synthetic_focused = bool(component and component.group(1) in
                             (PACKAGE + '/' + ACTIVITY, PACKAGE + '/.SyntheticActivity'))
    main_focused = bool(component and component.group(1) in
                        (PACKAGE + '/' + PACKAGE + '.MainActivity', PACKAGE + '/.MainActivity'))
    minitouch = {
        'processObserved': contains('processes', r'(?m)^\S*minitouch(?:\s|$)'),
        'binaryObservedAtKnownPaths': contains('minitouch_files', r'^/data/local/tmp/minitouch(?:-nopie)?$'),
        'socketObserved': contains('sockets', r'\s@minitouch$'),
        'stfAgentSocketObserved': contains('sockets', r'\s@minitouchagent$'),
        'stfPackageObserved': contains('stf_package', r'^package:jp\.co\.cyberagent\.stf$'),
        'inputDevices': touches, 'eventAccess': access,
        'sourceCommit': SOURCE_COMMIT, 'runtimeVerified': False,
    }
    reasons = ['no_scoped_remote_synthetic_dispatcher', 'runtime_injection_not_attempted']
    if device['sdk'] is not None and device['sdk'] >= 29:
        reasons.append('upstream_android_10_security_policy_requires_stf_workaround')
    touch_paths = {row['path'] for row in touches}
    if any(row['path'] in touch_paths and not row['writableAccessCheck'] for row in access):
        reasons.append('touch_device_write_access_check_denied')
    if minitouch['binaryObservedAtKnownPaths'] is False:
        reasons.append('minitouch_absent_at_known_paths')
    if minitouch['stfPackageObserved'] is False:
        reasons.append('stf_package_not_observed')
    return {
        'schemaVersion': 1, 'mode': 'read_only_metadata_probe', 'status': 'blocked',
        'liveInjectionAttempted': False, 'timingSamples': [], 'device': device,
        'foreground': {'syntheticFocused': synthetic_focused, 'mainFocused': main_focused,
                       'focusQueryKnown': component is not None,
                       'keyguardReported': contains('foreground', r'isStatusBarKeyguard=true')},
        'companion': {'versionName': (re.search(r'versionName=([A-Za-z0-9_.-]+)', text('companion') or '').group(1)
                                     if re.search(r'versionName=([A-Za-z0-9_.-]+)', text('companion') or '') else None),
                      'remoteSyntheticClickVerified': False},
        'minitouch': minitouch,
        'methods': {
            'adb_input': {'status': 'unmeasured', 'commandObserved': contains('input_command', r'^/\S+/input$')},
            'uiautomator': {'status': 'unmeasured', 'existingServerObserved': contains('processes', r'\bcom\.wetest\.uia2\.Main\b')},
            'minitouch': {'status': 'blocked', 'reasons': reasons},
        },
        'queryFailures': sorted(key for key in PROBE_COMMANDS if not results.get(key, {}).get('ok')),
        'limitations': [
            'Metadata/access checks do not prove O_RDWR, injection, handler execution, or latency.',
            'Known-path absence is not an exhaustive filesystem search.',
            'No input-device write, minitouch connection, service startup, installation, or permission change.',
            'An ordinary APK wrapper does not inherit ADB shell or input injection privileges.',
        ],
    }


def probe(serial):
    if serial != SERIAL:
        raise ValueError('This bounded probe accepts only the assigned Huawei serial.')
    adb = shutil.which('adb')
    if not adb:
        raise ValueError('ADB is unavailable.')
    results = {}
    for name, command in PROBE_COMMANDS.items():
        try:
            result = subprocess.run([adb, '-s', serial, 'shell', command], capture_output=True,
                                    text=True, timeout=12)
            results[name] = {'ok': result.returncode == 0, 'text': result.stdout}
        except (OSError, subprocess.TimeoutExpired):
            results[name] = {'ok': False, 'text': ''}
    report = classify_probe(results)
    report['observedAt'] = datetime.now(timezone.utc).isoformat()
    return report


def main():
    parser = argparse.ArgumentParser(description='Read-only touch compatibility probe; never injects input.')
    parser.add_argument('--serial', required=True, choices=[SERIAL])
    args = parser.parse_args()
    try:
        print(json.dumps(probe(args.serial), ensure_ascii=False, indent=2))
        # A completed metadata probe is successful even when injection is blocked.
        return 0
    except (ValueError, OSError):
        print(json.dumps({'status': 'incomplete', 'reason': 'probe_unavailable', 'liveInjectionAttempted': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
