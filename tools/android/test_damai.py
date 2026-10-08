import unittest
from pathlib import Path
from damai import Page, PREFIX, next_step, validate_config


def node(rid, text="", children="", **attrs):
    from xml.sax.saxutils import escape
    values = {"resource-id": PREFIX + rid if rid else "", "text": text, "package": "cn.damai", "bounds": "[0,0][300,100]", "enabled": "true", **attrs}
    attributes = " ".join(f'{key}="{escape(str(value))}"' for key, value in values.items())
    return f'<node {attributes}>{children}</node>'


def picker(tag="", selected="false", extras=""):
    session = node("ll_perform_item", children=node("", "2026-10-18 周日 19:30") + node("tv_tag", tag), selected=selected)
    tier = node("ll_perform_item", children=node("", "看台380元") + node("tv_tag", tag), selected=selected)
    return Page('<hierarchy>' + node("tv_title", "测试演唱会") + node("project_detail_perform_flowlayout", children=node("", children=session)) + node("project_detail_perform_price_flowlayout", children=node("", children=tier)) + extras + '</hierarchy>')


def quantity_row(value="1张", minus="false", plus="true", extra="", rid="layout_sku_num_ll"):
    controls = node("img_jian", enabled=minus, clickable="true") + node("tv_num", value) + node("img_jia", enabled=plus, clickable="true") + extra
    return node("layout_num", children=node("tv_limit_num_title", "数量") + node(rid, children=controls))


CONFIG = {"eventTitle": "测试演唱会", "session": "2026-10-18 周日 19:30", "tier": "看台380元", "quantity": 1, "buyerLabels": [], "startAt": None, "timeoutSeconds": 120}


