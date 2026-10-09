# /// script
# requires-python = ">=3.10"
# ///
"""USB-started phone execution adapter. Standard library only; no uiautomator2 or submit path."""
import copy
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid

from damai import normalized, event_key, validate_config
from submission_guard import SubmissionGuard, validate_acknowledgement
from usb_protocol import PACKAGE, UsbError, encode_request
from usb_transport import UsbSession, adb_call, artifact


MESSAGES = {
    'usb_executor_missing': '缺少完整USB执行组件。开发环境请先运行 npm run build:usb-executor；安装版请重新安装完整应用。',
    'usb_artifact_invalid': 'USB执行组件完整性校验失败，保持停止；请重新构建或安装完整应用。',
    'device_unavailable': '设备未连接或USB调试未授权。任务不会自动恢复。',
    'usb_executor_busy': '手机已有USB执行会话，未覆盖或终止它；请等待该会话结束。',
    'private_usb_socket_unsupported': '当前系统不支持已验证的私有USB套接字通道，未改用网络监听。',
    'normal_companion_update_required': '手机上的本地预览组件需要更新后才能确认交接；未启动新的执行。',
    'normal_companion_signature_mismatch': '本地预览组件签名不匹配，未接管手机；不会卸载或删除原应用数据。',
    'normal_execution_handoff_unconfirmed': '未确认原手机任务已停止，USB执行保持停止。',
    'automatic_submission_not_supported': 'USB模式仅核验订单，不允许自动提交或付款。',
    'stopped_by_user': '已停止发出后续动作；已交给系统的动作可能完成，请核对手机当前状态。',
    'usb_disconnected': 'USB执行连接已断开，已撤销会话；不会自动恢复或重发动作。',
    'usb_lease_expired': 'USB心跳已失效，手机已禁止后续动作；不会自动恢复。',
    'screen_locked': '手机已锁屏，执行已停止；解锁后需要重新读取和确认目标。',
    'foreground_changed': '大麦已离开前台，执行已停止；返回不会自动恢复。',
    'damai_not_foreground': '未能恢复大麦前台，请打开目标演出后重新读取。',
    'damai_not_installed': '手机未安装大麦。',
    'damai_version_unsupported': '当前只验证了大麦9.0.35；其他版本保持停止。',
    'full_screen_required': '请关闭分屏或弹窗，让大麦全屏显示后重新读取。',
    'primary_portrait_display_required': 'USB执行当前只支持主屏竖屏，未猜测其他显示方式的坐标。',
    'capture_window_not_allowed': '存在未确认的窗口或覆盖层，未读取截图或继续操作。',
    'capture_page_not_allowed': '页面不符合公共票务截图范围，已停止；订单页和验证页不会截图。',
    'secure_or_blank_frame': '屏幕读取为空或受保护，未尝试绕过。',
    'action_effect_unconfirmed': '没有确认操作产生实际效果，已停止，没有重复点击。',
    'navigation_effect_unconfirmed': '没有确认页面切换效果，已停止，没有重复点击。',
    'deadline': '任务已到截止时间，已停止后续动作。',
    'sold_out_or_upcoming': '目标票档缺货或未开售，已停止，不自动换档。',
    'confirmation_verified_manual_submit': '已核对选票、金额和观演人，停在确认页；尚未提交订单或付款。',
}
BLOCKERS = {
    'RISK': '需要在手机处理登录或验证提示', 'DIALOG': '页面存在弹窗', 'UNKNOWN_PAGE': '尚未识别为受支持页面',
    'LOADING': '页面正在加载', 'QUEUE': '正在排队', 'RESERVATION': '这是预约页面，不是购票页面',
    'UNSUPPORTED_BUYER_STRUCTURE': '观演人只在手机按完整原生结构核验',
    'UNSUPPORTED_FEE_STRUCTURE': '尚未独立核实费用结构', 'UNSUPPORTED_TIER_IDENTITY': '订单页只提供价格，完整票档需由选票步骤核对',
}


def message(reason):
    return MESSAGES.get(reason, '校验未通过，已停止且未重复操作。诊断：' + reason)


class Cancelled(Exception):
    pass


