import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import ocr


SERVER = r'''
import json, os, struct, sys, time
while True:
    head = sys.stdin.buffer.read(4)
    if not head: break
    length = struct.unpack('!I', head)[0]
    if length > 1024 * 1024: time.sleep(5)
    data = sys.stdin.buffer.read(length)
    if data.endswith(b'hang'): time.sleep(5)
    if data.endswith(b'exit'): sys.exit(1)
    if data.endswith(b'oversize'):
        sys.stdout.buffer.write(struct.pack('!I', 5 * 1024 * 1024)); sys.stdout.buffer.flush(); continue
    result = {'ready': True} if not data else [{'text': str(os.getpid()), 'confidence': 1, 'bounds': [0, 0, 10, 10]}]
    if data.endswith(b'invalid'): result = [{'text': 'bad', 'confidence': 2, 'bounds': [0, 0, 10, 10]}]
    if data.endswith(b'error'): result = {'error': 'invalid image'}
    payload = json.dumps(result).encode()
    for part in (struct.pack('!I', len(payload))[:2], struct.pack('!I', len(payload))[2:], payload[:3], payload[3:]):
        sys.stdout.buffer.write(part); sys.stdout.buffer.flush()
'''


class OCRWorkerTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        binary = Path(self.folder.name) / 'recognize'
        binary.write_text(f'#!{sys.executable}\n' + SERVER)
        binary.chmod(0o700)
        self.binary = patch.object(ocr, '_binary', return_value=binary)
        self.binary.start()
        # Successful protocol tests should not depend on sub-500ms subprocess
        # startup while Android/Rust builds compete for CPU. Timeout-specific
        # tests below retain their short deadline and strict elapsed-time bound.
        self.worker = ocr.OCRWorker(timeout=3)

    def tearDown(self):
        if hasattr(self, 'worker'): self.worker.close()
        self.binary.stop()
        self.folder.cleanup()

    def test_reuses_worker_with_fragmented_responses_and_closes_it(self):
        self.worker.warmup()
        first = self.worker.recognize(ocr.PNG_SIGNATURE)
        self.assertEqual(first, self.worker.recognize(ocr.JPEG_SIGNATURE))
        process = self.worker._process
        self.worker.close()
        self.assertIsNotNone(process.poll())
        self.assertIsNone(self.worker._process)

    def test_timeout_discards_worker_and_next_call_can_recover(self):
        self.worker.warmup()
        self.worker.timeout = .5
        began = time.monotonic()
        with self.assertRaisesRegex(RuntimeError, '超时'):
            self.worker.recognize(ocr.PNG_SIGNATURE + b'hang')
        self.assertLess(time.monotonic() - began, 2)
        self.assertIsNone(self.worker._process)
        self.worker.timeout = 3
        self.assertEqual(len(self.worker.recognize(ocr.PNG_SIGNATURE)), 1)

    def test_corrupt_output_crash_and_oversize_never_leave_live_worker(self):
        for marker in (b'exit', b'oversize', b'invalid', b'error'):
            with self.subTest(marker=marker):
                with self.assertRaises(RuntimeError):
                    self.worker.recognize(ocr.PNG_SIGNATURE + marker)
                self.assertIsNone(self.worker._process)

    def test_invalid_input_does_not_spawn_process(self):
        with self.assertRaises(ValueError): self.worker.recognize(b'bad')
        self.assertIsNone(self.worker._process)

    def test_timeout_also_bounds_writing_a_blocked_pipe(self):
        self.worker.warmup()
        self.worker.timeout = .5
        began = time.monotonic()
        with self.assertRaisesRegex(RuntimeError, '超时'):
            self.worker.recognize(ocr.PNG_SIGNATURE + bytes(2 * 1024 * 1024))
        self.assertLess(time.monotonic() - began, 2)
        self.assertIsNone(self.worker._process)


if __name__ == '__main__':
    unittest.main()
