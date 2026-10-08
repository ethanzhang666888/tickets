"""USB client for the read-only Android feasibility APK; never sends purchase actions.

The pairing secret is entered using getpass (not a command-line argument), stays
in memory, and is never printed. Each invocation owns one dynamic ADB forward.
"""
import argparse
import getpass
import json
import math
import re
import shutil
import socket
import statistics
import subprocess
import sys
import time

class ProbeError(ValueError):
    """Only fixed local explanations and already-sanitized phone summaries."""
    def __init__(self, message, phone=None):
        super().__init__(message)
        self.phone = phone


PORT = 18765
LIMIT = 65536
ACTIONS = ('capabilities', 'inspect', 'native_audit', 'status', 'stop')
NUMERIC_FIELDS = {'nodeCount', 'textNodeCount', 'clickableCount', 'candidateCount', 'nativeMs', 'screenshotMs', 'ocrMs', 'ocrBlockCount', 'ocrCandidateCount', 'screenshotWidth', 'screenshotHeight', 'testClickCount', 'maxRequestBytes', 'receivedFrames', 'rejectedFrames', 'maxFrameClockBehindMs'}
NUMERIC_FIELDS |= {'sessionContainerCount', 'tierContainerCount', 'confirmationTitleCount', 'dialogCount', 'checkboxCount', 'checkedCheckboxCount', 'maskedIdentifierNodeCount', 'documentTypeNodeCount', 'scrollableNodeCount', 'submitButtonCount', 'paymentLabelCount'}
NUMERIC_FIELDS |= {'resourceIdNodeCount', 'webViewNodeCount', 'sessionLabelNodeCount', 'tierLabelNodeCount', 'appWindowCount', 'activeAppWindowCount', 'focusedAppWindowCount', 'inspectedWindowMatchCount'}
BOOL_FIELDS = {'pickerLayoutRecognized', 'identityTriplesVerified', 'executionReady', 'accessibilityConnected', 'nativeInspection', 'bundledChineseOcr', 'captureConsentRequired', 'testClickLocalOnly', 'truncated', 'ocrStarted'}
SAFE_CODES = {
    'none', 'native_audit_only', 'native_audit_not_armed', 'native_audit_unavailable', 'not_granted', 'not_requested', 'ready', 'waiting_foreground', 'awaiting_frame', 'ocr_running', 'complete',
    'consent_denied', 'regrant_requested', 'capture_start_failed', 'damai_launch_failed',
    'screen_locked', 'accessibility_lost', 'foreground_unavailable', 'payment_foreground',
    'node_scan_incomplete', 'node_scan_failed', 'foreground_not_allowed', 'foreground_changed',
    'foreground_or_permission_lost', 'accessibility_interrupted', 'capture_permission_lost',
    'display_changed_regrant_required', 'capture_or_ocr_timeout', 'stopped_by_user',
    'invalid_capture_consent', 'capture_start_failed_or_secure', 'capture_busy',
    'capture_failed_or_secure', 'secure_or_blank_frame', 'ocr_unavailable', 'capture_service_stopped',
    'invalid_or_unauthorized_request', 'busy', 'unsupported', 'inspection_unavailable', 'accessibility_required',
}
ENUM_FIELDS = {
    'pageKind': {'detail', 'selection', 'payment', 'unknown', 'synthetic'},
    'captureState': SAFE_CODES,
    'bridgeState': {'not_started', 'listening', 'unavailable', 'stopped'},
    'phase': {'idle', 'awaiting_frame', 'frame_rejected_timestamp', 'processing_frame', 'ocr_running', 'complete', 'ocr_failed', 'capture_failed'},
}


def encode_request(action, token):
    if action not in ACTIONS or not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', token):
        raise ProbeError('只允许验证接口和有效的手机配对密钥。')
    return (json.dumps({'protocolVersion': 1, 'token': token, 'action': action}, separators=(',', ':')) + '\n').encode()


