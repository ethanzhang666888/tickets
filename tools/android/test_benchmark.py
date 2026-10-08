import unittest
from benchmark import summarize, safe_sample, validate_samples


class BenchmarkTests(unittest.TestCase):
    def test_nearest_rank_p95_and_median(self):
        self.assertEqual(summarize(list(range(1, 31))), {'count': 30, 'median': 15.5, 'p95': 29, 'min': 1, 'max': 30})

    def test_empty_sample_and_nonfinite_values_rejected(self):
        for values in ([], [True], [-1], [float('nan')], [float('inf')]):
            with self.assertRaises(ValueError):
                summarize(values)

    def test_report_cannot_include_page_or_identity_text(self):
        page = {'kind': 'confirm', 'title': 'private', 'buyers': [{'name': 'private', 'id': 'secret'}], 'blockers': []}
        state = {'metrics': {'readMs': 12.4, 'ocrMs': 3.1, 'cacheHit': False, 'ocrUsed': True, 'secret': 'private'}}
        sample = safe_sample(15, page, state)
        self.assertNotIn('private', str(sample))
        self.assertNotIn('secret', str(sample))
        self.assertEqual(sample['kind'], 'confirm')
        self.assertEqual(sample['readMs'], 12.4)

    def test_invalid_or_blocked_page_invalidates_measurement(self):
        for page in ({'kind': 'unknown', 'blockers': []}, {'kind': 'picker', 'blockers': ['private']}):
            with self.assertRaisesRegex(ValueError, '页面'):
                safe_sample(10, page, {'metrics': {}})

    def test_sample_count_bounded(self):
        for count in (0, 2, 301, True):
            with self.assertRaises(ValueError):
                validate_samples(count)
        self.assertEqual(validate_samples(30), 30)


if __name__ == '__main__':
    unittest.main()
