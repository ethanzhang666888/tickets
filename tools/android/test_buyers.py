"""Attendee selection uses complete native rows, never OCR names or stale indexes."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

from damai import Page, PREFIX, next_step, normalized, validate_config
from test_damai import CONFIG

CONFIRM_CONFIG = dict(CONFIG, eventTitle='北京·2026周传雄「念念不忘•再遇见plus」演唱会-北京站',
                      session='2026-10-05 周一19:00', tier='内场980元')


def confirmation(selected=(), quantity=1, mutate=None):
    root = ET.fromstring(Path(__file__).with_name('fixtures').joinpath('confirm.xml').read_text())
    names = [n for n in root.iter('node') if n.get('resource-id') == PREFIX + 'text_name']
    for n, name in zip(names, ('测试甲', '测试乙', '测试丙')):
        n.set('text', name)
    checks = [n for n in root.iter('node') if n.get('resource-id') == PREFIX + 'checkbox']
    for i, check in enumerate(checks):
        check.set('checked', 'true' if i in selected else 'false')
    for n in root.iter('node'):
        if n.get('text') == '×1张':
            n.set('text', f'×{quantity}张')
        if n.get('text') == '¥980':
            n.set('text', f'¥{980 * quantity}')
    if mutate:
        mutate(root)
    return Page(ET.tostring(root, encoding='unicode'))


def by_id(root, rid):
    return [n for n in root.iter('node') if n.get('resource-id') == PREFIX + rid]


class BuyerTests(unittest.TestCase):
    def test_default_one_and_two_choose_first_rows(self):
        for quantity in (1, 2):
            page = confirmation(quantity=quantity)
            self.assertTrue(page.data['buyersComplete'])
            first = page.data['buyers'][0]
            self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, quantity=quantity))[0],
                             ('buyer', normalized(first['label']), True))
        page = confirmation(selected=(0,), quantity=2)
        self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, quantity=2))[0],
                         ('buyer', normalized(page.data['buyers'][1]['label']), True))

    def test_wrong_selected_row_is_unchecked_before_selecting_target(self):
        page = confirmation(selected=(2,))
        self.assertEqual(next_step(page, CONFIRM_CONFIG)[0],
                         ('buyer', normalized(page.data['buyers'][2]['label']), False))

    def test_custom_names_and_display_labels_choose_exactly_and_never_fallback(self):
        page = confirmation()
        for label in ('测试乙', page.data['buyers'][1]['label']):
            action = next_step(page, dict(CONFIRM_CONFIG, buyerLabels=[label]))[0]
            self.assertEqual(action, ('buyer', normalized(page.data['buyers'][1]['label']), True))
        for labels in (['不存在'], ['测试甲', '测试乙']):
            self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, buyerLabels=labels))[0], 'pause')

    def test_all_desired_selected_then_manual_stop_or_submit(self):
        page = confirmation(selected=(0,))
        self.assertEqual(next_step(page, CONFIRM_CONFIG)[0], 'confirm')
        self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, autoSubmit=True))[0], 'submit')
        page.data['selectedBuyerCount'] = 0
        self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, autoSubmit=True))[0], 'pause')

    def test_two_custom_people_cannot_alias_the_same_row(self):
        page = confirmation(quantity=2)
        labels = ['测试甲', page.data['buyers'][0]['label']]
        self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, quantity=2, buyerLabels=labels))[0], 'pause')

    def test_missing_disabled_duplicate_or_incomplete_rows_stop(self):
        mutations = [
            lambda r: by_id(r, 'text_name')[1].set('text', ''),
            lambda r: by_id(r, 'text_name')[1].set('text', '测试甲'),
            lambda r: by_id(r, 'checkbox')[0].set('enabled', 'false'),
            lambda r: by_id(r, 'checkbox')[0].set('visible-to-user', 'false'),
            lambda r: by_id(r, 'recycler_main')[0].set('scrollable', 'true'),
            lambda r: by_id(r, 'recycler_main')[0][0].set('index', '1'),
            lambda r: by_id(r, 'text_num')[0].set('text', '110101199001011234'),
            lambda r: by_id(r, 'text_num_type')[0].set('text', ''),
            lambda r: by_id(r, 'recycler_main')[0].set('bounds', '[0,843][1080,3500]'),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                page = confirmation(mutate=mutate)
                self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, autoSubmit=True))[0], 'pause')
                self.assertEqual(next_step(page, CONFIRM_CONFIG)[0], 'pause')
        self.assertEqual(next_step(confirmation(quantity=4), dict(CONFIRM_CONFIG, quantity=4))[0], 'pause')

    def test_unique_mask_disambiguates_names_but_name_alone_cannot(self):
        def same_name(root):
            by_id(root, 'text_name')[1].set('text', '测试甲')
            by_id(root, 'text_num')[1].set('text', '000************001')
        page = confirmation(mutate=same_name)
        self.assertTrue(page.data['buyersComplete'])
        self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, buyerLabels=['测试甲']))[0], 'pause')
        label = page.data['buyers'][1]['label']
        self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, buyerLabels=[label]))[0],
                         ('buyer', normalized(label), True))

    def test_wrong_order_details_never_allow_buyer_click_even_without_submit(self):
        page = confirmation()
        for changed in ({'quantity': 2}, {'tier': '看台380元'}, {'session': '2026-10-04 周日19:00'}, {'eventTitle': '另一演出'}):
            self.assertEqual(next_step(page, dict(CONFIRM_CONFIG, **changed))[0], 'pause')

    def test_custom_config_validation(self):
        validate_config(dict(CONFIG, buyerLabels=['测试甲']), 0)
        validate_config(dict(CONFIG, quantity=2, buyerLabels=['测试甲', '测试乙']), 0)
        for buyers in (None, '测试甲', [''], ['测试甲', '测试乙'], [1], ['x\ny'], ['x' * 301]):
            with self.assertRaises(ValueError):
                validate_config(dict(CONFIG, buyerLabels=buyers), 0)
        with self.assertRaises(ValueError):
            validate_config(dict(CONFIG, quantity=2, buyerLabels=['测 试甲', '测试甲']), 0)


if __name__ == '__main__':
    unittest.main()
