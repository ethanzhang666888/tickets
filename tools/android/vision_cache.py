"""In-memory OCR cache keyed by the pixels of current native UI regions."""

from collections import OrderedDict
from copy import deepcopy
import hashlib
import io
import time

from PIL import Image

from ocr import recognize


class VisionCache:
    def __init__(self, recognizer=None):
        self._recognize = recognizer or recognize
        self._entries = OrderedDict()

    def read(self, image: Image.Image, regions: list, force=False) -> tuple:
        if not isinstance(image, Image.Image) or image.width <= 0 or image.height <= 0:
            raise ValueError("OCR 缓存需要有效且非空的 PIL 图片。")
        if not isinstance(regions, list) or type(force) is not bool:
            raise ValueError("OCR 区域必须为列表，force 必须为布尔值。")
        boxes = []
        for region in regions:
            if (not isinstance(region, (list, tuple)) or len(region) != 4
                    or any(type(value) is not int for value in region)):
                raise ValueError("OCR 区域必须包含四个整数像素坐标。")
            left, top, right, bottom = region
            if not (0 <= left < right <= image.width and 0 <= top < bottom <= image.height):
                raise ValueError("OCR 区域超出图片范围或面积无效。")
            boxes.append(tuple(region))

        requested = tuple(boxes)
        boxes = boxes or [(0, 0, image.width, image.height)]
        rgb = image if image.mode == "RGB" else image.convert("RGB")
        digests = tuple(hashlib.sha256(rgb.crop(box).tobytes()).digest() for box in boxes)
        key = (image.size, requested, digests)
        if not force and key in self._entries:
            self._entries.move_to_end(key)
            return deepcopy(self._entries[key]), {"cacheHit": True, "ocrMs": 0}

        union = (min(box[0] for box in boxes), min(box[1] for box in boxes),
                 max(box[2] for box in boxes), max(box[3] for box in boxes))
        payload = io.BytesIO()
        rgb.crop(union).save(payload, format="JPEG", quality=95, subsampling=0)
        start = time.perf_counter()
        observed = self._recognize(payload.getvalue())
        elapsed = round((time.perf_counter() - start) * 1000, 1)
        recognized = []
        for result in observed:
            left, top, right, bottom = result["bounds"]
            shifted = [left + union[0], top + union[1], right + union[0], bottom + union[1]]
            x, y = (shifted[0] + shifted[2]) / 2, (shifted[1] + shifted[3]) / 2
            # The gap between regions is not hashed, so its text must not enter the cache.
            if any(box[0] <= x < box[2] and box[1] <= y < box[3] for box in boxes):
                recognized.append({**deepcopy(result), "bounds": shifted})
        self._entries[key] = recognized
        self._entries.move_to_end(key)
        while len(self._entries) > 8:
            self._entries.popitem(last=False)
        return deepcopy(recognized), {"cacheHit": False, "ocrMs": elapsed}
