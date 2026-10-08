"""Local macOS Vision OCR; image bytes are passed through stdin only."""

import hashlib
import json
import math
import os
from pathlib import Path
import platform
import select
import shutil
import struct
import subprocess
import tempfile
import threading
import time


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
JPEG_SIGNATURE = b"\xff\xd8\xff"


def _binary():
    if platform.system() != "Darwin":
        raise RuntimeError("本地 OCR 目前仅支持 macOS。")
    source = Path(__file__).with_suffix(".swift")
    try:
        source_bytes = source.read_bytes()
    except OSError as error:
        raise RuntimeError("缺少本地 OCR 的 ocr.swift 资源。") from error
    fingerprint = hashlib.sha256(
        source_bytes + platform.machine().encode() + platform.mac_ver()[0].encode()
    ).hexdigest()[:20]
    cache = Path.home() / "Library/Caches/tickets-android-ocr" / fingerprint
    binary = cache / "recognize"
    if binary.is_file() and os.access(binary, os.X_OK):
        return binary
    compiler = shutil.which("swiftc")
    if not compiler and Path("/usr/bin/swiftc").is_file():
        compiler = "/usr/bin/swiftc"
    if not compiler:
        raise RuntimeError("首次启用本地 OCR 需要 Xcode Command Line Tools（swiftc）。")
    temporary = None
    try:
        cache.mkdir(parents=True, mode=0o700, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix="compile-", dir=cache)
        os.close(descriptor)
        compiled = subprocess.run(
            [compiler, "-O", str(source), "-o", temporary],
            capture_output=True, text=True, timeout=60,
        )
        if compiled.returncode:
            raise RuntimeError(f"本地 OCR 编译失败：{compiled.stderr.strip()[:1500]}")
        os.chmod(temporary, 0o700)
        os.replace(temporary, binary)
        return binary
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("本地 OCR 首次编译超过 60 秒，请检查 Xcode Command Line Tools。") from error
    except OSError as error:
        raise RuntimeError(f"无法准备本地 OCR：{error.strerror}") from error
    finally:
        if temporary:
            Path(temporary).unlink(missing_ok=True)


def warmup() -> None:
    """Prepare the local executable without reading an image."""
    _binary()


def recognize(image: bytes) -> list:
    """Return text/confidence/bounds in image pixels, with a top-left origin."""
    _validate_image(image)
    binary = _binary()
    try:
        completed = subprocess.run(
            [str(binary)], input=image, capture_output=True, timeout=20,
        )
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("本地 OCR 识别超过 20 秒。") from error
    except OSError as error:
        raise RuntimeError(f"无法启动本地 OCR：{error.strerror}") from error
    if completed.returncode:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()[:1500]
        raise RuntimeError(detail or "本地 OCR 进程异常退出。")
    return _decode_results(completed.stdout)


def _validate_image(image):
    if not isinstance(image, bytes) or not image.startswith((PNG_SIGNATURE, JPEG_SIGNATURE)):
        raise ValueError("本地 OCR 需要 PNG 或 JPEG 格式的 bytes。")
    if len(image) > 40 * 1024 * 1024:
        raise ValueError("本地 OCR 的图片大小不能超过 40 MiB。")


def _decode_results(payload):
    try:
        results = json.loads(payload)
        if not isinstance(results, list):
            raise ValueError("expected list")
        for result in results:
            if not isinstance(result, dict) or not isinstance(result.get("text"), str):
                raise ValueError("invalid text")
            confidence = result.get("confidence")
            if (type(confidence) not in (float, int) or not math.isfinite(confidence)
                    or not 0 <= confidence <= 1):
                raise ValueError("invalid confidence")
            bounds = result.get("bounds")
            if (not isinstance(bounds, list) or len(bounds) != 4
                    or any(type(value) is not int or value < 0 for value in bounds)
                    or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]):
                raise ValueError("invalid bounds")
        return results
    except (ValueError, TypeError, UnicodeDecodeError) as error:
        raise RuntimeError("本地 OCR 返回了无法解析的识别结果。") from error


class OCRWorker:
    """One local Vision process; bounded framed IO, no files containing screenshots."""

    def __init__(self, timeout=20):
        self.timeout = timeout
        self._process = None
        self._lock = threading.RLock()

    def _start(self):
        if self._process is not None and self._process.poll() is None:
            return
        self.close()
        self._process = subprocess.Popen([str(_binary()), '--server'], stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        os.set_blocking(self._process.stdin.fileno(), False)
        os.set_blocking(self._process.stdout.fileno(), False)

    def _transfer(self, stream, size, deadline, payload=None):
        fd = stream.fileno()
        result = bytearray()
        offset = 0
        while offset < size:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError('本地 OCR 响应超时，已停止识别进程，请重新读取。')
            readable, writable, _ = select.select([fd] if payload is None else [],
                                                   [fd] if payload is not None else [], [], remaining)
            if not readable and not writable:
                continue
            try:
                if payload is None:
                    chunk = os.read(fd, min(size - offset, 65536))
                    if not chunk:
                        raise RuntimeError('本地 OCR 进程已退出，请重新读取。')
                    result.extend(chunk)
                    offset += len(chunk)
                else:
                    offset += os.write(fd, payload[offset:offset + 65536])
            except BlockingIOError:
                continue
        return bytes(result)

    def _request(self, image):
        self._start()
        deadline = time.monotonic() + self.timeout
        payload = struct.pack('!I', len(image)) + image
        self._transfer(self._process.stdin, len(payload), deadline, payload)
        header = self._transfer(self._process.stdout, 4, deadline)
        length = struct.unpack('!I', header)[0]
        if not 0 < length <= 4 * 1024 * 1024:
            raise RuntimeError('本地 OCR 返回的响应长度异常。')
        return self._transfer(self._process.stdout, length, deadline)

    def warmup(self):
        with self._lock:
            try:
                if json.loads(self._request(b'')) != {'ready': True}:
                    raise RuntimeError('本地 OCR 预热响应异常。')
            except Exception as error:
                self.close()
                raise RuntimeError('无法准备本地 OCR 进程。') from error

    def recognize(self, image):
        _validate_image(image)
        with self._lock:
            try:
                return _decode_results(self._request(image))
            except Exception as error:
                self.close()
                if isinstance(error, RuntimeError):
                    raise
                raise RuntimeError('本地 OCR 进程通信失败，请重新读取。') from error

    def close(self):
        with self._lock:
            process, self._process = self._process, None
            if process is not None:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=1)
                for stream in (process.stdin, process.stdout):
                    if stream:
                        stream.close()
