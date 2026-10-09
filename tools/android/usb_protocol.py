"""Bounded USB executor wire protocol. No raw input or submission command exists."""
import json
import re
import struct
import time

VERSION = 1
MAGIC = 0x54555342
MAX_FRAME = 32768
PACKAGE = 'local.tickets.companion.usb'
RUNNER = PACKAGE + '/local.tickets.companion.usb.UsbExecutionRunner'
OPERATIONS = {'probe': 0, 'inspect': 1, 'open_picker': 2, 'select_session': 3, 'prepare': 4, 'start': 5}


class UsbError(RuntimeError):
    def __init__(self, reason):
        # Reasons are fixed identifiers, never reflected phone text or request values.
        self.reason = reason if isinstance(reason, str) and re.fullmatch(r'[a-z0-9_:]{1,120}', reason) else 'usb_execution_failed'
        super().__init__(self.reason)


def _text(value, limit=300):
    if not isinstance(value, str):
        raise UsbError('label_invalid')
    try:
        units = len(value.encode('utf-16-le', errors='strict')) // 2
        data = value.encode('utf-8', errors='strict')
    except UnicodeError:
        raise UsbError('label_invalid') from None
    if not value.strip() or units > limit or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
        raise UsbError('label_invalid')
    if len(data) > limit * 4:
        raise UsbError('label_invalid')
    return struct.pack('!I', len(data)) + data


def encode_request(capability, operation, *, event_title='', session='', config=None, unit_price=None):
    if not isinstance(capability, str) or not re.fullmatch(r'[0-9a-f]{64}', capability):
        raise UsbError('capability_invalid')
    if operation not in OPERATIONS:
        raise UsbError('operation_invalid')
    out = struct.pack('!II', MAGIC, VERSION) + _text(capability, 64) + bytes([OPERATIONS[operation]])
    if operation in ('open_picker', 'select_session'):
        out += _text(event_title)
        if operation == 'select_session':
            out += _text(session)
    elif operation in ('prepare', 'start'):
        if not isinstance(config, dict) or config.get('autoSubmit', False) is not False:
            raise UsbError('automatic_submission_not_supported')
        quantity = config.get('quantity')
        timeout = config.get('timeoutSeconds')
        start = config.get('startAt')
        buyers = config.get('buyerLabels', [])
        if (type(quantity) is not int or not 1 <= quantity <= 20 or type(unit_price) is not int or not 1 <= unit_price <= 2_000_000_000
                or unit_price * quantity > 2_000_000_000 or type(timeout) is not int or not 10 <= timeout <= 600
                or (start is not None and (type(start) is not int or start < 0))
                or (operation == 'prepare' and start is not None)
                or not isinstance(buyers, list) or len(buyers) not in (0, quantity)):
            raise UsbError('plan_invalid')
        out += _text(config.get('eventTitle')) + _text(config.get('session')) + _text(config.get('tier'))
        out += struct.pack('!qiqqii', unit_price, quantity, unit_price * quantity, start or 0, timeout, len(buyers))
        for buyer in buyers:
            out += _text(buyer)
    if len(out) > MAX_FRAME:
        raise UsbError('frame_size_invalid')
    return out


def encode_control(sequence, cancel=False):
    if type(sequence) is not int or not 1 <= sequence <= 2**63 - 1 or type(cancel) is not bool:
        raise UsbError('control_invalid')
    return struct.pack('!Bq', 2 if cancel else 1, sequence)


def send_frame(sock, data):
    if not isinstance(data, bytes) or not 1 <= len(data) <= MAX_FRAME:
        raise UsbError('frame_size_invalid')
    sock.sendall(struct.pack('!I', len(data)) + data)


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise UsbError('duplicate_reply_field')
        result[key] = value
    return result


def receive_frame(sock, run_id, *, cancelled=lambda: False, silence_seconds=10):
    deadline = time.monotonic() + silence_seconds
    def exact(size):
        data = bytearray()
        while len(data) < size:
            if cancelled():
                raise UsbError('stopped_by_user')
            if time.monotonic() >= deadline:
                raise UsbError('usb_reply_timeout')
            try:
                chunk = sock.recv(size - len(data))
            except TimeoutError:
                continue
            if not chunk:
                raise UsbError('usb_disconnected')
            data.extend(chunk)
        return bytes(data)
    size = struct.unpack('!I', exact(4))[0]
    if not 1 <= size <= MAX_FRAME:
        raise UsbError('frame_size_invalid')
    try:
        frame = json.loads(exact(size).decode('utf-8', errors='strict'), object_pairs_hook=_unique,
                           parse_constant=lambda _: (_ for _ in ()).throw(UsbError('reply_invalid')))
    except UsbError:
        raise
    except (UnicodeError, ValueError, TypeError):
        raise UsbError('reply_invalid') from None
    if (not isinstance(frame, dict) or set(frame) != {'protocol', 'runId', 'type', 'data'}
            or type(frame['protocol']) is not int or frame['protocol'] != VERSION or frame['runId'] != run_id
            or frame['type'] not in ('hello', 'progress', 'complete', 'error') or not isinstance(frame['data'], dict)):
        raise UsbError('reply_invalid')
    return frame