class UsbDriver:
    def __init__(self, state_dir=None, adb=None, apk=None, session_factory=UsbSession):
        self.adb = adb or os.environ.get('TICKETS_ADB', 'adb')
        self.apk = Path(apk or os.environ.get('TICKETS_USB_EXECUTOR_APK', Path(__file__).parent / 'usb-executor/app-usb-executor.apk'))
        self.session_factory = session_factory
        self.submission_guard = SubmissionGuard(state_dir)
        self.lock = threading.RLock()
        self.io = threading.Lock()
        self.states = {}
        self.epochs = {}
        self.active = None
        self.active_token = None
        self.closed = False
        self.cancel_event = threading.Event()
        self.session = None
        self.done = threading.Event(); self.done.set()
        self._installed = {}

    def token(self, serial):
        if not isinstance(serial, str):
            return 0
        with self.lock:
            return self.epochs.get(serial, 0)

    def _check(self, serial, token):
        with self.lock:
            if self.closed or self.epochs.get(serial, 0) != token:
                raise Cancelled()

    def state(self, serial):
        with self.lock:
            current = self.states.setdefault(serial, {'deviceId': serial, 'backend': 'usb', 'status': 'idle', 'phase': 'idle',
                'message': 'USB辅助模式：读取公共票务目录，手机完成核验，停在确认页，不提交不付款。',
                'page': None, 'catalog': None, 'prepared': None, 'config': None, 'metrics': {}, 'logs': [],
                'updatedAt': int(time.time() * 1000), 'automaticSubmissionSupported': False})
            result = copy.deepcopy(current)
            if self.active == serial and result['status'] not in ('preparing', 'waiting', 'running'):
                result.update(status='running', phase='finishing', message='正在结束当前USB会话，请稍候。')
        result['submissionGuard'] = self.submission_guard.state(serial)
        return result

    def _update(self, serial, token=None, **values):
        self.state(serial)
        with self.lock:
            if token is not None:
                self._check(serial, token)
            current = self.states[serial]
            if 'message' in values and values['message'] != current['message']:
                current['logs'] = (current['logs'] + [{'time': int(time.time() * 1000), 'message': values['message']}])[-80:]
            current.update(values)
            current['updatedAt'] = max(int(time.time() * 1000), current['updatedAt'] + 1)
        return self.state(serial)

    @staticmethod
    def _config_key(config):
        return (tuple(normalized(config.get(k, '')) for k in ('eventTitle', 'session', 'tier')) +
                (config.get('quantity'), config.get('autoSubmit', False), tuple(normalized(x) for x in config.get('buyerLabels', []))))

    def _validated_config(self, serial, config, preparing):
        now = int(time.time() * 1000)
        validate_config(config, now)
        if config.get('autoSubmit', False) is not False:
            raise UsbError('automatic_submission_not_supported')
        if config.get('startAt') is not None and (preparing or config['startAt'] > now + 24 * 3600_000):
            raise UsbError('start_time_invalid')
        if set(config) - {'eventTitle', 'session', 'tier', 'quantity', 'buyerLabels', 'autoSubmit', 'startAt', 'timeoutSeconds'}:
            raise UsbError('plan_invalid')
        state = self.state(serial)
        page = state.get('page') or {}
        catalog = page if page.get('kind') == 'picker' else state.get('catalog') or {}
        if (page.get('kind') not in ('detail', 'picker') or page.get('blockers')
                or event_key(page.get('title', '')) != event_key(config['eventTitle'])
                or event_key(catalog.get('title', '')) != event_key(config['eventTitle'])):
            raise ValueError('请先读取并核对当前演出的完整票务目录。')
        sessions = [x for x in catalog.get('sessions', []) if normalized(x.get('label')) == normalized(config['session'])]
        tiers = [x for x in catalog.get('tiers', []) if normalized(x.get('label')) == normalized(config['tier'])]
        if len(sessions) != 1 or sessions[0].get('selected') is not True or len(tiers) != 1:
            raise ValueError('目标场次和票档尚未从手机核对，请重新读取。')
        price = tiers[0].get('unitPriceCents')
        if type(price) is not int or not 1 <= price * config['quantity'] <= 2_000_000_000:
            raise UsbError('plan_invalid')
        result = copy.deepcopy(config)
        result.setdefault('buyerLabels', []); result.setdefault('autoSubmit', False); result.setdefault('startAt', None); result.setdefault('timeoutSeconds', 120)
        encode_request('0' * 64, 'prepare' if preparing else 'start', config=result, unit_price=price)
        return result, price

    def _ensure_installed(self, serial, token):
        self._check(serial, token)
        apk, metadata = artifact(self.apk)
        state = adb_call(self.adb, serial, ['get-state'], check=False).stdout.decode(errors='replace').strip()
        if state != 'device':
            raise UsbError('device_unavailable')
        pids = adb_call(self.adb, serial, ['shell', 'pidof', PACKAGE], check=False).stdout.decode(errors='replace').strip()
        if pids:
            raise UsbError('usb_executor_busy')
        if self._installed.get(serial) != metadata['sha256']:
            self._update(serial, token, message='正在安装或校验本项目USB执行器；不申请新的系统高权限。')
            self._check(serial, token)
            result = adb_call(self.adb, serial, ['install', '-r', str(apk)], timeout=60, check=False)
            if result.returncode or b'Success' not in result.stdout:
                raise UsbError('usb_executor_install_failed')
            self._installed[serial] = metadata['sha256']
        self._check(serial, token)

    def _adopt_report(self, serial, token, report, progress=False, preparing=False):
        values = {'metrics': copy.deepcopy(report.get('metrics', {}))}
        if 'page' in report:
            page = copy.deepcopy(report['page'])
            page['blockers'] = [BLOCKERS.get(x, '页面字段未完整核验：' + x) for x in page['blockers']]
            values['page'] = page
            if page['kind'] == 'picker' and not page['blockers']:
                values['catalog'] = copy.deepcopy(page)
            elif page['kind'] in ('detail', 'picker'):
                old = self.state(serial).get('catalog') or {}
                if event_key(old.get('title', '')) != event_key(page.get('title', '')):
                    values['catalog'] = None
        if progress:
            values['phase'] = report['reason']
            values['status'] = 'preparing' if preparing else ('waiting' if report['phase'] == 'waiting' else 'running')
            values['message'] = '手机正在等待已设定的开始时间。' if report['phase'] == 'waiting' else '手机正在核对页面并执行已授权的步骤；不会提交或付款。'
        self._update(serial, token, **values)

    def _operation(self, serial, token, operation, **arguments):
        with self.submission_guard.operation(serial), self.io:
            self.submission_guard.assert_clear(serial)
            self._ensure_installed(serial, token)
            session = self.session_factory(self.adb, serial, self.cancel_event,
                lambda data: self._adopt_report(serial, token, data, progress=True, preparing=operation == 'prepare'))
            with self.lock:
                self._check(serial, token)
                self.session = session
            try:
                result = session.run(operation, **arguments)
                self._check(serial, token)
                return result
            finally:
                with self.lock:
                    if self.session is session:
                        self.session = None

    def _begin(self, serial, queued_token=None):
        with self.lock:
            if self.closed:
                raise Cancelled()
            if queued_token is not None:
                self._check(serial, queued_token)
            if self.active is not None or self.io.locked():
                raise ValueError('已有设备操作，请等待结束或先停止。')
            token = self.epochs.get(serial, 0) + 1
            self.epochs[serial] = token
            self.active, self.active_token = serial, token
            self.cancel_event.clear(); self.done.clear()
            return token

    def _end(self, serial, token):
        with self.lock:
            if self.active == serial and self.active_token == token:
                self.active = None; self.active_token = None
                current = self.states.get(serial)
                if current is not None:
                    current['updatedAt'] = max(int(time.time() * 1000), current['updatedAt'] + 1)
                if self.epochs.get(serial, 0) != token:
                    self._update(serial, status='cancelled', phase='cancelled', prepared=None, message=message('stopped_by_user'))
                self.done.set()

    def inspect(self, serial, queued_token=None):
        return self._synchronous(serial, 'inspect', queued_token)

    def open_picker(self, serial, title, queued_token=None):
        if not isinstance(title, str) or not title.strip():
            raise ValueError('请先读取并确认演出标题。')
        return self._synchronous(serial, 'open_picker', queued_token, event_title=title)

    def select_session(self, serial, title, session, queued_token=None):
        if not all(isinstance(x, str) and x.strip() for x in (title, session)):
            raise ValueError('请先读取演出并选择明确场次。')
        return self._synchronous(serial, 'select_session', queued_token, event_title=title, session=session)

    def _synchronous(self, serial, operation, queued_token, **arguments):
        token = self._begin(serial, queued_token)
        self._update(serial, token, status='running', phase='inspecting', prepared=None, config=None, message='正在通过USB读取并核验手机页面。')
        try:
            result = self._operation(serial, token, operation, **arguments)
            self._adopt_report(serial, token, result)
            page = self.state(serial).get('page') or {}
            blocked = bool(page.get('blockers')) or page.get('kind') not in ('detail', 'picker')
            self._update(serial, token, status='needs_action' if blocked else 'idle', phase='idle',
                         message='请先在手机处理当前页面提示，再重新读取。' if blocked else '已读取公共票务目录，请核对目标；身份信息不会回传电脑。')
        except Cancelled:
            pass
        except Exception as error:
            self._fail(serial, token, error)
            raise
        finally:
            self._end(serial, token)
        return self.state(serial)

    def _fail(self, serial, token, error):
        reason = error.reason if isinstance(error, UsbError) else 'usb_execution_failed'
        try:
            self._update(serial, token, status='interrupted' if reason in ('usb_disconnected', 'usb_lease_expired') else 'failed',
                         phase='failed', prepared=None, message=message(reason))
        except Cancelled:
            pass

    def prepare(self, serial, config, queued_token=None):
        return self._launch(serial, config, queued_token, preparing=True)

    def start(self, serial, config, queued_token=None, prepared_id=None):
        return self._launch(serial, config, queued_token, preparing=False, prepared_id=prepared_id)

    def _launch(self, serial, config, queued_token, preparing, prepared_id=None):
        with self.lock:
            if queued_token is not None:
                self._check(serial, queued_token)
            validated, price = self._validated_config(serial, config, preparing)
            previous = self.state(serial).get('prepared')
            if not preparing and (not previous or previous.get('id') != prepared_id or
                                  self._config_key(previous['config']) != self._config_key(validated)):
                raise ValueError('目标未锁定或已经修改，请先锁定并预热。')
            self.submission_guard.assert_clear(serial)
            token = self._begin(serial, queued_token)
            status = 'preparing' if preparing else ('waiting' if validated['startAt'] else 'running')
            self._update(serial, token, status=status, phase=status, prepared=None, config=validated, metrics={},
                         message='正在手机预选并核验目标；不会进入提交。' if preparing else '开始已确认的USB任务，手机核验完成后停在确认页。')
            worker = threading.Thread(target=self._run, args=(serial, token, validated, price, preparing), name='usb-operation', daemon=True)
            worker.start()
        return self.state(serial)

    @staticmethod
    def _prepared_proof(result, config, price):
        page = result.get('page') or {}
        if page.get('blockers') or event_key(page.get('title', '')) != event_key(config['eventTitle']):
            return False
        if result['reason'] == 'waiting_sale':
            return page.get('kind') == 'detail' and page.get('purchaseAvailability') == 'upcoming' and result.get('verification') == 'event_only'
        selected_sessions = [x for x in page.get('sessions', []) if x.get('selected') is True]
        selected_tiers = [x for x in page.get('tiers', []) if x.get('selected') is True]
        return (page.get('kind') == 'picker' and result.get('verification') == 'full' and type(page.get('quantity')) is int
                and page['quantity'] == config['quantity'] and len(selected_sessions) == len(selected_tiers) == 1
                and normalized(selected_sessions[0].get('label')) == normalized(config['session'])
                and normalized(selected_tiers[0].get('label')) == normalized(config['tier'])
                and selected_tiers[0].get('unitPriceCents') == price
                and all(x.get('selectionVerified') is True for x in page.get('sessions', []) + page.get('tiers', [])))

    def _run(self, serial, token, config, price, preparing):
        began = time.monotonic()
        try:
            result = self._operation(serial, token, 'prepare' if preparing else 'start', config=config, unit_price=price)
            self._adopt_report(serial, token, result)
            elapsed = round((time.monotonic() - began) * 1000, 1)
            metrics = {**self.state(serial).get('metrics', {}), 'prepareMs' if preparing else 'executionMs': elapsed}
            if preparing and result['phase'] == 'ready' and result['reason'] in ('selected', 'waiting_sale'):
                if not self._prepared_proof(result, config, price):
                    raise UsbError('preparation_result_unverified')
                prepared = {'id': uuid.uuid4().hex, 'at': int(time.time() * 1000), 'config': copy.deepcopy(dict(config, startAt=None)),
                            'readiness': result['reason'], 'verification': result.get('verification', 'full')}
                self._update(serial, token, status='ready', phase='ready', prepared=prepared, metrics=metrics,
                             message='目标已在手机核对，开始执行时仍会重新检查；不提交不付款。')
            elif not preparing and result['phase'] == 'confirmed' and result['reason'] == 'confirmation_verified_manual_submit' and result.get('page', {}).get('orderVerified') is True:
                self._update(serial, token, status='needs_action', phase='confirmed', prepared=None, metrics=metrics,
                             message=message('confirmation_verified_manual_submit'))
            elif result['phase'] == 'confirmed' or result['reason'] == 'confirmation_verified_manual_submit':
                raise UsbError('usb_confirmation_invalid')
            else:
                self._update(serial, token, status='needs_action', phase='stopped', prepared=None, metrics=metrics,
                             message=message(result['reason']))
        except Cancelled:
            pass
        except Exception as error:
            self._fail(serial, token, error)
        finally:
            self._end(serial, token)

    def stop(self, serial):
        with self.lock:
            self.epochs[serial] = self.epochs.get(serial, 0) + 1
            current = self.session if self.active == serial else None
            active = self.active == serial
            if active:
                self.cancel_event.set()
            self._update(serial, prepared=None, status='running' if active else 'cancelled',
                         phase='stopping' if active else 'cancelled',
                         message='停止请求已发送，正在等待手机会话结束。' if active else message('stopped_by_user'))
        if current is not None:
            current.cancel()
        if active:
            self.done.wait(timeout=1)
        return self.state(serial)

    def acknowledge_submission(self, serial, attempt_id, acknowledged):
        validate_acknowledgement(attempt_id, acknowledged)
        with self.lock:
            if self.active is not None or not self.io.acquire(blocking=False):
                raise ValueError('仍有设备操作，结束后才能确认订单记录。')
            try:
                with self.submission_guard.operation(serial):
                    self.submission_guard.acknowledge(serial, attempt_id, acknowledged)
                    self.epochs[serial] = self.epochs.get(serial, 0) + 1
                    self._update(serial, status='idle', phase='idle', config=None, prepared=None,
                                 message='已记录人工核实。请重新读取并锁定目标，不会自动恢复任务。')
            finally:
                self.io.release()
        return self.state(serial)

    def shutdown(self):
        with self.lock:
            self.closed = True
            for serial in self.epochs:
                self.epochs[serial] += 1
            self.cancel_event.set()
            current = self.session
        if current is not None:
            current.cancel()
        self.done.wait(timeout=10)