def read_reply(connection):
    data = bytearray()
    deadline = time.monotonic() + 20
    while b'\n' not in data:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ProbeError('手机响应超时。')
        connection.settimeout(remaining)
        chunk = connection.recv(min(4096, LIMIT + 1 - len(data)))
        if not chunk:
            raise ProbeError('手机连接提前结束，未收到完整响应。')
        data.extend(chunk)
        if len(data) > LIMIT:
            raise ProbeError('手机响应超过大小限制。')
    line, remainder = bytes(data).split(b'\n', 1)
    if remainder.strip():
        raise ProbeError('手机返回了多条响应。')
    try:
        return line.decode('utf-8')
    except UnicodeDecodeError:
        raise ProbeError('手机响应编码无效。') from None


def _unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProbeError('重复响应字段。')
        result[key] = value
    return result


def _decode_envelope(raw):
    try:
        data = json.loads(raw, object_pairs_hook=_unique_fields, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite')))
    except (ValueError, TypeError):
        raise ProbeError('手机响应格式无效。') from None
    if not isinstance(data, dict) or data.get('executionSupported') is not False or data.get('stage') != 'feasibility':
        raise ProbeError('验证接口未确认只读模式；检查配对密钥、APK 版本和手机提示。')
    if type(data.get('protocolVersion')) is not int or data['protocolVersion'] != 1 or type(data.get('ok')) is not bool:
        raise ProbeError('手机响应协议版本或结果标记无效。')
    return data


def decode_response(raw):
    data = _decode_envelope(raw)
    result = {'protocolVersion': 1, 'executionSupported': False, 'stage': 'feasibility', 'ok': data['ok']}
    result.update(_safe_fields(data))
    if 'error' in data:
        error = data['error']
        result['error'] = error if isinstance(error, str) and error in SAFE_CODES else 'unrecognized_error'
    if 'actions' in data:
        actions = data['actions']
        if not isinstance(actions, list) or any(not isinstance(a, str) or a not in ACTIONS for a in actions):
            raise ProbeError('手机返回了未授权操作。')
        result['actions'] = actions
    for field in ('summary', 'captureDiagnostics', 'lastIncompleteScan', 'nativeAudit'):
        if field in data:
            if not isinstance(data[field], dict):
                raise ProbeError('手机读页摘要无效。')
            result[field] = _safe_fields(data[field])
    if 'nativeStructure' in data:
        result['nativeStructure'] = _safe_structure(data['nativeStructure'])
    return result


def _safe_structure(data):
    if (not isinstance(data, dict) or type(data.get('complete')) is not bool
            or not isinstance(data.get('nodes'), list) or len(data['nodes']) > 80
            or (not data['complete'] and data['nodes'])):
        raise ProbeError('手机原生结构摘要无效。')
    rows = []
    flags = ('visible', 'enabled', 'clickable', 'selected', 'checkable', 'checked')
    kinds = {'text', 'button', 'checkbox', 'input', 'webview', 'scroll', 'container', 'other'}
    for row in data['nodes']:
        if not isinstance(row, dict):
            raise ProbeError('手机原生结构摘要无效。')
        parent, resource, kind = row.get('parent'), row.get('resourceId'), row.get('kind')
        if (type(parent) is not int or not -1 <= parent < len(rows)
                or not isinstance(resource, str)
                or (resource != '' and not re.fullmatch(r'cn\.damai:id/[a-z][a-z0-9_]{0,79}', resource))
                or not isinstance(kind, str) or kind not in kinds
                or any(type(row.get(key)) is not bool for key in flags)):
            raise ProbeError('手机原生结构摘要无效。')
        rows.append(dict(parent=parent, resourceId=resource, kind=kind,
            **{key: row[key] for key in flags}))
    return {'complete': data['complete'], 'nodes': rows}


def _safe_fields(data):
    result = {}
    for key in NUMERIC_FIELDS & data.keys():
        value = data[key]
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            raise ProbeError('手机返回的计数或耗时无效。')
        result[key] = value
    for key in BOOL_FIELDS & data.keys():
        if type(data[key]) is not bool:
            raise ProbeError('手机返回的能力标记无效。')
        result[key] = data[key]
    for key, allowed in ENUM_FIELDS.items():
        if key in data:
            if not isinstance(data[key], str) or data[key] not in allowed:
                raise ProbeError('手机返回了未知状态。')
            result[key] = data[key]
    return result


def pair(serial, code):
    if not isinstance(code, str) or not re.fullmatch(r'[0-9 ]{8,16}', code):
        raise ProbeError('请输入手机新生成的 8 位一次性配对码。')
    code = code.replace(' ', '')
    if not re.fullmatch(r'[0-9]{8}', code):
        raise ProbeError('请输入手机新生成的 8 位一次性配对码。')
    payload = (json.dumps({'protocolVersion': 1, 'action': 'pair', 'pairingCode': code}) + '\n').encode()
    data = _decode_envelope(_exchange(serial, payload))
    if data['ok'] is not True or set(data) != {'protocolVersion', 'stage', 'executionSupported', 'ok', 'token'}:
        raise ProbeError('配对失败，请在手机重新生成一次性配对码。')
    token = data['token']
    if not isinstance(token, str) or not re.fullmatch(r'[0-9a-f]{48}', token):
        raise ProbeError('配对响应无效。')
    return token


def request(serial, action, token):
    return decode_response(_exchange(serial, encode_request(action, token)))


def _exchange(serial, payload):
    if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', serial):
        raise ProbeError('设备标识无效。')
    adb = shutil.which('adb')
    if not adb:
        raise ProbeError('未找到 ADB。')
    forward = subprocess.run([adb, '-s', serial, 'forward', 'tcp:0', f'tcp:{PORT}'], capture_output=True, text=True, timeout=8)
    port = forward.stdout.strip()
    if forward.returncode or not port.isascii() or not port.isdigit() or not 1 <= int(port) <= 65535:
        raise ProbeError('无法建立专用 USB 通道，请检查手机连接。')
    try:
        with socket.create_connection(('127.0.0.1', int(port)), timeout=20) as connection:
            connection.sendall(payload)
            return read_reply(connection)
    finally:
        # Remove only the forwarding rule allocated by this invocation.
        subprocess.run([adb, '-s', serial, 'forward', '--remove', f'tcp:{port}'], capture_output=True, timeout=8)


def read_pairing_code(dialog=False):
    if not dialog:
        return getpass.getpass('输入手机新生成的 8 位一次性配对码（不会保存）：').strip()
    if sys.platform != 'darwin':
        raise ProbeError('系统密码输入框仅适用于 macOS。')
    # The secret is returned only to this process, never through the shell/tool output.
    script = ('text returned of (display dialog "请在手机验证 APK 首页生成配对码，输入 8 位数字。'
              '配对码 2 分钟有效。点击确定完成配对后，会另行提示准备测试页面。配对码不保存、不写入日志。" '
              'default answer "" with hidden answer buttons {"取消", "确定"} '
              'default button "确定" cancel button "取消" giving up after 180)')
    result = subprocess.run(['osascript', '-e', script], capture_output=True, text=True, timeout=190)
    if result.returncode:
        raise ProbeError('配对输入已取消或不可用。')
    return result.stdout.strip()


def confirm_inspection_ready():
    script = ('set answer to display dialog "配对成功。下一步：\\n'
              '1. 在手机首页点“授权屏幕读取”，手动确认系统提示。\\n'
              '2. 打开“合成测试页”，保持该页不动。\\n'
              '3. 手机显示合成测试页后，再点此处“开始只读测量”。\\n'
              '本次不点击大麦、不下单。无需再次生成配对码。" '
              'buttons {"取消", "开始只读测量"} default button "开始只读测量" '
              'cancel button "取消" giving up after 180\n'
              'if gave up of answer then\nreturn "cancelled"\nelse\nreturn "ready"\nend if')
    result = subprocess.run(['osascript', '-e', script], capture_output=True, text=True, timeout=190)
    if result.returncode or result.stdout.strip() != 'ready':
        raise ProbeError('读页验证已取消，未发送检查请求。')


def confirm_native_audit_ready():
    script = ('set answer to display dialog "配对成功。请在手机验证应用首页点击‘一次性核对大麦页面’。'
              '它会停止录屏并打开大麦。请停在要核对的选票、观演人或确认页，不提交订单、不付款。'
              '2 分钟内点击这里‘核对原生节点’。只读取一次节点结构，不截图、不点击，姓名证件和页面文字不导出。" '
              'buttons {"取消", "核对原生节点"} default button "核对原生节点" '
              'cancel button "取消" giving up after 180\n'
              'if gave up of answer then\nreturn "cancelled"\nelse\nreturn "ready"\nend if')
    result = subprocess.run(['osascript', '-e', script], capture_output=True, text=True, timeout=190)
    if result.returncode or result.stdout.strip() != 'ready':
        raise ProbeError('原生节点核对已取消，未发送核对请求。')


def inspect_complete(serial, token, timeout=15):
    result = request(serial, 'inspect', token)
    if result.get('error') == 'capture_busy':
        raise ProbeError('已有截图识别正在进行，请待它完成后重新验证。', phone=result)
    deadline = time.monotonic() + timeout
    while result['ok'] and result.get('captureState') in ('awaiting_frame', 'ocr_running'):
        if time.monotonic() >= deadline:
            raise ProbeError('手机 OCR 未在时限内完成。')
        time.sleep(0.2)
        result = request(serial, 'status', token)
    return result


def sample_inspections(serial, token, count):
    if type(count) is not int or not 1 <= count <= 100:
        raise ProbeError('热读取样本数量必须在 1 到 100 之间。')
    samples = []
    kind = None
    for _ in range(count + 1):
        began = time.perf_counter()
        response = inspect_complete(serial, token)
        summary = response.get('summary', {})
        current_kind = summary.get('pageKind')
        if (not response['ok'] or response.get('captureState') != 'ready'
                or summary.get('captureState') != 'complete' or summary.get('truncated') is not False
                or any(field not in summary for field in ('nativeMs', 'screenshotMs', 'ocrMs'))
                or current_kind not in ('synthetic', 'detail', 'selection')
                or (kind is not None and kind != current_kind)):
            raise ProbeError('页面变化或识别未完成，本组测量停止。', phone=response)
        kind = current_kind
        sample = {field: summary[field] for field in ('nativeMs', 'screenshotMs', 'ocrMs')}
        sample['usbRoundTripMs'] = round((time.perf_counter() - began) * 1000, 2)
        samples.append(sample)
        time.sleep(0.1)
    warm = samples[1:]
    return {'ok': True, 'stage': 'feasibility', 'executionSupported': False, 'pageKind': kind,
            'pageIdentityVerified': False,
            'method': 'Operator-held page; category changes abort. Native and OCR timing are on-device; USB round trip includes desktop forwarding and polling. No cache or purchase actions.',
            'firstSample': samples[0], 'warmSamples': warm,
            'warmSummary': {field: {'median': statistics.median(sample[field] for sample in warm),
                                    'p95': sorted(sample[field] for sample in warm)[math.ceil(len(warm) * .95) - 1]}
                            for field in samples[0]}}


def main():
    parser = argparse.ArgumentParser(description='仅验证手机 APK，不执行任何购票点击。')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--action', choices=ACTIONS, default='status')
    parser.add_argument('--dialog', action='store_true', help='在 macOS 本机密码框输入配对码，不经过聊天或日志')
    parser.add_argument('--samples', type=int, default=0, help='仅 inspect：单列首次样本，另测 1–100 次热读页；请保持页面不变')
    args = parser.parse_args()
    if args.samples and (args.action != 'inspect' or not 1 <= args.samples <= 100):
        parser.error('--samples 仅支持 inspect，范围为 1–100。')
    try:
        token = pair(args.serial, read_pairing_code(args.dialog))
        if args.dialog and args.action == 'inspect':
            confirm_inspection_ready()
        if args.dialog and args.action == 'native_audit':
            confirm_native_audit_ready()
        if args.samples:
            result = sample_inspections(args.serial, token, args.samples)
        else:
            result = inspect_complete(args.serial, token) if args.action == 'inspect' else request(args.serial, args.action, token)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result['ok'] else 1
    except ProbeError as error:
        output = {'status': 'incomplete', 'message': str(error)}
        if error.phone is not None:
            output['phone'] = error.phone
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return 1
    except (ValueError, OSError, subprocess.TimeoutExpired):
        print(json.dumps({'status': 'incomplete', 'message': '验证未完成。请检查手机授权、配对密钥、USB 连接和 APK 提示；未发送购票操作。'}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
