"""Offline USB adapter tests. Every device operation is replaced by an in-memory session."""
import copy
import hashlib
import hmac
import json
import socket
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from usb_protocol import VERSION, MAGIC, PACKAGE, UsbError, encode_request, encode_control, send_frame, receive_frame
from usb_transport import verify_hello, validate_report, artifact
from usb_driver import UsbDriver, Cancelled


CONFIG = {'eventTitle': '测试演出', 'session': '2026-11-07 19:00', 'tier': '内场880元', 'quantity': 1,
          'buyerLabels': [], 'autoSubmit': False, 'startAt': None, 'timeoutSeconds': 120}


def page(selected=False):
    return {'kind': 'picker', 'title': '测试演出', 'sessions': [{'label': CONFIG['session'], 'selected': True,
            'selectionVerified': True, 'enabled': True, 'availability': 'available'}],
            'tiers': [{'label': '内场880元', 'unitPriceCents': 88000, 'selected': selected, 'selectionVerified': True,
            'enabled': True, 'availability': 'available'}], 'blockers': [], 'canOpenPicker': False,
            'purchaseAvailability': 'unknown', 'buyers': [], 'buyersComplete': False, 'orderVerified': False,
            'identityStaysOnPhone': True, **({'quantity': 1} if selected else {})}


def report(phase='inspected', reason='page_inspected', selected=False):
    return {'phase': phase, 'reason': reason, 'actions': 0, 'metrics': {'readMs': 50, 'visualMs': 40, 'nativeReadRetries': 0},
            'lastNativeReadFailure': 'none', 'automaticSubmissionSupported': False, 'attendeeIdentityExported': False,
            'page': page(selected)}


def confirmation():
    result = report('confirmed', 'confirmation_verified_manual_submit')
    result['page'] = {'kind': 'confirm', 'title': '', 'sessions': [], 'tiers': [], 'blockers': [], 'canOpenPicker': False,
        'purchaseAvailability': 'unknown', 'buyers': [], 'buyersComplete': False, 'orderVerified': True,
        'identityStaysOnPhone': True, 'quantity': 1, 'totalCents': 88000, 'selectedBuyerCount': 1, 'defaultFirstSelected': True}
    return result


class UsbWireTests(unittest.TestCase):
    def test_request_contains_only_bounded_fixed_schema_and_rejects_submit(self):
        data = encode_request('a' * 64, 'prepare', config=CONFIG, unit_price=88000)
        self.assertEqual(struct.unpack('!II', data[:8]), (MAGIC, VERSION))
        self.assertIn('内场880元'.encode(), data)
        for operation in ('submit', 'tap', 'shell'):
            with self.assertRaises(UsbError): encode_request('a' * 64, operation)
        for change in ({'autoSubmit': True}, {'quantity': True}, {'timeoutSeconds': 601}, {'buyerLabels': ['\ud800']}, {'buyerLabels': ['甲\n乙']}):
            with self.assertRaises(UsbError): encode_request('a' * 64, 'start', config={**CONFIG, **change}, unit_price=88000)
        with self.assertRaises(UsbError): encode_request('a' * 64, 'start', config=CONFIG, unit_price=True)
        with self.assertRaises(UsbError): encode_control(0)
        self.assertEqual(struct.unpack('!Bq', encode_control(2, True)), (2, 2))

    def test_server_proves_knowledge_of_capability_before_private_config_is_sent(self):
        run, capability, pid, sdk = '1' * 32, 'a' * 64, 1234, 29
        proof = hmac.new(capability.encode(), f'hello|1|{run}|{PACKAGE}|{pid}|{sdk}'.encode(), hashlib.sha256).hexdigest()
        frame = {'type': 'hello', 'data': {'protocol': 1, 'package': PACKAGE, 'pid': pid, 'sdk': sdk, 'proof': proof}}
        self.assertEqual(verify_hello(frame, run, capability), pid)
        for key, value in (('proof', '0' * 64), ('pid', 9999), ('package', 'other'), ('protocol', True)):
            bad = copy.deepcopy(frame); bad['data'][key] = value
            with self.assertRaises(UsbError): verify_hello(bad, run, capability)

    def test_framed_replies_reject_duplicate_keys_wrong_sessions_and_oversize(self):
        for body in [b'{"protocol":1,"protocol":1}', json.dumps({'protocol': 1, 'runId': 'wrong', 'type': 'complete', 'data': {}}).encode()]:
            left, right = socket.socketpair()
            try:
                send_frame(left, body)
                with self.assertRaises(UsbError): receive_frame(right, 'expected')
            finally: left.close(); right.close()
        left, right = socket.socketpair()
        try:
            left.sendall(struct.pack('!I', 32769))
            with self.assertRaises(UsbError): receive_frame(right, 'expected')
        finally: left.close(); right.close()

    def test_private_fields_and_unproved_success_never_cross_desktop_boundary(self):
        self.assertEqual(validate_report(report())['page']['buyers'], [])
        self.assertTrue(validate_report(confirmation())['page']['orderVerified'])
        variants = []
        for key, value in (('buyers', [{'name': '秘密甲'}]), ('title', '秘密甲'), ('name', '秘密甲')):
            bad = confirmation(); bad['page'][key] = value; variants.append(bad)
        bad = confirmation(); bad['page']['orderVerified'] = False; variants.append(bad)
        bad = confirmation(); bad.pop('page'); variants.append(bad)
        bad = report(); bad['lastNativeReadFailure'] = {'name': '秘密甲'}; variants.append(bad)
        bad = report(); bad['reason'] = 'confirmation_verified_manual_submit'; variants.append(bad)
        for data in variants:
            with self.assertRaises(UsbError): validate_report(data)


