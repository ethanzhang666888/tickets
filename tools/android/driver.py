# /// script
# requires-python = ">=3.10"
# dependencies = ["uiautomator2==3.7.0"]
# ///
"""Local JSONL driver with optional order submission and manual Alipay handoff."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from damai import Page, PACKAGE, TIER_LIST, normalized, next_step, validate_config
from ocr import OCRWorker
from vision_cache import VisionCache
from submission_guard import SubmissionGuard, validate_acknowledgement

if os.environ.get("TICKETS_ADB"):
    os.environ["ADBUTILS_ADB_PATH"] = os.environ["TICKETS_ADB"]
import adbutils
import uiautomator2 as u2
from uiautomator2.core import _jsonrpc_call


class SingleAttemptDevice(u2.Device):
    def __init__(self, device, preserve_jar=False):
        try:
            super().__init__(device)
        except Exception:
            # The caller has verified that this run owns the newly created service/JAR.
            try:
                if getattr(self, "_process", None):
                    self.stop_uiautomator(wait=False)
                if not preserve_jar:
                    device.shell(["rm", "-f", "/data/local/tmp/u2.jar"], timeout=5)
            except Exception:
                pass
            raise

    def jsonrpc_call(self, method, params=None, timeout=10):
        # A lost click response must never cause the library to reconnect and click again.
        return _jsonrpc_call(self._dev, self._device_server_port, method, params, min(timeout, 8), False)


class Cancelled(Exception):
    pass


class Driver:
    def __init__(self, state_dir=None):
        self.submission_guard = SubmissionGuard(state_dir)
        self.task_ids = {}
        self.submission_attempts = {}
        self.lock = threading.RLock()
        self.io = threading.Lock()
        self.states = {}
        self.devices = {}
        self.epochs = {}
        self.cancel = threading.Event()
        self.active = None
        self.closed = False
        self.vision = {}
        self.observations = {}
        self.ocr = OCRWorker()
        self.preserved_jars = set()

    def state(self, serial):
        with self.lock:
            state = self.states.setdefault(serial, {
                "deviceId": serial, "status": "idle", "message": "请读取手机上的目标演出。",
                "page": None, "logs": [], "updatedAt": int(time.time() * 1000), "config": None,
                "phase": "idle", "prepared": None, "metrics": {},
            })
            state['submissionGuard'] = self.submission_guard.state(serial)
            return copy.deepcopy(state)

    def update(self, serial, token=None, **values):
        with self.lock:
            self.state(serial)
            if token is not None:
                self.check(serial, token)
            state = self.states[serial]
            if "message" in values and values["message"] != state["message"]:
                state["logs"] = (state["logs"] + [{"time": int(time.time() * 1000), "message": values["message"]}])[-80:]
            state.update(values)
            state["updatedAt"] = max(int(time.time() * 1000), state["updatedAt"] + 1)
            return copy.deepcopy(state)

    def check(self, serial, token):
        if self.closed or self.epochs.get(serial, 0) != token:
            raise Cancelled()

    def token(self, serial):
        with self.lock:
            return self.epochs.get(serial, 0)

    def connect(self, serial, token):
        self.check(serial, token)
        if serial in self.devices:
            return self.devices[serial]
        online = {d.serial for d in adbutils.adb.device_list()}
        if serial not in online:
            raise ConnectionError("手机已断开或未授权，请重新连接后读取；任务不会自动恢复。")
        if serial not in self.devices:
            ad = adbutils.adb.device(serial)
            if ad.shell2(["test", "-e", "/data/local/tmp/u2.jar"], timeout=5).returncode == 0:
                processes = ad.shell(['ps', '-A', '-o', 'PID,ARGS'], timeout=5)
                if not processes.strip() or re.search(r'\bapp_process(?:32|64)?\b|uiautomator|com\.wetest\.', processes, re.I):
                    raise RuntimeError('已有 UIAutomator 服务或无法确认其状态，请先结束现有检查程序。')
                expected = hashlib.sha256((Path(u2.__file__).parent / 'assets/u2.jar').read_bytes()).hexdigest()
                checksum = ad.shell(['sha256sum', '/data/local/tmp/u2.jar'], timeout=5).split()
                if not checksum or checksum[0] != expected:
                    raise RuntimeError('手机存在其他版本的读取组件，本次未覆盖，请先确认现有自动化环境。')
                self.preserved_jars.add(serial)
            package = ad.shell(["dumpsys", "package", PACKAGE], timeout=8)
            version = re.search(r"versionName=(\S+)", package)
            if not version:
                raise RuntimeError("手机未安装大麦。")
            if version.group(1) != "9.0.35":
                raise RuntimeError("当前适配已验证大麦 9.0.35；此手机版本不同，请先重新验证页面。")
            self.check(serial, token)
            device = SingleAttemptDevice(ad, preserve_jar=True) if serial in self.preserved_jars else SingleAttemptDevice(ad)
            try:
                with self.lock:
                    self.check(serial, token)
                    self.devices[serial] = device
            except Cancelled:
                device.stop_uiautomator()
                if serial not in self.preserved_jars:
                    ad.shell(["rm", "-f", "/data/local/tmp/u2.jar"], timeout=5)
                raise
            device.jsonrpc.setConfigurator({"waitForIdleTimeout": 0, "waitForSelectorTimeout": 0})
            device.settings['operation_delay'] = (0, 0)
        return self.devices[serial]

    def foreground(self, device, payment_expected=False):
        try:
            info = device.info
        except Exception as error:
            raise ConnectionError('手机通信中断，请重新连接后读取；任务不会自动恢复。') from error
        allowed = (PACKAGE, 'com.eg.android.AlipayGphone') if payment_expected else (PACKAGE,)
        if info.get('currentPackageName') not in allowed or not info.get('screenOn', False):
            raise ConnectionError('大麦不在前台或手机已锁屏，请在手机处理后重新读取。')
        return info

    def read(self, serial, token, payment_expected=False):
        began = time.perf_counter()
        device = self.connect(serial, token)
        self.check(serial, token)
        info = self.foreground(device, payment_expected)
        if info.get('currentPackageName') == 'com.eg.android.AlipayGphone':
            page = Page('<hierarchy/>')
            page.data['kind'] = 'payment'
            self.update(serial, token, metrics={**self.state(serial)['metrics'],
                        'readMs': round((time.perf_counter() - began) * 1000, 1),
                        'treeMs': 0, 'screenshotMs': 0, 'ocrMs': 0, 'cacheHit': False, 'ocrUsed': False})
            self.observations.pop(serial, None)
            return page
        stage = time.perf_counter()
        xml = device.dump_hierarchy()
        observed_at = time.perf_counter()
        tree_ms = (time.perf_counter() - stage) * 1000
        self.check(serial, token)
        initial = Page(xml)
        use_ocr = initial.data["kind"] in ("detail", "picker", "unknown") or (initial.data['kind'] == 'reservation' and bool(initial.ocr_regions()))
        metrics = {'treeMs': round(tree_ms, 1), 'screenshotMs': 0, 'ocrMs': 0, 'cacheHit': False, 'ocrUsed': use_ocr}
        if use_ocr:
            stage = time.perf_counter()
            picture = device.screenshot().convert('RGB')
            metrics['screenshotMs'] = round((time.perf_counter() - stage) * 1000, 1)
            self.check(serial, token)
            cache = self.vision.setdefault(serial, VisionCache(self.ocr.recognize))
            recognized, observed = cache.read(picture, initial.ocr_regions())
            metrics.update(observed)
            self.check(serial, token)
            page = Page(xml, recognized, image=picture)
        else:
            page = initial
        self.check(serial, token)
        metrics['readMs'] = round((time.perf_counter() - began) * 1000, 1)
        self.update(serial, token, metrics={**self.state(serial)['metrics'], **metrics})
        self.observations[serial] = (page, token, observed_at)
        if page.data['title'] and page.data['kind'] in ('detail', 'picker', 'reservation'):
            catalog = self.state(serial).get('catalog')
            if catalog and normalized(catalog['title']) != normalized(page.data['title']):
                self.update(serial, token, catalog=None)
            if (page.data['kind'] in ('picker', 'reservation') and page.data['tiers']
                    and sum(c['selected'] for c in page.data['sessions']) == 1):
                self.update(serial, token, catalog={key: copy.deepcopy(page.data[key]) for key in ('title', 'sessions', 'tiers')})
        return page

    def tap(self, serial, token, page, target, deadline=None):
        self.check(serial, token)
        if deadline is not None and time.monotonic() >= deadline:
            raise RuntimeError('任务超时，已停止后续操作。')
        device = self.devices[serial]
        observation = self.observations.get(serial)
        # Reuse only the just-read frame, never a prepared plan's old coordinates.
        is_current = (observation is not None and observation[0] is page
                      and observation[1] == token and time.perf_counter() - observation[2] <= .4)
        fresh = page if is_current else self.read(serial, token)
        if (fresh.data != page.data or fresh.data["blockers"]
                or fresh.data["kind"] not in ("detail", "picker", "confirm")):
            raise RuntimeError("点击前页面已变化，已停止，请重新读取。")
        buyer_target = isinstance(target, tuple) and len(target) == 3 and target[0] == 'buyer'
        if fresh.data['kind'] == 'confirm':
            config = self.state(serial).get('config') or {}
            if (target not in ('submit', 'select_alipay') and not buyer_target
                    or not config or next_step(fresh, config)[0] != target):
                raise RuntimeError('确认页的目标或观演人核对失败，已停止。')
            if target in ('submit', 'select_alipay') and config.get('autoSubmit') is not True:
                raise RuntimeError('自动提交未启用，已停止。')
        elif buyer_target or target in ('submit', 'select_alipay'):
            raise RuntimeError('订单操作只允许在已核对的确认页执行。')
        box = page.targets.get(target)
        if box is None or box[2] <= box[0] or box[3] <= box[1]:
            raise RuntimeError("目标控件没有唯一有效位置。")
        if fresh.targets.get(target) != box:
            raise RuntimeError("目标内容或位置已变化，请重新读取。")
        self.check(serial, token)
        if deadline is not None and time.monotonic() >= deadline:
            raise RuntimeError('任务超时，已停止后续操作。')
        if target == 'submit':
            # Commit uncertainty BEFORE dispatch: a lost response or crash can never
            # permit another task/process to submit again on this device.
            task_id = self.task_ids.setdefault(serial, uuid.uuid4().hex)
            intent = self.submission_guard.begin(serial, task_id)
            self.submission_attempts[serial] = intent['attemptId']
        else:
            # Another process may have submitted since this task was prepared.
            self.submission_guard.assert_clear(serial)
        self.check(serial, token)
        if deadline is not None and time.monotonic() >= deadline:
            raise RuntimeError('任务超时，已停止后续操作。')
        device.click((box[0] + box[2]) // 2, (box[1] + box[3]) // 2)
        self.check(serial, token)

    def inspect(self, serial, token=None):
        with self.lock:
            if self.active:
                raise RuntimeError("已有演练在执行，请先停止后再读取或切换设备。")
            token = self.token(serial) if token is None else token
            self.check(serial, token)
        with self.io:
            try:
                page = self.read(serial, token)
                message = "页面已读取，请核对演出、场次与票档。"
                status = "needs_action" if page.data["blockers"] else "idle"
                if page.data["blockers"]:
                    message = page.data["blockers"][0]
                return self.update(serial, token, page=page.data, status=status, phase='idle', prepared=None, config=None, message=message)
            except Cancelled:
                return self.state(serial)

    def open_picker(self, serial, title, token=None):
        with self.lock:
            if self.active:
                raise RuntimeError("请先停止正在执行的演练。")
            token = self.token(serial) if token is None else token
            self.check(serial, token)
        with self.io:
            page = self.read(serial, token)
            if page.data["blockers"] or page.data["kind"] != "detail" or normalized(page.data["title"]) != normalized(title):
                raise RuntimeError("详情页已变化或有弹窗，请重新读取并确认演出。")
            self.tap(serial, token, page, "open_picker")
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                self.check(serial, token)
                self.cancel.wait(.08)
                page = self.read(serial, token)
                if page.data["kind"] == "picker" or page.data["blockers"]:
                    break
            status = "needs_action" if page.data["blockers"] else "idle"
            return self.update(serial, token, page=page.data, status=status, phase='idle', prepared=None, message="已打开选票页面，请选择准确场次和票档。" if page.data["kind"] == "picker" else "请先在手机处理提示，然后重新读取。")

    @staticmethod
    def config_key(config):
        return (tuple(normalized(config.get(key, '')) for key in ('eventTitle', 'session', 'tier'))
                + (config.get('quantity'), config.get('autoSubmit', False),
                   tuple(normalized(label) for label in config.get('buyerLabels', []))))

    def select_session(self, serial, title, session, token=None):
        token = self.token(serial) if token is None else token
        with self.lock:
            self.check(serial, token)
            if self.active:
                raise RuntimeError('请先停止当前任务。')
        with self.io:
            page = self.read(serial, token)
            if page.data['kind'] != 'picker' or page.data['blockers'] or normalized(page.data['title']) != normalized(title):
                raise RuntimeError('请先读取正式选票页面并确认演出。')
            choices = [c for c in page.data['sessions'] if normalized(c['label']) == normalized(session)]
            if len(choices) != 1 or not choices[0]['enabled']:
                raise RuntimeError('该场次尚不可选，请在手机确认当前开售状态。')
            self.update(serial, token, prepared=None, config=None)
            if not choices[0]['selected']:
                self.tap(serial, token, page, ('session', normalized(session)))
            if not choices[0]['selected'] or not page.data['tiers']:
                until = time.monotonic() + 3
                while time.monotonic() < until:
                    self.check(serial, token)
                    page = self.read(serial, token)
                    if page.data['blockers']:
                        return self.update(serial, token, page=page.data, status='needs_action', phase='handoff',
                                           message=page.data['blockers'][0])
                    if (normalized(page.data['title']) == normalized(title) and page.data['tiers']
                            and self.effect_observed(page, ('session', normalized(session)), None)):
                        break
                    self.cancel.wait(.08)
                else:
                    raise RuntimeError('未确认目标场次已切换并加载票档，请在手机检查后重新读取。')
            if not page.data['tiers']:
                raise RuntimeError('该场次票档尚未加载，请稍后重新读取。')
            return self.update(serial, token, page=page.data, status='idle', phase='idle',
                               message='已读取该场次票档，请选择目标价格后锁定并预热。')

    def prepare(self, serial, config, token=None):
        validate_config(config, int(time.time() * 1000))
        if config.get('startAt') is not None:
            raise ValueError('准备阶段不设置开始时间。')
        return self.launch(serial, config, token, preparing=True)

    def start(self, serial, config, token=None, prepared_id=None):
        return self.launch(serial, config, token, preparing=False, prepared_id=prepared_id)

    def launch(self, serial, config, token, preparing, prepared_id=None):
        validate_config(config, int(time.time() * 1000))
        with self.lock:
            token = self.token(serial) if token is None else token
            self.check(serial, token)
            if self.active or self.io.locked():
                raise RuntimeError('设备已有操作，请等待完成或先停止。')
            self.submission_guard.assert_clear(serial)
            current = self.state(serial).get('page')
            if not current or current['kind'] not in ('detail', 'picker') or current['blockers']:
                raise RuntimeError('请先读取手机上的正式演出详情或选票页面。')
            if normalized(current['title']) != normalized(config['eventTitle']):
                raise RuntimeError('演出与最近读取的页面不一致。')
            catalog = current if current['kind'] == 'picker' else self.state(serial).get('catalog') or {}
            if (normalized(catalog.get('title', '')) != normalized(config['eventTitle']) or any(
                    sum(normalized(c['label']) == normalized(config[key]) for c in catalog.get(options, [])) != 1
                    for key, options in (('session', 'sessions'), ('tier', 'tiers')))):
                raise RuntimeError('场次或票档尚未从手机读取，不能锁定，请重新读取该场票档。')
            prepared = self.state(serial).get('prepared')
            if not preparing and (not prepared or prepared.get('id') != prepared_id
                                  or self.config_key(prepared['config']) != self.config_key(config)):
                raise RuntimeError('目标尚未锁定或已经修改，请先锁定并预热。')
            self.active = serial
            self.task_ids[serial] = uuid.uuid4().hex
            self.submission_attempts.pop(serial, None)
            self.cancel.clear()
            status = 'preparing' if preparing else ('waiting' if config.get('startAt') else 'running')
            message = '正在预热和预选，停在进入确认页之前。' if preparing else ('已待命，保持手机解锁和当前页面。' if config.get('startAt') else '开始执行已锁定的目标。')
            result = self.update(serial, token, status=status, phase=status, prepared=None,
                                 config=copy.deepcopy(config), metrics={}, message=message)
            threading.Thread(target=self.run, args=(serial, token, copy.deepcopy(config), preparing), daemon=True).start()
            return result

    def ready(self, serial, token, config, began, readiness, verification='full'):
        plan = {'id': uuid.uuid4().hex, 'at': int(time.time() * 1000), 'readiness': readiness,
                'verification': verification, 'config': copy.deepcopy(dict(config, startAt=None))}
        metrics = {**self.state(serial)['metrics'], 'prepareMs': round((time.perf_counter() - began) * 1000, 1)}
        message = '已预选并锁定，执行时只推进剩余步骤。' if readiness == 'selected' else '目标已核对，等待正式开放；执行时仍会检查可售状态。'
        if verification == 'event_only':
            message = '演出已核对并预热；场次和票档待开售后逐项核对，不一致即停止。'
        self.update(serial, token, status='ready', phase='ready', prepared=plan, metrics=metrics, message=message)

    @staticmethod
    def effect_observed(page, action, before_quantity):
        data = page.data
        if action == 'open_picker':
            return data['kind'] == 'picker'
        if action == 'next':
            return data['kind'] in ('confirm', 'queue')
        if action == 'submit':
            return data['kind'] == 'payment'
        if action == 'select_alipay':
            return data['kind'] == 'confirm' and data.get('alipaySelected') is True
        if isinstance(action, tuple) and len(action) == 3 and action[0] == 'buyer':
            matches = [b for b in data.get('buyers', []) if normalized(b['label']) == action[1]]
            return (data['kind'] == 'confirm' and data.get('buyersComplete') is True
                    and len(matches) == 1 and matches[0]['selected'] is action[2])
        if data['kind'] != 'picker':
            return False
        if action in ('increase', 'decrease'):
            return before_quantity is not None and data['quantity'] == before_quantity + (1 if action == 'increase' else -1)
        if isinstance(action, tuple):
            choices = data['sessions' if action[0] == 'session' else 'tiers']
            loaded = bool(data['tiers']) if action[0] == 'session' else data['quantity'] is not None
            return loaded and sum(normalized(c['label']) == action[1] and c['selected'] for c in choices) == 1
        return False

    def run(self, serial, token, config, preparing=False):
        began = time.perf_counter()
        try:
            with self.submission_guard.operation(serial), self.io:
                self.submission_guard.assert_clear(serial)
                self.connect(serial, token)
                if preparing:
                    self.ocr.warmup()
                    self.check(serial, token)
                start_at = config.get('startAt')
                while start_at and time.time() * 1000 < start_at:
                    self.check(serial, token)
                    remaining = start_at / 1000 - time.time()
                    if remaining > .15:
                        self.foreground(self.devices[serial])
                    if self.cancel.wait(min(.5, max(0, start_at / 1000 - time.time()))):
                        raise Cancelled()
                trigger = time.perf_counter()
                if not preparing:
                    self.update(serial, token, phase='executing', status='running',
                                metrics={**self.state(serial)['metrics'], 'triggerLagMs': round(max(0, time.time() * 1000 - start_at), 1) if start_at else 0})
                deadline = time.monotonic() + config.get('timeoutSeconds', 120)
                pending_action = None
                before_quantity = None
                pending_until = 0
                actions = 0
                next_clicked = False
                submit_clicked = False
                buyer_snapshot = None
                while time.monotonic() < deadline:
                    self.check(serial, token)
                    page = self.read(serial, token, payment_expected=True) if submit_clicked else self.read(serial, token)
                    if time.monotonic() >= deadline:
                        raise RuntimeError('任务超时，已停止后续操作。')
                    if submit_clicked and page.data['kind'] == 'payment':
                        attempt_id = self.submission_attempts.get(serial)
                        if attempt_id:
                            self.submission_guard.mark_handoff(serial, attempt_id)
                    if page.data['kind'] == 'confirm' and page.data.get('buyersComplete'):
                        observed_buyers = tuple(normalized(b['label']) for b in page.data['buyers'])
                        if buyer_snapshot is not None and observed_buyers != buyer_snapshot:
                            raise RuntimeError('观演人列表或顺序已变化，已停止；不会按新位置换人。')
                        buyer_snapshot = observed_buyers
                    action, message = next_step(page, dict(config, autoSubmit=False) if preparing else config)
                    if page.data['blockers'] or action in ('confirm', 'payment'):
                        self.update(serial, token, page=page.data, status='needs_action', phase='handoff', message=message)
                        return
                    if pending_action:
                        if not self.effect_observed(page, pending_action, before_quantity):
                            if time.monotonic() >= pending_until:
                                raise RuntimeError('操作后未确认预期变化，已停止，避免重复点击。')
                            self.update(serial, token, page=page.data, message='已点击，正在等待页面完成切换。')
                            self.cancel.wait(.08)
                            continue
                        pending_action = None
                    self.update(serial, token, page=page.data, message=message)
                    if action == 'pause':
                        self.update(serial, token, status='needs_action', phase='handoff', message=message)
                        return
                    if preparing and action == 'next':
                        self.ready(serial, token, config, began, 'selected')
                        return
                    if action == 'wait':
                        if preparing:
                            if page.data['kind'] == 'detail' and page.data.get('purchaseAvailability') == 'upcoming':
                                self.ready(serial, token, config, began, 'waiting_sale', verification='event_only')
                                return
                            # A reservation page or an invisible tier cannot become a locked plan.
                            visible = page.data['kind'] == 'picker' and all(
                                sum(normalized(c['label']) == normalized(config[key]) for c in page.data[options]) == 1
                                for key, options in (('session', 'sessions'), ('tier', 'tiers')))
                            if not visible:
                                raise RuntimeError('尚不能核对完整场次和票档，请在开售前打开正式选票页面再准备。')
                            self.ready(serial, token, config, began, 'waiting_sale')
                            return
                        self.cancel.wait(.15)
                        continue
                    if action == 'next' and next_clicked:
                        raise RuntimeError('已经点击过进入确认页，请在手机检查结果；不会重复点击。')
                    if action == 'submit' and submit_clicked:
                        raise RuntimeError('订单已经提交过一次，请在手机检查订单和支付状态，不会再次提交。')
                    if actions >= 30:
                        raise RuntimeError('已达到动作上限，请在手机检查当前状态。')
                    tap_began = time.perf_counter()
                    if action == 'submit':
                        submit_clicked = True
                    self.tap(serial, token, page, action, deadline=deadline)
                    if not preparing and actions == 0:
                        self.update(serial, token, metrics={**self.state(serial)['metrics'], 'firstActionMs': round((time.perf_counter() - trigger) * 1000, 1)})
                    actions += 1
                    self.update(serial, token, metrics={**self.state(serial)['metrics'],
                                'tapMs': round((time.perf_counter() - tap_began) * 1000, 1), 'actionCount': actions})
                    pending_action = action
                    before_quantity = page.data['quantity']
                    next_clicked = next_clicked or action == 'next'
                    pending_until = time.monotonic() + (15 if action == 'submit' else 2.5)
                raise RuntimeError('任务超时，已停止后续操作，请检查手机当前页面。')
        except Cancelled:
            pass
        except ConnectionError as error:
            self.update_error(serial, token, 'interrupted', str(error))
        except RuntimeError as error:
            self.update_error(serial, token, 'needs_action', str(error))
        except Exception:
            self.update_error(serial, token, 'failed', '设备读取或页面操作失败，已停止。请检查 USB 和当前页面后重新读取。')
        finally:
            with self.lock:
                if not preparing and 'trigger' in locals():
                    try:
                        self.update(serial, token, metrics={**self.state(serial)['metrics'],
                                    'executionMs': round((time.perf_counter() - trigger) * 1000, 1)})
                    except Cancelled:
                        pass
                if self.active == serial:
                    self.active = None

    def update_error(self, serial, token, status, message):
        try:
            self.update(serial, token, status=status, phase="handoff", prepared=None, message=message)
        except Cancelled:
            pass

    def stop(self, serial):
        with self.lock:
            self.epochs[serial] = self.token(serial) + 1
            if self.active == serial:
                self.cancel.set()
            return self.update(serial, status="cancelled", phase="cancelled", prepared=None, message="已停止发出后续动作，请在手机核对当前状态。")

    def acknowledge_submission(self, serial, attempt_id, acknowledged, token=None):
        """User attests that orders were checked; this does not assert payment."""
        validate_acknowledgement(attempt_id, acknowledged)
        with self.lock:
            token = self.token(serial) if token is None else token
            self.check(serial, token)
            if self.active or not self.io.acquire(blocking=False):
                raise RuntimeError('设备仍有操作，停止并等待操作结束后才能确认订单记录。')
            try:
                with self.submission_guard.operation(serial):
                    self.submission_guard.acknowledge(serial, attempt_id, acknowledged)
                    self.submission_attempts.pop(serial, None)
                    # Invalidate queued actions and any previously prepared target.
                    self.epochs[serial] = token + 1
                    return self.update(serial, status='idle', phase='idle', prepared=None, config=None,
                                       message='已确认人工检查订单并解除限制；如需继续，请重新读取并锁定目标。')
            finally:
                self.io.release()

    def shutdown(self):
        with self.lock:
            self.closed = True
            self.cancel.set()
        for serial, device in list(self.devices.items()):
            try:
                device.stop_uiautomator()
                if serial not in self.preserved_jars:
                    device.adb_device.shell(["rm", "-f", "/data/local/tmp/u2.jar"])
            except Exception:
                pass
        with self.io:
            self.ocr.close()


def main():
    driver = Driver()
    output_lock = threading.Lock()
    operations = ThreadPoolExecutor(max_workers=1)

    def handle(request):
        request_id = request.get("requestId")
        serial = request.get("deviceId", "")
        try:
            if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", serial):
                raise ValueError("设备标识无效。")
            action = request.get("action")
            if action == "status":
                data = driver.state(serial)
            elif action == "stop":
                data = driver.stop(serial)
            elif action == 'acknowledge_submission':
                data = driver.acknowledge_submission(serial, request.get('attemptId'),
                                                     request.get('acknowledged'))
            elif action == "inspect":
                data = driver.inspect(serial, request.get("_queuedEpoch"))
            elif action == "open_picker":
                data = driver.open_picker(serial, request.get("eventTitle", ""), request.get("_queuedEpoch"))
            elif action == "select_session":
                data = driver.select_session(serial, request.get("eventTitle", ""), request.get("session", ""), request.get("_queuedEpoch"))
            elif action == "prepare":
                data = driver.prepare(serial, request.get("config", {}), request.get("_queuedEpoch"))
            elif action == "start":
                data = driver.start(serial, request.get("config"), request.get("_queuedEpoch"), request.get("preparedId"))
            else:
                raise ValueError("不支持的设备操作。")
            reply = {"requestId": request_id, "ok": True, "data": data}
        except Cancelled:
            reply = {"requestId": request_id, "ok": True, "data": driver.state(serial)}
        except (ValueError, RuntimeError, ConnectionError) as error:
            reply = {"requestId": request_id, "ok": False, "error": str(error).replace(serial, "[device]") if isinstance(serial, str) and serial else str(error)}
        except Exception:
            reply = {"requestId": request_id, "ok": False, "error": "手机通信失败，请检查设备连接、大麦前台和本地读取环境。"}
        with output_lock:
            print(json.dumps(reply, ensure_ascii=False), flush=True)

    try:
        for line in sys.stdin:
            try:
                request = json.loads(line)
                if not isinstance(request, dict) or not isinstance(request.get("requestId"), str):
                    raise ValueError()
            except (ValueError, json.JSONDecodeError):
                continue
            if request.get("action") in ("status", "stop", "acknowledge_submission"):
                handle(request)
            else:
                request["_queuedEpoch"] = driver.token(request.get("deviceId", ""))
                operations.submit(handle, request)
    finally:
        driver.shutdown()
        operations.shutdown(wait=True, cancel_futures=True)


if __name__ == "__main__":
    main()
