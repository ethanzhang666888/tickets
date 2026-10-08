import threading
import time
import copy
import unittest
import tempfile
import io
import json
from pathlib import Path
from unittest.mock import MagicMock, patch, ANY
from driver import Driver, Cancelled, SingleAttemptDevice
from test_damai import CONFIG, picker, quantity_row, node
from test_buyers import CONFIRM_CONFIG, confirmation
from damai import Page, normalized


def selected_picker():
    page = picker(selected='true', extras=quantity_row())
    page.data['nextLabel'] = '确定'
    page.targets['next'] = [1, 1, 10, 10]
    return page


def seed_plan(driver, page=None):
    driver.update('test', page=(page or picker()).data, status='ready',
                  prepared={'id': 'plan', 'config': copy.deepcopy(CONFIG), 'readiness': 'selected'})


def wait_finished(driver):
    until = time.monotonic() + 2
    while driver.active and time.monotonic() < until:
        time.sleep(.005)
    if driver.active:
        raise AssertionError('Background operation did not complete')


class DriverTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def make_driver(self):
        return Driver(state_dir=self.directory.name)

    def submission_fixture(self):
        driver = self.make_driver()
        confirm = Page(Path(__file__).with_name('fixtures').joinpath('confirm.xml').read_text())
        config = dict(CONFIG, eventTitle='北京·2026周传雄「念念不忘•再遇见plus」演唱会-北京站',
                      session='2026-10-05 周一19:00', tier='内场980元', autoSubmit=True)
        driver.update('test', config=config)
        driver.devices['test'] = MagicMock()
        driver.observations['test'] = (confirm, 0, time.perf_counter())
        return driver, confirm, config

    def test_buyer_target_is_rechecked_before_click_and_effect_is_identity_based(self):
        driver = self.make_driver()
        page = confirmation()
        target = ('buyer', normalized(page.data['buyers'][0]['label']), True)
        driver.update('test', config=CONFIRM_CONFIG)
        driver.devices['test'] = MagicMock()
        driver.observations['test'] = (page, 0, time.perf_counter())
        driver.tap('test', 0, page, target)
        driver.devices['test'].click.assert_called_once()
        self.assertFalse(driver.effect_observed(page, target, None))
        self.assertTrue(driver.effect_observed(confirmation(selected=(0,)), target, None))
        self.assertFalse(driver.effect_observed(confirmation(selected=(1,)), target, None))
        self.assertFalse(driver.state('test')['submissionGuard']['blocked'])

    def test_custom_buyer_change_invalidates_prepared_target(self):
        driver = self.make_driver()
        seed_plan(driver)
        with self.assertRaisesRegex(RuntimeError, '目标尚未锁定'):
            driver.start('test', dict(CONFIG, buyerLabels=['测试甲']), prepared_id='plan')
        self.assertNotEqual(driver.config_key(CONFIG), driver.config_key(dict(CONFIG, buyerLabels=['测试甲'])))

    def test_buyer_run_chooses_then_stops_without_submission(self):
        driver = self.make_driver()
        config = copy.deepcopy(CONFIRM_CONFIG)
        driver.update('test', config=config)
        first, selected = confirmation(), confirmation(selected=(0,))
        with patch.object(driver, 'connect'), patch.object(driver, 'read', side_effect=[first, selected]), patch.object(driver, 'tap') as tap:
            driver.run('test', 0, config)
        tap.assert_called_once()
        self.assertEqual(tap.call_args.args[3], ('buyer', normalized(first.data['buyers'][0]['label']), True))
        self.assertEqual(driver.state('test')['page']['selectedBuyerCount'], 1)
        self.assertEqual(driver.state('test')['status'], 'needs_action')
        self.assertFalse(driver.state('test')['submissionGuard']['blocked'])

    def test_buyer_list_reorder_stops_instead_of_switching_default_person(self):
        driver = self.make_driver()
        driver.update('test', config=CONFIRM_CONFIG)
        first, changed = confirmation(), confirmation(selected=(0,))
        changed.data['buyers'].reverse()
        with patch.object(driver, 'connect'), patch.object(driver, 'read', side_effect=[first, changed]), patch.object(driver, 'tap') as tap:
            driver.run('test', 0, CONFIRM_CONFIG)
        tap.assert_called_once()
        self.assertIn('观演人列表', driver.state('test')['message'])
        self.assertEqual(driver.state('test')['status'], 'needs_action')

    def test_wrong_buyer_click_and_unconfirmed_effect_never_retry(self):
        driver = self.make_driver()
        page = confirmation()
        driver.update('test', config=CONFIRM_CONFIG)
        driver.devices['test'] = MagicMock()
        driver.observations['test'] = (page, 0, time.perf_counter())
        wrong = ('buyer', normalized(page.data['buyers'][1]['label']), True)
        with self.assertRaises(RuntimeError):
            driver.tap('test', 0, page, wrong)
        driver.devices['test'].click.assert_not_called()
        # Advance the operation deadline after the first click; no repeat is permitted.
        with patch.object(driver, 'connect'), patch.object(driver, 'read', return_value=page), patch.object(driver, 'tap') as tap, patch('driver.time.monotonic', side_effect=[0, 0, 0, 0, 3, 3, 3]):
            driver.run('test', 0, CONFIRM_CONFIG)
        tap.assert_called_once()
        self.assertIn('未确认预期变化', driver.state('test')['message'])

    def test_failed_submission_persistence_never_dispatches_click(self):
        driver, confirm, _ = self.submission_fixture()
        with patch('submission_guard.os.replace', side_effect=OSError('full disk')):
            with self.assertRaises(RuntimeError):
                driver.tap('test', 0, confirm, 'submit')
        driver.devices['test'].click.assert_not_called()
        self.assertTrue(driver.state('test')['submissionGuard']['blocked'])

    def test_lost_response_recreation_blocks_prepare_and_start_even_changed_target(self):
        driver, confirm, _ = self.submission_fixture()
        observed = []
        def lost_response(*_):
            observed.append(self.make_driver().state('test')['submissionGuard'])
            raise ConnectionError('response lost')
        driver.devices['test'].click.side_effect = lost_response
        with self.assertRaises(ConnectionError):
            driver.tap('test', 0, confirm, 'submit')
        self.assertTrue(observed[0]['blocked'])
        self.assertEqual(observed[0]['orderState'], 'unknown')
        replacement = self.make_driver()
        seed_plan(replacement)
        for config in (CONFIG, dict(CONFIG, tier='看台680元'), dict(CONFIG, autoSubmit=True)):
            with self.assertRaisesRegex(RuntimeError, '订单'):
                replacement.prepare('test', config)
            with self.assertRaisesRegex(RuntimeError, '订单'):
                replacement.start('test', config, prepared_id='plan')
        driver.devices['test'].click.assert_called_once()

    def test_status_inspect_and_stop_never_clear_submission_intent(self):
        driver, confirm, _ = self.submission_fixture()
        driver.tap('test', 0, confirm, 'submit')
        intent = driver.state('test')['submissionGuard']
        driver.stop('test')
        with patch.object(driver, 'read', return_value=picker()):
            driver.inspect('test')
        self.assertEqual(driver.state('test')['submissionGuard'], intent)
        for attempt, acknowledged in [('2' * 32, True), (intent['attemptId'], False), (intent['attemptId'], 1)]:
            with self.assertRaises((ValueError, RuntimeError)):
                driver.acknowledge_submission('test', attempt, acknowledged)
        driver.active = 'test'
        with self.assertRaises(RuntimeError):
            driver.acknowledge_submission('test', intent['attemptId'], True)
        driver.active = None
        with driver.io:
            with self.assertRaises(RuntimeError):
                driver.acknowledge_submission('test', intent['attemptId'], True)
        state = driver.acknowledge_submission('test', intent['attemptId'], True)
        self.assertFalse(state['submissionGuard']['blocked'])
        self.assertIsNone(state['prepared'])
        self.assertIsNone(state['config'])

    def test_stop_during_intent_persistence_prevents_dispatch_and_keeps_fence(self):
        driver, confirm, _ = self.submission_fixture()
        persist = driver.submission_guard.begin
        def stopped_before_dispatch(*args):
            intent = persist(*args)
            driver.stop('test')
            return intent
        with patch.object(driver.submission_guard, 'begin', side_effect=stopped_before_dispatch):
            with self.assertRaises(Cancelled):
                driver.tap('test', 0, confirm, 'submit')
        driver.devices['test'].click.assert_not_called()
        self.assertTrue(self.make_driver().state('test')['submissionGuard']['blocked'])

    def test_stop_during_submit_keeps_fence_and_denies_acknowledgement(self):
        driver, confirm, _ = self.submission_fixture()
        started, release = threading.Event(), threading.Event()
        errors = []
        def inflight(*_):
            started.set()
            release.wait(2)
        driver.devices['test'].click.side_effect = inflight
        def submit():
            with driver.io:
                try:
                    driver.tap('test', 0, confirm, 'submit')
                except Cancelled as error:
                    errors.append(error)
        worker = threading.Thread(target=submit)
        worker.start()
        try:
            self.assertTrue(started.wait(1))
            state = driver.stop('test')
            self.assertTrue(state['submissionGuard']['blocked'])
            with self.assertRaises(RuntimeError):
                driver.acknowledge_submission('test', state['submissionGuard']['attemptId'], True)
        finally:
            release.set()
            worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(len(errors), 1)
        self.assertTrue(self.make_driver().state('test')['submissionGuard']['blocked'])

    def test_other_process_operation_prevents_acknowledgement(self):
        driver, confirm, _ = self.submission_fixture()
        driver.tap('test', 0, confirm, 'submit')
        attempt = driver.state('test')['submissionGuard']['attemptId']
        second = self.make_driver()
        with driver.submission_guard.operation('test'):
            with self.assertRaises(RuntimeError):
                second.acknowledge_submission('test', attempt, True)
        self.assertTrue(second.state('test')['submissionGuard']['blocked'])

    def test_submission_fsync_failure_never_dispatches_click(self):
        driver, confirm, _ = self.submission_fixture()
        with patch('submission_guard.os.fsync', side_effect=OSError('sync failure')):
            with self.assertRaises(RuntimeError):
                driver.tap('test', 0, confirm, 'submit')
        driver.devices['test'].click.assert_not_called()

    def test_new_intent_rejects_old_acknowledgement_and_queued_actions(self):
        driver, confirm, _ = self.submission_fixture()
        driver.tap('test', 0, confirm, 'submit')
        first = driver.state('test')['submissionGuard']['attemptId']
        old_token = driver.token('test')
        driver.acknowledge_submission('test', first, True)
        with self.assertRaises(Cancelled):
            driver.inspect('test', old_token)
        second = driver.submission_guard.begin('test', '2' * 32)
        with self.assertRaises(RuntimeError):
            driver.acknowledge_submission('test', first, True)
        self.assertEqual(driver.state('test')['submissionGuard']['attemptId'], second['attemptId'])
        self.assertTrue(driver.state('test')['submissionGuard']['blocked'])

    def test_corrupt_store_blocks_launch_without_device_io(self):
        Path(self.directory.name, 'submissions.json').write_text('{corrupt')
        driver = self.make_driver()
        seed_plan(driver)
        with patch.object(driver, 'connect') as connect:
            with self.assertRaises(RuntimeError):
                driver.prepare('test', CONFIG)
            with self.assertRaises(RuntimeError):
                driver.start('test', CONFIG, prepared_id='plan')
            connect.assert_not_called()
        self.assertEqual(driver.state('test')['submissionGuard']['status'], 'error')

    def test_jsonl_dispatch_validates_acknowledgement_without_device_io(self):
        driver = self.make_driver()
        intent = driver.submission_guard.begin('test', '1' * 32)
        requests = [dict(requestId=str(i), action='acknowledge_submission', deviceId='test',
                         attemptId=attempt, acknowledged=ack)
                    for i, (attempt, ack) in enumerate([(None, True), ('bad', True),
                                                       (intent['attemptId'], 'true'),
                                                       (intent['attemptId'], True)])]
        requests[-1]['_queuedEpoch'] = -1  # Client input must not spoof internal cancellation tokens.
        requests.insert(0, dict(requestId='invalid-device', action='acknowledge_submission',
                                deviceId=['test'], attemptId=intent['attemptId'], acknowledged=True))
        output = io.StringIO()
        from driver import main
        with patch('driver.Driver', return_value=driver), patch.object(driver, 'shutdown'), \
                patch('driver.sys.stdin', io.StringIO('\n'.join(json.dumps(r) for r in requests))), \
                patch('driver.sys.stdout', output):
            main()
        replies = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([reply['ok'] for reply in replies], [False, False, False, False, True])
        self.assertFalse(replies[-1]['data']['submissionGuard']['blocked'])

    def test_matching_idle_jar_is_reused_and_preserved(self):
        driver = self.make_driver()
        ad, device = MagicMock(), MagicMock()
        ad.serial = 'test'
        ad.shell2.return_value.returncode = 0
        import hashlib
        import uiautomator2
        digest = hashlib.sha256((Path(uiautomator2.__file__).parent / 'assets/u2.jar').read_bytes()).hexdigest()
        ad.shell.side_effect = ['PID ARGS\n1 /init', digest + '  /data/local/tmp/u2.jar', 'versionName=9.0.35']
        with patch('driver.adbutils.adb.device_list', return_value=[ad]), patch('driver.adbutils.adb.device', return_value=ad), patch('driver.SingleAttemptDevice', return_value=device) as connect:
            driver.connect('test', 0)
            connect.assert_called_once_with(ad, preserve_jar=True)
            driver.shutdown()
        device.stop_uiautomator.assert_called_once()
        device.adb_device.shell.assert_not_called()

    def test_foreign_or_active_jar_is_never_started_or_overwritten(self):
        for outputs in (['PID ARGS\n42 com.wetest.uia2.Main'], ['PID ARGS\n1 /init', 'different-hash']):
            driver = self.make_driver()
            ad = MagicMock(serial='test')
            ad.shell2.return_value.returncode = 0
            ad.shell.side_effect = outputs
            with patch('driver.adbutils.adb.device_list', return_value=[ad]), patch('driver.adbutils.adb.device', return_value=ad), patch('driver.SingleAttemptDevice') as connect:
                with self.assertRaises(RuntimeError): driver.connect('test', 0)
                connect.assert_not_called()

    def test_payment_opt_in_is_locked_and_stops_after_one_submission(self):
        driver = self.make_driver()
        confirm = Page(Path(__file__).with_name('fixtures').joinpath('confirm.xml').read_text())
        config = dict(CONFIG, eventTitle='北京·2026周传雄「念念不忘•再遇见plus」演唱会-北京站',
                      session='2026-10-05 周一19:00', tier='内场980元', autoSubmit=True)
        page = selected_picker()
        page.data['title'] = config['eventTitle']
        page.data['sessions'][0]['label'] = config['session']
        page.data['tiers'][0]['label'] = config['tier']
        payment = Page('<hierarchy/>')
        payment.data['kind'] = 'payment'
        driver.update('test', page=page.data, prepared={'id': 'plan', 'config': config})
        with self.assertRaisesRegex(RuntimeError, '锁定'):
            driver.start('test', dict(config, autoSubmit=False), prepared_id='plan')
        driver.devices['test'] = MagicMock()
        frames = iter([page, confirm, confirm, payment])
        def read_frame(*args, **kwargs):
            frame = next(frames)
            driver.observations['test'] = (frame, 0, time.perf_counter())
            return frame
        with patch.object(driver, 'connect'), patch.object(driver, 'read', side_effect=read_frame), patch.object(driver, 'tap', wraps=driver.tap) as tap:
            driver.start('test', config, prepared_id='plan')
            wait_finished(driver)
        self.assertEqual([c.args[3] for c in tap.call_args_list], ['next', 'submit'])
        self.assertEqual(driver.state('test')['page']['kind'], 'payment')
        self.assertEqual(driver.state('test')['status'], 'needs_action')
        guard = driver.state('test')['submissionGuard']
        self.assertTrue(guard['blocked'])
        self.assertEqual(guard['orderState'], 'alipay_handoff')
        self.assertEqual(self.make_driver().state('test')['submissionGuard'], guard)
        with self.assertRaisesRegex(RuntimeError, '订单'):
            driver.prepare('test', config)
        driver.acknowledge_submission('test', guard['attemptId'], True)
        self.assertFalse(self.make_driver().state('test')['submissionGuard']['blocked'])

    def test_alipay_is_only_an_allowed_handoff_after_submission(self):
        driver = self.make_driver()
        device = MagicMock()
        device.info = {'currentPackageName': 'com.eg.android.AlipayGphone', 'screenOn': True}
        with self.assertRaises(ConnectionError):
            driver.foreground(device)
        with patch.object(driver, 'connect', return_value=device):
            page = driver.read('test', 0, payment_expected=True)
        self.assertEqual(page.data['kind'], 'payment')
        device.dump_hierarchy.assert_not_called()
        device.click.assert_not_called()

    def test_stop_during_inflight_read_prevents_late_click(self):
        driver = self.make_driver()
        reading, release = threading.Event(), threading.Event()
        page = picker()
        seed_plan(driver, page)
        def delayed_read(*_):
            reading.set()
            release.wait(2)
            return page
        with patch.object(driver, "connect"), patch.object(driver, "read", side_effect=delayed_read), patch.object(driver, "tap") as tap:
            driver.start("test", CONFIG, prepared_id='plan')
            self.assertTrue(reading.wait(1))
            stopped = driver.stop("test")
            self.assertEqual(stopped["status"], "cancelled")
            release.set()
            # Waiting for io proves the background worker has left its operation section.
            with driver.io:
                pass
            self.assertEqual(driver.state("test")["status"], "cancelled")
            tap.assert_not_called()

    def test_cancel_invalidates_queued_operation(self):
        driver = self.make_driver()
        old_token = driver.token("test")
        driver.stop("test")
        with patch.object(driver, "read") as read:
            with self.assertRaises(Cancelled):
                driver.inspect("test", old_token)
            read.assert_not_called()

    def test_status_does_not_touch_device(self):
        driver = self.make_driver()
        with patch.object(driver, "connect") as connect:
            self.assertEqual(driver.state("test")["status"], "idle")
            connect.assert_not_called()

    def test_prepare_preselects_then_stops_before_confirmation(self):
        driver = self.make_driver()
        driver.update('test', page=picker().data)
        with patch.object(driver, 'connect'), patch.object(driver.ocr, 'warmup') as warm, \
                patch.object(driver, 'read', side_effect=[picker(), selected_picker()]), \
                patch.object(driver, 'tap') as tap:
            driver.prepare('test', CONFIG)
            wait_finished(driver)
        state = driver.state('test')
        self.assertEqual(state['status'], 'ready')
        self.assertEqual(state['prepared']['readiness'], 'selected')
        self.assertEqual(state['prepared']['config'], CONFIG)
        self.assertTrue(state['prepared']['id'])
        self.assertIn('prepareMs', state['metrics'])
        self.assertEqual(tap.call_count, 1)
        self.assertEqual(tap.call_args.args[-1][0], 'session')
        warm.assert_called_once()

    def test_start_rejects_missing_changed_or_cancelled_plan(self):
        driver = self.make_driver()
        seed_plan(driver)
        for config, plan_id in ((CONFIG, None), (CONFIG, 'other'), (dict(CONFIG, quantity=2), 'plan'),
                                (dict(CONFIG, tier='看台680元'), 'plan')):
            with self.assertRaisesRegex(RuntimeError, '锁定'):
                driver.start('test', config, prepared_id=plan_id)
        driver.stop('test')
        with self.assertRaisesRegex(RuntimeError, '锁定'):
            driver.start('test', CONFIG, prepared_id='plan')

    def test_execution_consumes_plan_never_warms_and_stops_at_confirm(self):
        driver = self.make_driver()
        page = selected_picker()
        seed_plan(driver, page)
        confirm = Page('<hierarchy>' + node('', '提交订单') + node('', '观演人') + '</hierarchy>')
        with patch.object(driver, 'connect'), patch.object(driver.ocr, 'warmup') as warm, \
                patch.object(driver, 'read', side_effect=[page, confirm]), patch.object(driver, 'tap') as tap:
            driver.start('test', CONFIG, prepared_id='plan')
            wait_finished(driver)
        state = driver.state('test')
        self.assertEqual(state['status'], 'needs_action')
        self.assertEqual(state['page']['kind'], 'confirm')
        self.assertIsNone(state['prepared'])
        tap.assert_called_once_with('test', 0, page, 'next', deadline=ANY)
        warm.assert_not_called()
        self.assertIn('firstActionMs', state['metrics'])

    def test_reservation_can_never_be_prepared(self):
        driver = self.make_driver()
        page = Page('<hierarchy>' + node('', '预约想看场次') + '</hierarchy>')
        driver.update('test', page=page.data)
        with patch.object(driver, 'connect') as connect:
            with self.assertRaisesRegex(RuntimeError, '正式'):
                driver.prepare('test', CONFIG)
            connect.assert_not_called()

    def test_presale_detail_prepares_event_only_without_claiming_ticket_validation(self):
        driver = self.make_driver()
        page = Page('<hierarchy>' + node('info_v2_title_tv1', CONFIG['eventTitle']) + node('trade_project_detail_purchase_status_bar_container_fl', children=node('', '已预约')) + '</hierarchy>')
        driver.update('test', page=page.data, catalog=picker(selected='true').data)
        with patch.object(driver, 'connect'), patch.object(driver.ocr, 'warmup'), patch.object(driver, 'read', return_value=page), patch.object(driver, 'tap') as tap:
            driver.prepare('test', CONFIG)
            wait_finished(driver)
        state = driver.state('test')
        self.assertEqual(state['status'], 'ready')
        self.assertEqual(state['prepared']['readiness'], 'waiting_sale')
        self.assertEqual(state['prepared']['verification'], 'event_only')
        self.assertIn('开售后', state['message'])
        tap.assert_not_called()

    def test_presale_execution_rechecks_ticket_after_opening_and_never_upgrades(self):
        driver = self.make_driver()
        presale = Page('<hierarchy>' + node('info_v2_title_tv1', CONFIG['eventTitle']) + node('trade_project_detail_purchase_status_bar_container_fl', children=node('', '已预约')) + '</hierarchy>')
        onsale = Page('<hierarchy>' + node('info_v2_title_tv1', CONFIG['eventTitle']) + node('trade_project_detail_purchase_status_bar_container_fl', children=node('', '立即购买')) + '</hierarchy>')
        driver.update('test', page=presale.data, catalog=picker(selected='true').data, status='ready', prepared={'id': 'plan', 'config': CONFIG, 'readiness': 'waiting_sale', 'verification': 'event_only'})
        unavailable = picker(tag='缺货')
        with patch.object(driver, 'connect'), patch.object(driver, 'read', side_effect=[presale, onsale, unavailable]), patch.object(driver, 'tap') as tap:
            driver.start('test', CONFIG, prepared_id='plan')
            wait_finished(driver)
        self.assertEqual(driver.state('test')['status'], 'needs_action')
        self.assertIn('无票', driver.state('test')['message'])
        self.assertEqual(tap.call_count, 1)
        self.assertEqual(tap.call_args.args[-1], 'open_picker')

    def test_scheduled_execution_never_clicks_before_target_time_or_rewarms(self):
        driver = self.make_driver()
        page = selected_picker()
        seed_plan(driver, page)
        device = MagicMock()
        device.info = {'currentPackageName': 'cn.damai', 'screenOn': True}
        driver.devices['test'] = device
        confirm = Page('<hierarchy>' + node('', '提交订单') + node('', '观演人') + '</hierarchy>')
        start_at = int(time.time() * 1000) + 200
        observed_times = []
        with patch.object(driver, 'connect'), patch.object(driver.ocr, 'warmup') as warm, \
                patch.object(driver, 'read', side_effect=[page, confirm]), \
                patch.object(driver, 'tap', side_effect=lambda *a, **k: observed_times.append(time.time() * 1000)):
            driver.start('test', dict(CONFIG, startAt=start_at), prepared_id='plan')
            wait_finished(driver)
        self.assertEqual(len(observed_times), 1)
        self.assertGreaterEqual(observed_times[0], start_at)
        self.assertEqual(driver.state('test')['page']['kind'], 'confirm')
        warm.assert_not_called()

    def test_stop_cancels_scheduled_execution_without_any_device_action(self):
        driver = self.make_driver()
        seed_plan(driver)
        device = MagicMock()
        device.info = {'currentPackageName': 'cn.damai', 'screenOn': True}
        driver.devices['test'] = device
        with patch.object(driver, 'connect'), patch.object(driver, 'read') as read, patch.object(driver, 'tap') as tap:
            driver.start('test', dict(CONFIG, startAt=int(time.time() * 1000) + 5000), prepared_id='plan')
            driver.stop('test')
            wait_finished(driver)
        self.assertEqual(driver.state('test')['status'], 'cancelled')
        read.assert_not_called()
        tap.assert_not_called()

    def test_fresh_owned_frame_clicks_once_without_second_device_read(self):
        driver = self.make_driver()
        device = MagicMock()
        driver.devices['test'] = device
        page = selected_picker()
        driver.observations['test'] = (page, 0, time.perf_counter())
        with patch.object(driver, 'read') as read:
            driver.tap('test', 0, page, 'next')
            read.assert_not_called()
        device.click.assert_called_once_with(5, 5)

    def test_stale_frame_is_rechecked_before_click(self):
        driver = self.make_driver()
        driver.devices['test'] = MagicMock()
        page = selected_picker()
        driver.observations['test'] = (page, 0, time.perf_counter() - 1)
        with patch.object(driver, 'read', return_value=page) as read:
            driver.tap('test', 0, page, 'next')
            read.assert_called_once()

    def test_slow_recheck_cannot_click_after_deadline(self):
        driver = self.make_driver()
        device = MagicMock()
        driver.devices['test'] = device
        page = selected_picker()
        with patch.object(driver, 'read', return_value=page), patch('driver.time.monotonic', side_effect=[1, 3]):
            with self.assertRaisesRegex(RuntimeError, '超时'):
                driver.tap('test', 0, page, 'next', deadline=2)
        device.click.assert_not_called()

    def test_reservation_read_uses_rpc_without_screenshot_or_slow_shell(self):
        driver = self.make_driver()
        device = MagicMock()
        device.info = {'currentPackageName': 'cn.damai', 'screenOn': True}
        device.dump_hierarchy.return_value = '<hierarchy>' + node('', '预约想看场次') + '</hierarchy>'
        with patch.object(driver, 'connect', return_value=device):
            page = driver.read('test', 0)
        self.assertEqual(page.data['kind'], 'reservation')
        device.app_current.assert_not_called()
        device.screenshot.assert_not_called()
        self.assertEqual(driver.state('test')['metrics']['ocrMs'], 0)

    def test_unknown_transition_frame_is_tolerated_but_no_second_click(self):
        driver = self.make_driver()
        page = selected_picker()
        seed_plan(driver, page)
        unknown = Page('<hierarchy/>')
        confirm = Page('<hierarchy>' + node('', '提交订单') + node('', '观演人') + '</hierarchy>')
        with patch.object(driver, 'connect'), patch.object(driver, 'read', side_effect=[page, unknown, page, confirm]), \
                patch.object(driver, 'tap') as tap:
            driver.start('test', CONFIG, prepared_id='plan')
            wait_finished(driver)
        self.assertEqual(driver.state('test')['page']['kind'], 'confirm')
        self.assertEqual(tap.call_count, 1)
        messages = [entry['message'] for entry in driver.state('test')['logs']]
        self.assertFalse(any('无法继续' in message for message in messages))
        self.assertIn('executionMs', driver.state('test')['metrics'])

    def test_selected_session_with_loading_tiers_waits_without_tapping_again(self):
        driver = self.make_driver()
        loading = selected_picker()
        loading.data['tiers'] = []
        with patch.object(driver, 'read', side_effect=[loading, selected_picker()]), patch.object(driver, 'tap') as tap:
            state = driver.select_session('test', CONFIG['eventTitle'], CONFIG['session'])
        self.assertTrue(state['page']['tiers'])
        tap.assert_not_called()

    def test_unrelated_tier_loading_cannot_repeat_quantity_increment(self):
        driver = self.make_driver()
        page = selected_picker()
        later = copy.deepcopy(page)
        later.data['tiers'].append({'label': '看台680元', 'selected': False, 'enabled': True, 'availability': 'available'})
        finished = copy.deepcopy(later)
        finished.data['quantity'] = 2
        config = dict(CONFIG, quantity=2)
        driver.update('test', page=page.data)
        with patch.object(driver, 'connect'), patch.object(driver.ocr, 'warmup'), \
                patch.object(driver, 'read', side_effect=[page, later, finished]), patch.object(driver, 'tap') as tap:
            driver.prepare('test', config)
            wait_finished(driver)
        self.assertEqual(driver.state('test')['status'], 'ready')
        self.assertEqual(tap.call_count, 1)
        self.assertEqual(tap.call_args.args[-1], 'increase')

    def test_select_session_waits_for_selected_target_not_old_tiers(self):
        driver = self.make_driver()
        before = picker()
        after = selected_picker()
        with patch.object(driver, 'read', side_effect=[before, before, after]) as read, patch.object(driver, 'tap') as tap:
            state = driver.select_session('test', CONFIG['eventTitle'], CONFIG['session'])
        self.assertTrue(state['page']['sessions'][0]['selected'])
        self.assertEqual(read.call_count, 3)
        self.assertEqual(tap.call_count, 1)

    def test_prepare_waits_for_tiers_loading_after_session_selection(self):
        driver = self.make_driver()
        before = picker()
        loading = selected_picker()
        loading.data['tiers'] = []
        driver.update('test', page=before.data)
        with patch.object(driver, 'connect'), patch.object(driver.ocr, 'warmup'), \
                patch.object(driver, 'read', side_effect=[before, loading, selected_picker()]), patch.object(driver, 'tap') as tap:
            driver.prepare('test', CONFIG)
            wait_finished(driver)
        self.assertEqual(driver.state('test')['status'], 'ready')
        self.assertEqual(tap.call_count, 1)

    def test_stopping_other_device_does_not_cancel_active_task(self):
        driver = self.make_driver()
        driver.active = "phone-a"
        driver.stop("phone-b")
        self.assertFalse(driver.cancel.is_set())
        self.assertEqual(driver.token("phone-a"), 0)

    def test_rpc_failure_does_not_repeat_uncertain_click(self):
        device = object.__new__(SingleAttemptDevice)
        device._dev = object()
        device._device_server_port = 9008
        with patch('driver._jsonrpc_call', side_effect=RuntimeError("response lost")) as rpc:
            with self.assertRaises(RuntimeError):
                device.jsonrpc_call("click", [100, 200])
            self.assertEqual(rpc.call_count, 1)

    def test_same_bounds_with_different_tier_never_clicks(self):
        driver = self.make_driver()
        device = MagicMock()
        device.app_current.return_value = {"package": "cn.damai"}
        driver.devices["test"] = device
        before = picker()
        after = picker()
        after.data["tiers"][0]["label"] = "看台680元"
        target = next(key for key in before.targets if key[0] == "tier")
        with patch.object(driver, "read", return_value=after):
            with self.assertRaises(RuntimeError):
                driver.tap("test", 0, before, target)
        device.click.assert_not_called()

    def test_constructor_failure_cleans_up_owned_service(self):
        device = MagicMock()
        def partial_start(instance, _):
            instance._process = object()
            raise RuntimeError("startup interrupted")
        with patch("driver.u2.Device.__init__", partial_start), \
                patch.object(SingleAttemptDevice, "stop_uiautomator") as stop:
            with self.assertRaises(RuntimeError):
                SingleAttemptDevice(device)
        stop.assert_called_once_with(wait=False)
        device.shell.assert_called_once_with(["rm", "-f", "/data/local/tmp/u2.jar"], timeout=5)

    def test_connection_finishing_after_eof_cleans_up_its_new_service(self):
        driver = self.make_driver()
        connecting, release = threading.Event(), threading.Event()
        ad = MagicMock()
        ad.serial = "test"
        ad.shell2.return_value.returncode = 1
        ad.shell.return_value = "versionName=9.0.35"
        device = MagicMock()
        errors = []

        def delayed_connection(_):
            connecting.set()
            release.wait(2)
            return device

        def connect():
            try:
                driver.connect("test", driver.token("test"))
            except Exception as error:
                errors.append(error)

        with patch("driver.adbutils.adb.device_list", return_value=[ad]), \
                patch("driver.adbutils.adb.device", return_value=ad), \
                patch("driver.SingleAttemptDevice", side_effect=delayed_connection):
            worker = threading.Thread(target=connect)
            worker.start()
            try:
                self.assertTrue(connecting.wait(1))
                driver.shutdown()
            finally:
                release.set()
                worker.join(2)

        self.assertFalse(worker.is_alive())
        self.assertEqual(len(errors), 1)
        self.assertIsInstance(errors[0], Cancelled)
        self.assertNotIn("test", driver.devices)
        device.stop_uiautomator.assert_called_once_with()
        ad.shell.assert_any_call(["rm", "-f", "/data/local/tmp/u2.jar"], timeout=5)
        device.jsonrpc.setConfigurator.assert_not_called()


if __name__ == "__main__":
    unittest.main()