class PageTests(unittest.TestCase):
    def test_real_confirmation_is_read_natively_and_can_submit_only_when_opted_in(self):
        page = Page(Path(__file__).with_name('fixtures').joinpath('confirm.xml').read_text())
        config = dict(CONFIG, eventTitle='北京·2026周传雄「念念不忘•再遇见plus」演唱会-北京站',
                      session='2026-10-05 周一19:00', tier='内场980元')
        self.assertEqual(page.data['kind'], 'confirm')
        self.assertEqual(next_step(page, config)[0], 'confirm')
        self.assertEqual(next_step(page, dict(config, autoSubmit=True))[0], 'submit')
        for changed in ({'quantity': 2}, {'tier': '看台380元'}, {'session': '2026-10-04 周日19:00'}, {'eventTitle': '另一演出'}):
            self.assertEqual(next_step(page, dict(config, autoSubmit=True, **changed))[0], 'pause')
        page.data['selectedBuyerCount'] = 0
        self.assertEqual(next_step(page, dict(config, autoSubmit=True))[0], 'pause')

    def test_auto_submit_config_requires_an_actual_boolean(self):
        for value in ('true', 1, None):
            with self.assertRaises(ValueError):
                validate_config(dict(CONFIG, autoSubmit=value), 10_000)
        validate_config(dict(CONFIG, autoSubmit=True), 10_000)

    def test_reservation_page_is_not_a_purchase_picker(self):
        xml = '<hierarchy>' + node('title_tv', '王嘉尔北京站') + node('tv_perform_name', '预约想看场次') + node('project_detail_perform_flowlayout') + '</hierarchy>'
        page = Page(xml)
        self.assertEqual(page.data['kind'], 'reservation')
        self.assertEqual(page.data['title'], '王嘉尔北京站')
        self.assertEqual(next_step(page, CONFIG)[0], 'pause')
        self.assertEqual(page.targets, {})

    def test_sold_out_stops_instead_of_registering_or_upgrading(self):
        page = picker("缺货登记")
        self.assertEqual(page.data["kind"], "picker")
        self.assertFalse(page.data["tiers"][0]["enabled"])
        self.assertEqual(page.data["tiers"][0]["availability"], "sold_out")
        self.assertEqual(next_step(page, CONFIG)[0], "pause")

    def test_upcoming_options_wait_without_clicking_or_registering(self):
        for tag in ("未开售", "待开售", "开售提醒"):
            with self.subTest(tag=tag):
                page = picker(tag)
                self.assertEqual(page.data["sessions"][0]["availability"], "upcoming")
                self.assertEqual(page.data["tiers"][0]["availability"], "upcoming")
                self.assertFalse(page.data["tiers"][0]["enabled"])
                self.assertEqual(next_step(page, dict(CONFIG, startAt=50_000))[0], "wait")
        self.assertEqual(next_step(picker("缺货登记 开售提醒"), CONFIG)[0], "pause")

    def test_unknown_disabled_options_do_not_wait_as_presales(self):
        page = picker()
        page.data["sessions"][0].update(enabled=False, availability="unavailable")
        self.assertEqual(next_step(page, CONFIG)[0], "pause")

    def test_presale_and_registration_detail_buttons_are_never_click_targets(self):
        for label, decision in (("开售提醒", "wait"), ("待开售", "wait"), ("立即预约", "wait"), ("预约抢票", "wait"), ("缺货登记", "pause"), ('未知按钮', 'pause'), ('', 'pause')):
            page = Page('<hierarchy>' + node("info_v2_title_tv1", "测试演唱会") + node("trade_project_detail_purchase_status_bar_container_fl", children=node("", label)) + '</hierarchy>')
            self.assertFalse(page.data["canOpenPicker"])
            self.assertNotIn("open_picker", page.targets)
            self.assertEqual(next_step(page, CONFIG)[0], decision)

    def test_only_verified_purchase_detail_buttons_open_picker(self):
        for label in ('立即购买', '立即预订', '选票购买', '立即抢票'):
            page = Page('<hierarchy>' + node('info_v2_title_tv1', CONFIG['eventTitle']) + node('trade_project_detail_purchase_status_bar_container_fl', children=node('', label)) + '</hierarchy>')
            self.assertTrue(page.data['canOpenPicker'])
            self.assertEqual(next_step(page, CONFIG)[0], 'open_picker')

    def test_confirmation_never_produces_a_click(self):
        page = Page('<hierarchy>' + node("", "提交订单") + node("", "观演人") + '</hierarchy>')
        self.assertEqual(next_step(page, CONFIG)[0], "pause")
        self.assertEqual(page.targets, {})

    def test_different_event_stops(self):
        self.assertEqual(next_step(picker(), dict(CONFIG, eventTitle="别的演唱会"))[0], "pause")

    def test_measured_quantity_row_reads_and_adjusts_one_step_from_current_value(self):
        page = picker(selected="true", extras=quantity_row())
        self.assertEqual(page.data["quantity"], 1)
        self.assertNotIn("decrease", page.targets)
        self.assertIn("increase", page.targets)
        self.assertEqual(next_step(page, dict(CONFIG, quantity=2))[0], "increase")
        page = picker(selected="true", extras=quantity_row("2张", minus="true"))
        self.assertEqual(next_step(page, CONFIG)[0], "decrease")
        page = picker(selected="true", extras=quantity_row())
        page.data["nextLabel"] = "确定"
        page.targets["next"] = [1, 1, 10, 10]
        self.assertEqual(next_step(page, CONFIG)[0], "next")

    def test_quantity_is_not_guessed_from_duplicate_nodes_or_other_parent(self):
        for extras in (quantity_row(extra=node("tv_num", "2张")), quantity_row() + quantity_row(), quantity_row(rid="promotion_quantity")):
            page = picker(selected="true", extras=extras)
            self.assertIsNone(page.data["quantity"])
            self.assertNotIn("increase", page.targets)
            self.assertNotIn("decrease", page.targets)
            self.assertEqual(next_step(page, CONFIG)[0], "pause")
        for value in ("0张", "21张", "1", "1.5张", "张数1", "01张"):
            self.assertIsNone(picker(selected="true", extras=quantity_row(value)).data["quantity"])

    def test_quantity_buttons_require_unique_enabled_controls_in_the_measured_row(self):
        for extras in (quantity_row(plus="false"), quantity_row(extra=node("img_jia", enabled="true", clickable="true"))):
            page = picker(selected="true", extras=extras + node("img_jia", enabled="true", clickable="true"))
            self.assertEqual(page.data["quantity"], 1)
            self.assertNotIn("increase", page.targets)
            self.assertEqual(next_step(page, dict(CONFIG, quantity=2))[0], "pause")

    def test_session_only_picker_selects_session_before_requiring_tiers(self):
        cards = node("ll_perform_item", children=node("", "2026-10-17 周六 19:30")) + node("ll_perform_item", children=node("", "2026-10-18 周日 19:30"))
        page = Page('<hierarchy>' + node("tv_title", "测试演唱会") + node("project_detail_perform_flowlayout", children=cards) + '</hierarchy>')
        self.assertEqual(page.data["kind"], "picker")
        self.assertEqual(page.data["tiers"], [])
        self.assertEqual(page.data["blockers"], [])
        self.assertEqual(next_step(page, CONFIG)[0], ("session", "2026-10-18周日19:30"))
        page.data["sessions"][1]["selected"] = True
        self.assertEqual(next_step(page, CONFIG)[0], "pause")

    def test_remaining_stock_and_promotion_controls_cannot_be_ticket_quantity(self):
        extras = node("tv_remaining_num", "2") + node("promotion_reduce", "减一百") + node("reward_add_num", "5") + node("tv_quantity", "3")
        page = picker(selected="true", extras=extras)
        self.assertIsNone(page.data["quantity"])
        self.assertNotIn("decrease", page.targets)
        self.assertNotIn("increase", page.targets)
        self.assertEqual(next_step(page, CONFIG)[0], "pause")

    def test_ambiguous_target_is_never_clicked(self):
        page = picker()
        page.data["sessions"] *= 2
        self.assertEqual(next_step(page, CONFIG)[0], "pause")

    def test_ocr_outside_card_and_low_confidence_are_ignored(self):
        card = node("ll_perform_item")
        xml = '<hierarchy>' + node("project_detail_perform_price_flowlayout", children=node("", children=card)) + '</hierarchy>'
        observations = [{"text": "看台380元", "confidence": 1, "bounds": [400, 400, 500, 500]}, {"text": "看台380元", "confidence": .1, "bounds": [20, 20, 100, 50]}]
        self.assertEqual(Page(xml, observations).data["tiers"], [])

    def test_popup_and_risk_stop(self):
        dialog = Page('<hierarchy>' + node("damai_theme_dialog_confirm_btn", "确认并知悉") + '</hierarchy>')
        self.assertEqual(next_step(dialog, CONFIG)[0], "pause")
        risk = Page('<hierarchy>' + node("", "请完成安全验证") + '</hierarchy>')
        self.assertEqual(risk.data["kind"], "risk")

    def test_config_rejects_invalid_counts_and_past_timer(self):
        for quantity in (True, 0, 1.5, 21):
            with self.assertRaises(ValueError):
                validate_config(dict(CONFIG, quantity=quantity), 10_000)
        with self.assertRaises(ValueError):
            validate_config(dict(CONFIG, startAt=9_000), 10_000)
        validate_config(CONFIG, 10_000)


if __name__ == "__main__":
    unittest.main()
