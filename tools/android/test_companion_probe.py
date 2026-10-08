import json
import unittest
from unittest.mock import patch
from companion_probe import encode_request, decode_response, read_reply, inspect_complete, read_pairing_code, pair, sample_inspections, confirm_inspection_ready, ProbeError, main


def envelope(**fields):
    return json.dumps(dict(protocolVersion=1, executionSupported=False, stage='feasibility', ok=True, **fields))


class Socket:
    def __init__(self, chunks): self.chunks = iter(chunks)
    def recv(self, _): return next(self.chunks, b'')
    def settimeout(self, _): pass


class CompanionProbeTests(unittest.TestCase):
    def test_request_never_allows_purchase_or_control_fields(self):
        token = 'a' * 48
        self.assertIn(b'"action":"inspect"', encode_request('inspect', token))
        for action in ('start', 'prepare', 'buy', 'submit', 'test_click'):
            with self.assertRaises(ValueError): encode_request(action, token)
        with self.assertRaises(ValueError): encode_request('inspect', 'short')

    def test_reply_framing_is_bounded_and_requires_newline(self):
        self.assertEqual(read_reply(Socket([b'{', b'}\n'])), '{}')
        for chunks in ([b'{}'], [b'x' * 65537], [b'{}\n{}\n']):
            with self.assertRaises(ValueError): read_reply(Socket(chunks))

    def test_reply_has_a_total_deadline_not_only_per_read_timeout(self):
        with patch('companion_probe.time.monotonic', side_effect=[0, 21]):
            with self.assertRaises(ValueError): read_reply(Socket([b'{}\n']))

    def test_actual_nested_summary_preserves_metrics_not_private_text(self):
        safe = decode_response(envelope(accessibilityConnected=True, captureState='ready', bridgeState='listening', error='none', testClickCount=1,
            summary=dict(pageKind='synthetic', nodeCount=18, nativeMs=12, screenshotMs=25, ocrMs=80, ocrBlockCount=3,
                         screenshotWidth=1080, screenshotHeight=2340, truncated=False, captureState='complete', title='private', token='secret'), buyers=['secret']))
        self.assertEqual(safe['summary']['nodeCount'], 18)
        self.assertEqual(safe['summary']['nativeMs'], 12)
        self.assertEqual(safe['summary']['ocrMs'], 80)
        self.assertEqual(safe['testClickCount'], 1)
        self.assertNotIn('private', str(safe))
        self.assertNotIn('secret', str(safe))

    def test_capture_diagnostics_preserve_only_fixed_phases_and_counters(self):
        diagnostics = {'phase': 'frame_rejected_timestamp', 'receivedFrames': 30, 'rejectedFrames': 30,
                       'maxFrameClockBehindMs': 12345, 'ocrStarted': False, 'timestamp': 9999999, 'text': 'private', 'token': 'secret'}
        safe = decode_response(envelope(captureDiagnostics=diagnostics))['captureDiagnostics']
        self.assertEqual(safe, {key: diagnostics[key] for key in ('phase', 'receivedFrames', 'rejectedFrames', 'maxFrameClockBehindMs', 'ocrStarted')})
        with self.assertRaises(ValueError): decode_response(envelope(captureDiagnostics={'phase': 'private'}))
        with self.assertRaises(ValueError): decode_response(envelope(captureDiagnostics={'receivedFrames': -1}))

    def test_incomplete_guard_scan_preserves_only_safe_summary(self):
        result = decode_response(envelope(lastIncompleteScan={
            'nodeCount': 86, 'nativeMs': 102, 'truncated': True,
            'pageKind': 'unknown', 'title': 'private', 'token': 'secret'}))
        self.assertEqual(result['lastIncompleteScan'], {
            'nodeCount': 86, 'nativeMs': 102, 'truncated': True, 'pageKind': 'unknown'})
        self.assertNotIn('private', str(result))
        self.assertNotIn('secret', str(result))
        with self.assertRaises(ProbeError):
            decode_response(envelope(lastIncompleteScan=[]))

    def test_capabilities_are_preserved(self):
        safe = decode_response(envelope(actions=['capabilities', 'inspect', 'status', 'stop'], nativeInspection=True,
            bundledChineseOcr=True, captureConsentRequired=True, testClickLocalOnly=True, maxRequestBytes=8192))
        self.assertTrue(safe['bundledChineseOcr'])
        self.assertEqual(safe['maxRequestBytes'], 8192)
        with self.assertRaises(ValueError): decode_response(envelope(actions=['submit']))

    def test_unauthorized_and_unknown_failures_are_not_reported_as_success(self):
        raw = dict(protocolVersion=1, executionSupported=False, stage='feasibility', ok=False, error='invalid_or_unauthorized_request')
        safe = decode_response(json.dumps(raw))
        self.assertFalse(safe['ok'])
        self.assertEqual(safe['error'], 'invalid_or_unauthorized_request')
        raw['error'] = 'private user data'
        self.assertEqual(decode_response(json.dumps(raw))['error'], 'unrecognized_error')

    def test_inspect_waits_for_async_ocr_without_repeating_inspect(self):
        replies = [{'ok': True, 'captureState': 'awaiting_frame'}, {'ok': True, 'captureState': 'ocr_running'},
                   {'ok': True, 'captureState': 'ready', 'summary': {'captureState': 'complete', 'ocrMs': 25}}]
        with patch('companion_probe.request', side_effect=replies) as send, patch('companion_probe.time.sleep'):
            result = inspect_complete('serial', 'a' * 48)
        self.assertEqual(result['summary']['ocrMs'], 25)
        self.assertEqual([call.args[1] for call in send.call_args_list], ['inspect', 'status', 'status'])

    def test_busy_capture_cannot_be_counted_as_a_new_sample(self):
        with patch('companion_probe.request', return_value={'ok': True, 'captureState': 'ocr_running', 'error': 'capture_busy'}) as send:
            with self.assertRaises(ProbeError): inspect_complete('serial', 'a' * 48)
            self.assertEqual(send.call_count, 1)

    def test_async_wait_timeout_and_failure_do_not_resend_inspection(self):
        with patch('companion_probe.request', return_value={'ok': True, 'captureState': 'awaiting_frame'}) as send:
            with self.assertRaises(ValueError): inspect_complete('serial', 'a' * 48, timeout=0)
            self.assertEqual(send.call_count, 1)
        with patch('companion_probe.request', return_value={'ok': False, 'error': 'busy'}) as send:
            self.assertFalse(inspect_complete('serial', 'a' * 48)['ok'])
            self.assertEqual(send.call_count, 1)

    def test_guided_inspection_requires_explicit_ready_after_pairing(self):
        from subprocess import CompletedProcess
        with patch('companion_probe.subprocess.run', return_value=CompletedProcess([], 0, 'ready\n', '')):
            confirm_inspection_ready()
        with patch('companion_probe.subprocess.run', return_value=CompletedProcess([], 0, 'cancelled\n', '')):
            with self.assertRaises(ProbeError): confirm_inspection_ready()

    def test_cancelling_guidance_never_inspects_and_never_prints_secret(self):
        import contextlib
        import io
        output = io.StringIO()
        with patch('sys.argv', ['probe', '--serial', 'serial', '--dialog', '--action', 'inspect']), \
                patch('companion_probe.read_pairing_code', return_value='12345678'), \
                patch('companion_probe.pair', return_value='a' * 48), \
                patch('companion_probe.confirm_inspection_ready', side_effect=ProbeError('读页验证已取消，未发送检查请求。')), \
                patch('companion_probe.inspect_complete') as inspect, contextlib.redirect_stdout(output):
            self.assertEqual(main(), 1)
            inspect.assert_not_called()
        self.assertNotIn('a' * 48, output.getvalue())
        self.assertNotIn('12345678', output.getvalue())

    def test_samples_keep_cold_separate_and_report_nearest_rank_p95(self):
        complete = {'ok': True, 'captureState': 'ready', 'summary': {'pageKind': 'synthetic', 'truncated': False,
            'captureState': 'complete', 'nativeMs': 2, 'screenshotMs': 3, 'ocrMs': 4}}
        with patch('companion_probe.inspect_complete', return_value=complete) as inspect, patch('companion_probe.time.sleep'):
            result = sample_inspections('serial', 'a' * 48, 30)
        self.assertEqual(inspect.call_count, 31)
        self.assertEqual(len(result['warmSamples']), 30)
        self.assertEqual(result['warmSummary']['ocrMs'], {'median': 4.0, 'p95': 4})
        self.assertEqual(result['pageIdentityVerified'], False)
        self.assertIn('firstSample', result)

    def test_samples_abort_on_page_change_or_missing_capture(self):
        complete = {'ok': True, 'captureState': 'ready', 'summary': {'pageKind': 'synthetic', 'truncated': False,
            'captureState': 'complete', 'nativeMs': 2, 'screenshotMs': 3, 'ocrMs': 4}}
        changed = {**complete, 'summary': {**complete['summary'], 'pageKind': 'detail'}}
        for invalid in ({'ok': False}, {'ok': True, 'captureState': 'not_granted'}, changed,
                        {**complete, 'summary': {**complete['summary'], 'truncated': True}}):
            with patch('companion_probe.inspect_complete', side_effect=[complete, invalid]), patch('companion_probe.time.sleep'):
                with self.assertRaises(ValueError): sample_inspections('serial', 'a' * 48, 1)
        for count in (0, 101, True):
            with self.assertRaises(ValueError): sample_inspections('serial', 'a' * 48, count)

    def test_native_audit_is_read_only_and_keeps_existing_result_sanitization(self):
        payload = json.loads(encode_request('native_audit', 'a' * 48))
        self.assertEqual(set(payload), {'protocolVersion', 'token', 'action'})
        result = decode_response(envelope(captureState='native_audit_only',
            summary={'pageKind': 'payment', 'nodeCount': 90, 'truncated': False,
                     'captureState': 'native_audit_only', 'name': 'private'}))
        self.assertEqual(result['captureState'], 'native_audit_only')
        self.assertNotIn('private', str(result))

    def test_native_audit_exports_only_fixed_counts_and_no_identity_claim(self):
        result = decode_response(envelope(nativeAudit={
            'sessionContainerCount': 1, 'maskedIdentifierNodeCount': 2,
            'identityTriplesVerified': False, 'executionReady': False,
            'buyerName': 'private', 'document': '110101199001011234'}))
        self.assertEqual(result['nativeAudit'], {
            'sessionContainerCount': 1, 'maskedIdentifierNodeCount': 2,
            'identityTriplesVerified': False, 'executionReady': False})
        self.assertNotIn('private', str(result))
        self.assertNotIn('110101199001011234', str(result))

    def test_layout_diagnostics_keep_counts_without_window_or_node_text(self):
        counts = {key: 1 for key in ('resourceIdNodeCount', 'webViewNodeCount',
            'sessionLabelNodeCount', 'tierLabelNodeCount', 'appWindowCount',
            'activeAppWindowCount', 'focusedAppWindowCount', 'inspectedWindowMatchCount')}
        result = decode_response(envelope(nativeAudit={**counts, 'windowTitle': 'private',
            'resourceId': 'cn.damai:id/private_dynamic', 'nodeText': 'private'}))
        self.assertEqual(result['nativeAudit'], counts)
        self.assertNotIn('private', str(result))

    def test_native_structure_keeps_only_bounded_static_metadata(self):
        row = dict(parent=-1, resourceId='cn.damai:id/sku_item', kind='text',
            visible=True, enabled=True, clickable=False, selected=True, checkable=False, checked=False)
        result = decode_response(envelope(nativeStructure=dict(complete=True,
            nodes=[dict(row, text='private', document='110101199001011234')])))
        self.assertEqual(result['nativeStructure'], dict(complete=True, nodes=[row]))
        self.assertNotIn('private', str(result))
        for invalid in (dict(row, parent=0), dict(row, resourceId='private'),
                        dict(row, selected=1), dict(row, kind='private')):
            with self.assertRaises(ProbeError):
                decode_response(envelope(nativeStructure=dict(complete=True, nodes=[invalid])))
        for invalid in (dict(complete=True, nodes=[row] * 81), dict(complete=False, nodes=[row])):
            with self.assertRaises(ProbeError):
                decode_response(envelope(nativeStructure=invalid))

    def test_picker_layout_flag_is_boolean_and_does_not_enable_execution(self):
        result = decode_response(envelope(summary={'pickerLayoutRecognized': True}))
        self.assertTrue(result['summary']['pickerLayoutRecognized'])
        self.assertFalse(result['executionSupported'])
        with self.assertRaises(ProbeError):
            decode_response(envelope(summary={'pickerLayoutRecognized': 1}))

    def test_cancelling_native_audit_guidance_sends_no_read_or_capture(self):
        import contextlib
        import io
        output = io.StringIO()
        with patch('sys.argv', ['probe', '--serial', 'serial', '--dialog', '--action', 'native_audit']), \
                patch('companion_probe.read_pairing_code', return_value='12345678'), \
                patch('companion_probe.pair', return_value='a' * 48), \
                patch('companion_probe.confirm_native_audit_ready', side_effect=ProbeError('已取消。')), \
                patch('companion_probe.request') as send, \
                patch('companion_probe.inspect_complete') as inspect, contextlib.redirect_stdout(output):
            self.assertEqual(main(), 1)
            send.assert_not_called()
            inspect.assert_not_called()
        self.assertNotIn('a' * 48, output.getvalue())
        self.assertNotIn('12345678', output.getvalue())

    def test_short_code_exchange_keeps_long_secret_out_of_output(self):
        secret = 'a' * 48
        with patch('companion_probe._exchange', return_value=envelope(token=secret)) as exchange:
            self.assertEqual(pair('serial', '1234 5678'), secret)
            sent = json.loads(exchange.call_args.args[1])
            self.assertEqual(sent, {'protocolVersion': 1, 'action': 'pair', 'pairingCode': '12345678'})
            self.assertNotIn(secret, str(exchange.call_args))
        self.assertNotIn(secret, str(decode_response(envelope(token=secret))))

    def test_pairing_rejects_invalid_code_and_untrusted_response(self):
        for code in ('a' * 48, '1234567', '１２３４５６７８', '123456789', '1234\n5678'):
            with patch('companion_probe._exchange') as exchange:
                with self.assertRaises(ValueError): pair('serial', code)
                exchange.assert_not_called()
        for response in (envelope(), envelope(token='short'), envelope(token='A' * 48),
                         envelope(token='a' * 48, private='secret'),
                         '{"protocolVersion":1,"stage":"feasibility","executionSupported":false,"ok":false,"error":"pairing_failed"}'):
            with patch('companion_probe._exchange', return_value=response):
                with self.assertRaises(ValueError): pair('serial', '12345678')

    def test_duplicate_protocol_fields_are_rejected(self):
        with self.assertRaises(ValueError):
            decode_response('{"protocolVersion":2,"protocolVersion":1,"stage":"feasibility","executionSupported":false,"ok":true}')

    def test_dialog_secret_is_only_returned_in_memory(self):
        from subprocess import CompletedProcess
        secret = 'a' * 48
        with patch('companion_probe.sys.platform', 'darwin'), patch('companion_probe.subprocess.run', return_value=CompletedProcess([], 0, secret + '\n', '')) as run:
            self.assertEqual(read_pairing_code(True), secret)
            self.assertNotIn(secret, str(run.call_args))
        with patch('companion_probe.sys.platform', 'darwin'), patch('companion_probe.subprocess.run', return_value=CompletedProcess([], 1, '', 'cancelled')):
            with self.assertRaises(ValueError): read_pairing_code(True)

    def test_untrusted_or_executable_response_is_rejected(self):
        for raw in ('[]', '{}', '{"executionSupported":true,"stage":"feasibility"}', '{"executionSupported":false,"stage":"production"}',
                    envelope(summary={'nodeCount': 'private'}), envelope(summary={'nativeMs': float('nan')}),
                    envelope(summary=[]), envelope(summary={'truncated': 1}),
                    envelope(summary={'captureState': 'private'})):
            with self.assertRaises(ValueError): decode_response(raw)
        for field, value in [('protocolVersion', True), ('protocolVersion', 2), ('ok', 'true')]:
            data = json.loads(envelope())
            data[field] = value
            with self.assertRaises(ValueError): decode_response(json.dumps(data))


if __name__ == '__main__': unittest.main()
