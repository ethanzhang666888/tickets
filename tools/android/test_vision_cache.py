import io
import json
import subprocess
import unittest
from unittest.mock import Mock, patch

from PIL import Image

import ocr
from vision_cache import VisionCache


def observation(text="看台380元", bounds=None):
    return {"text": text, "confidence": 1, "bounds": bounds or [2, 3, 18, 13]}


class VisionCacheTests(unittest.TestCase):
    def setUp(self):
        self.image = Image.new("RGB", (100, 80), "white")
        self.recognizer = Mock(return_value=[observation()])
        self.cache = VisionCache(self.recognizer)

    def test_identical_pixels_hit_without_repeating_ocr(self):
        first, meta = self.cache.read(self.image, [[10, 20, 50, 60]])
        second, cached = self.cache.read(self.image.copy(), [[10, 20, 50, 60]])
        self.assertEqual(first, second)
        self.assertFalse(meta["cacheHit"])
        self.assertGreaterEqual(meta["ocrMs"], 0)
        self.assertEqual(cached, {"cacheHit": True, "ocrMs": 0})
        self.recognizer.assert_called_once()

    def test_pixel_change_inside_roi_invalidates(self):
        regions = [[10, 20, 50, 60]]
        self.cache.read(self.image, regions)
        self.image.putpixel((11, 21), (12, 34, 56))
        _, meta = self.cache.read(self.image, regions)
        self.assertFalse(meta["cacheHit"])
        self.assertEqual(self.recognizer.call_count, 2)

    def test_change_outside_roi_keeps_hit(self):
        regions = [[10, 20, 50, 60]]
        self.cache.read(self.image, regions)
        self.image.putpixel((90, 70), (0, 0, 0))
        _, meta = self.cache.read(self.image, regions)
        self.assertTrue(meta["cacheHit"])

    def test_bounds_reordering_and_dimensions_invalidate(self):
        cases = [
            (self.image, [[0, 0, 20, 20], [40, 0, 60, 20]]),
            (self.image, [[40, 0, 60, 20], [0, 0, 20, 20]]),
            (self.image, [[41, 0, 61, 20], [0, 0, 20, 20]]),
            (Image.new("RGB", (101, 80), "white"), [[41, 0, 61, 20], [0, 0, 20, 20]]),
        ]
        for image, regions in cases:
            _, meta = self.cache.read(image, regions)
            self.assertFalse(meta["cacheHit"])
        self.assertEqual(self.recognizer.call_count, len(cases))

    def test_union_is_encoded_once_as_jpeg_and_offset_restored(self):
        regions = [[10, 20, 30, 40], [50, 40, 70, 60]]
        self.recognizer.return_value = [observation(bounds=[2, 3, 18, 13]), observation("内场880元", [42, 23, 58, 33])]
        results, _ = self.cache.read(self.image, regions)
        self.recognizer.assert_called_once()
        encoded = self.recognizer.call_args.args[0]
        with Image.open(io.BytesIO(encoded)) as crop:
            self.assertEqual(crop.format, "JPEG")
            self.assertEqual(crop.size, (60, 40))
        self.assertEqual([r["bounds"] for r in results], [[12, 23, 28, 33], [52, 43, 68, 53]])

    def test_union_gap_text_is_excluded_from_cached_results(self):
        self.recognizer.return_value = [observation(), observation("gap", [25, 3, 35, 13])]
        results, _ = self.cache.read(self.image, [[0, 0, 20, 20], [40, 0, 60, 20]])
        self.assertEqual([r["text"] for r in results], ["看台380元"])

    def test_empty_regions_use_entire_image(self):
        self.cache.read(self.image, [])
        with Image.open(io.BytesIO(self.recognizer.call_args.args[0])) as crop:
            self.assertEqual(crop.size, self.image.size)
        self.image.putpixel((99, 79), (1, 2, 3))
        _, meta = self.cache.read(self.image, [])
        self.assertFalse(meta["cacheHit"])

    def test_explicit_full_region_is_a_different_region_configuration(self):
        self.cache.read(self.image, [])
        _, meta = self.cache.read(self.image, [[0, 0, 100, 80]])
        self.assertFalse(meta["cacheHit"])

    def test_same_rgb_pixels_in_other_image_mode_reuse_result(self):
        self.cache.read(self.image, [])
        _, meta = self.cache.read(self.image.convert("RGBA"), [])
        self.assertTrue(meta["cacheHit"])

    def test_force_bypasses_hit_and_replaces_cached_result(self):
        self.cache.read(self.image, [])
        self.recognizer.return_value = [observation("看台480元")]
        fresh, meta = self.cache.read(self.image, [], force=True)
        self.assertFalse(meta["cacheHit"])
        cached, meta = self.cache.read(self.image, [])
        self.assertTrue(meta["cacheHit"])
        self.assertEqual(fresh, cached)
        self.assertEqual(cached[0]["text"], "看台480元")
        self.assertEqual(self.recognizer.call_count, 2)

    def test_returned_objects_cannot_modify_cache_or_ocr_result(self):
        first, _ = self.cache.read(self.image, [[10, 20, 50, 60]])
        first[0]["bounds"][0] = 999
        first[0]["text"] = "changed"
        second, _ = self.cache.read(self.image, [[10, 20, 50, 60]])
        self.assertEqual(second[0], observation(bounds=[12, 23, 28, 33]))
        self.assertEqual(self.recognizer.return_value[0], observation())

    def test_lru_keeps_at_most_eight_images(self):
        images = [Image.new("RGB", (100, 80), (i, 0, 0)) for i in range(9)]
        for image in images[:8]:
            self.cache.read(image, [])
        self.assertTrue(self.cache.read(images[0], [])[1]["cacheHit"])
        self.cache.read(images[8], [])
        self.assertTrue(self.cache.read(images[0], [])[1]["cacheHit"])
        self.assertFalse(self.cache.read(images[1], [])[1]["cacheHit"])

    def test_invalid_bounds_never_invoke_ocr(self):
        for regions in (None, [[1, 2, 3]], [[-1, 0, 20, 20]], [[0, 0, 101, 20]], [[0, 0, 20, 81]], [[0, 0, 0, 20]], [[10, 0, 5, 20]], [[0.0, 0, 20, 20]], [[False, 0, 20, 20]]):
            with self.subTest(regions=regions), self.assertRaises(ValueError):
                self.cache.read(self.image, regions)
        with self.assertRaises(ValueError):
            self.cache.read(Image.new("RGB", (0, 20)), [])
        self.recognizer.assert_not_called()

    def test_recognition_failure_is_not_cached(self):
        self.recognizer.side_effect = RuntimeError("OCR timeout")
        with self.assertRaisesRegex(RuntimeError, "timeout"):
            self.cache.read(self.image, [])
        self.recognizer.side_effect = None
        _, meta = self.cache.read(self.image, [])
        self.assertFalse(meta["cacheHit"])


class OCRFormatTests(unittest.TestCase):
    @patch.object(ocr, "_binary", return_value="/tmp/fake-ocr")
    @patch.object(ocr.subprocess, "run")
    def test_jpeg_and_png_are_passed_as_bytes(self, run, _binary):
        run.return_value = subprocess.CompletedProcess([], 0, stdout=json.dumps([observation()]).encode(), stderr=b"")
        for format in ("PNG", "JPEG"):
            payload = io.BytesIO()
            Image.new("RGB", (30, 20), "white").save(payload, format=format)
            self.assertEqual(ocr.recognize(payload.getvalue()), [observation()])
            self.assertEqual(run.call_args.kwargs["input"], payload.getvalue())

    def test_other_formats_and_over_limit_are_rejected(self):
        with self.assertRaises(ValueError):
            ocr.recognize(b"GIF89a")
        with self.assertRaisesRegex(ValueError, "40 MiB"):
            ocr.recognize(b"\xff\xd8\xff" + bytes(40 * 1024 * 1024))

    @patch.object(ocr, "_binary")
    def test_warmup_prepares_binary_without_recognizing(self, binary):
        with patch.object(ocr.subprocess, "run") as run:
            ocr.warmup()
        binary.assert_called_once_with()
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