class FakeSession:
    def __init__(self, owner, cancel, progress):
        self.owner, self.cancel_event, self.progress = owner, cancel, progress
    def cancel(self):
        self.cancel_event.set()
    def run(self, operation, **arguments):
        self.owner.calls.append((operation, copy.deepcopy(arguments)))
        if self.owner.block:
            self.owner.entered.set()
            self.cancel_event.wait(3)
            if self.cancel_event.is_set(): raise UsbError('stopped_by_user')
        if operation in ('inspect', 'open_picker', 'select_session'): return report()
        if operation == 'prepare':
            result = report('ready', 'selected', True); result['verification'] = 'full'
            if self.owner.invalid_prepare: result['page']['tiers'][0]['selected'] = False
            return result
        if operation == 'start': return confirmation()
        raise AssertionError('Unexpected operation')


class DriverFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.calls = []
        self.block = False
        self.invalid_prepare = False
        self.entered = threading.Event()
        self.driver = UsbDriver(self.temp.name, adb='must-not-run-adb',
            session_factory=lambda adb, serial, cancel, progress: FakeSession(self, cancel, progress))
        self.installer = patch.object(self.driver, '_ensure_installed', lambda serial, token: self.driver._check(serial, token))
        self.installer.start()
    def tearDown(self):
        self.driver.shutdown(); self.installer.stop(); self.temp.cleanup()
    def ready(self):
        self.driver.inspect('device')
        self.driver.prepare('device', CONFIG)
        self.assertTrue(self.driver.done.wait(3))
        state = self.driver.state('device')
        self.assertEqual(state['status'], 'ready')
        return state['prepared']['id']

    def test_status_never_connects_or_requires_an_apk(self):
        state = self.driver.state('device')
        self.assertEqual(state['status'], 'idle')
        self.assertFalse(state['submissionGuard']['blocked'])
        self.assertEqual(self.calls, [])
        self.driver.stop('device')
        self.assertEqual(self.calls, [])

    def test_prepare_start_uses_price_from_catalog_and_stops_before_submission(self):
        prepared = self.ready()
        self.driver.start('device', CONFIG, prepared_id=prepared)
        self.assertTrue(self.driver.done.wait(3))
        state = self.driver.state('device')
        self.assertEqual(state['status'], 'needs_action')
        self.assertEqual(state['phase'], 'confirmed')
        self.assertTrue(state['page']['orderVerified'])
        self.assertEqual(state['page']['buyers'], [])
        self.assertIsNone(state['prepared'])
        self.assertEqual(self.calls[-1][1]['unit_price'], 88000)
        self.assertFalse(self.calls[-1][1]['config']['autoSubmit'])

    def test_unverified_preparation_cannot_issue_a_credential(self):
        self.invalid_prepare = True
        self.driver.inspect('device'); self.driver.prepare('device', CONFIG)
        self.assertTrue(self.driver.done.wait(3))
        state = self.driver.state('device')
        self.assertEqual(state['status'], 'failed')
        self.assertIsNone(state['prepared'])

    def test_auto_submit_bad_names_and_changed_prepared_target_are_rejected_before_run(self):
        prepared = self.ready()
        count = len(self.calls)
        for change in ({'autoSubmit': True}, {'buyerLabels': ['\ud800']}, {'quantity': 2}, {'buyerLabels': ['另一位']}):
            with self.assertRaises((ValueError, UsbError)):
                self.driver.start('device', {**CONFIG, **change}, prepared_id=prepared)
        self.assertEqual(len(self.calls), count)

    def test_stop_cancels_one_session_and_invalidates_queued_actions(self):
        self.driver.inspect('device')
        self.block = True
        self.driver.prepare('device', CONFIG)
        self.assertTrue(self.entered.wait(1))
        queued = self.driver.token('device')
        self.driver.stop('device')
        self.assertTrue(self.driver.done.wait(3))
        self.assertEqual(self.driver.state('device')['status'], 'cancelled')
        with self.assertRaises(Cancelled): self.driver.inspect('device', queued)
        self.assertIsNone(self.driver.state('device')['prepared'])
        self.assertEqual(len([x for x in self.calls if x[0] == 'prepare']), 1)

    def test_shared_submission_fence_blocks_all_phone_operations(self):
        self.driver.submission_guard.begin('device', 'b' * 32)
        with self.assertRaises(RuntimeError): self.driver.inspect('device')
        self.assertEqual(self.calls, [])
        self.assertTrue(self.driver.state('device')['submissionGuard']['blocked'])

    def test_operation_mutex_excludes_another_controller(self):
        with self.driver.submission_guard.operation('device'):
            with self.assertRaises(RuntimeError): self.driver.inspect('device')
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