def main():
    driver = UsbDriver()
    output_lock = threading.Lock()
    operations = ThreadPoolExecutor(max_workers=1)
    def handle(request):
        identifier, serial = request.get('requestId'), request.get('deviceId', '')
        try:
            if not isinstance(serial, str) or re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', serial) is None or request.get('backend', 'usb') != 'usb':
                raise ValueError('设备或执行通道无效。')
            action, epoch = request.get('action'), request.get('_queuedEpoch')
            if action == 'status': data = driver.state(serial)
            elif action == 'stop': data = driver.stop(serial)
            elif action == 'acknowledge_submission': data = driver.acknowledge_submission(serial, request.get('attemptId'), request.get('acknowledged'))
            elif action == 'inspect': data = driver.inspect(serial, epoch)
            elif action == 'open_picker': data = driver.open_picker(serial, request.get('eventTitle'), epoch)
            elif action == 'select_session': data = driver.select_session(serial, request.get('eventTitle'), request.get('session'), epoch)
            elif action == 'prepare': data = driver.prepare(serial, request.get('config'), epoch)
            elif action == 'start': data = driver.start(serial, request.get('config'), epoch, request.get('preparedId'))
            else: raise ValueError('不支持的USB设备操作。')
            reply = {'requestId': identifier, 'ok': True, 'data': data}
        except Cancelled:
            reply = {'requestId': identifier, 'ok': True, 'data': driver.state(serial)}
        except UsbError as error:
            reply = {'requestId': identifier, 'ok': False, 'error': message(error.reason)}
        except (ValueError, RuntimeError) as error:
            reply = {'requestId': identifier, 'ok': False, 'error': str(error)}
        except Exception:
            reply = {'requestId': identifier, 'ok': False, 'error': 'USB执行通信失败，已停止；请检查连接和手机当前页面。'}
        with output_lock:
            print(json.dumps(reply, ensure_ascii=False), flush=True)
    try:
        import sys
        for line in sys.stdin:
            if len(line) > 65536:
                driver.shutdown()
                break
            try:
                request = json.loads(line, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                if not isinstance(request, dict) or not isinstance(request.get('requestId'), str) or not 1 <= len(request['requestId']) <= 80:
                    raise ValueError()
            except (ValueError, TypeError):
                continue
            if request.get('action') in ('status', 'stop', 'acknowledge_submission'):
                handle(request)
            else:
                request['_queuedEpoch'] = driver.token(request.get('deviceId', ''))
                operations.submit(handle, request)
    finally:
        driver.shutdown()
        operations.shutdown(wait=True, cancel_futures=True)


if __name__ == '__main__':
    main()
