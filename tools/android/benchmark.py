# /// script
# requires-python = ">=3.10"
# dependencies = ["uiautomator2==3.7.0"]
# ///
"""Read-only device benchmark. Never selects, prepares, clicks or submits.

Run with the same fixed visible page before/after a backend change. The first
sample is reported separately; cache hits and misses must not be conflated.
Only timings, page categories and cache flags leave the reader's memory.
"""
import argparse
import hashlib
import json
import math
import statistics
import time

METRICS = ('readMs', 'treeMs', 'screenshotMs', 'ocrMs')
KINDS = ('detail', 'picker', 'reservation', 'confirm')


def validate_samples(count):
    if type(count) is not int or not 3 <= count <= 300:
        raise ValueError('采样数量须为 3–300。')
    return count


def summarize(values):
    if not values or any(type(v) not in (int, float) or not math.isfinite(v) or v < 0 for v in values):
        raise ValueError('采样必须为有限非负数。')
    ordered = sorted(values)
    return {'count': len(ordered), 'median': round(statistics.median(ordered), 2),
            'p95': round(ordered[math.ceil(.95 * len(ordered)) - 1], 2),
            'min': round(ordered[0], 2), 'max': round(ordered[-1], 2)}


def safe_sample(elapsed, page, state):
    if page.get('kind') not in KINDS or page.get('blockers'):
        raise ValueError('页面未知、被阻断或不适合测量，未继续采样。')
    metrics = state.get('metrics', {})
    sample = {'elapsedMs': round(elapsed, 2), 'kind': page['kind']}
    for key in METRICS:
        value = metrics.get(key)
        if type(value) in (int, float) and math.isfinite(value) and value >= 0:
            sample[key] = value
    for key in ('cacheHit', 'ocrUsed'):
        if type(metrics.get(key)) is bool:
            sample[key] = metrics[key]
    return sample


def page_key(page):
    # Memory-only identity. It is deliberately never exported, even as a digest.
    data = {key: page.get(key) for key in ('kind', 'title', 'sessions', 'tiers', 'quantity', 'totalPrice', 'selectedBuyerCount')}
    return hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False).encode()).digest()


def measure(driver, serial, count):
    validate_samples(count)
    samples = []
    expected = None
    for index in range(count + 1):
        began = time.perf_counter()
        page = driver.read(serial, driver.token(serial))
        elapsed = (time.perf_counter() - began) * 1000
        identity = page_key(page.data)
        if expected is not None and identity != expected:
            raise ValueError('测量期间目标或页面发生变化，本组不可用于固定页面比较。')
        expected = identity
        samples.append(safe_sample(elapsed, page.data, driver.state(serial)))
        if index < count:
            time.sleep(.1)  # only reads local UI; does not request inventory refresh
    warm = samples[1:]
    summary = {key: summarize([sample[key] for sample in warm if key in sample])
               for key in ('elapsedMs', *METRICS) if any(key in sample for sample in warm)}
    groups = {}
    for label, predicate in (
        ('nativeOnly', lambda s: s.get('ocrUsed') is False),
        ('ocrCacheHit', lambda s: s.get('ocrUsed') is True and s.get('cacheHit') is True),
        ('ocrCacheMiss', lambda s: s.get('ocrUsed') is True and s.get('cacheHit') is False),
    ):
        values = [s['elapsedMs'] for s in warm if predicate(s)]
        if values:
            groups[label] = summarize(values)
    return {'schemaVersion': 1, 'backend': 'mac-uiautomator', 'mode': 'read_only',
            'firstRead': samples[0], 'warmSummaryMs': summary, 'warmGroupsMs': groups,
            'samples': warm, 'limitations': ['固定页面读取，不是库存刷新、点击、下单或成功率测量。',
                                           'firstRead 是本进程首次读取，不等于全新安装的冷启动。']}


def main():
    parser = argparse.ArgumentParser(description='只读固定页面基准，不点击、不提交、不保存截图或页面文字。')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--samples', type=int, default=30, help='首次读取以外的样本数，默认 30')
    args = parser.parse_args()
    validate_samples(args.samples)
    import re
    if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', args.serial):
        parser.error('设备标识无效。')
    from driver import Driver
    driver = Driver()
    try:
        report = measure(driver, args.serial, args.samples)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, RuntimeError, ConnectionError) as error:
        print(json.dumps({'status': 'incomplete', 'error': str(error).replace(args.serial, '[device]')}, ensure_ascii=False))
        return 1
    finally:
        driver.shutdown()


if __name__ == '__main__':
    raise SystemExit(main())
