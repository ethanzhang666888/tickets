import unittest

from probe import choose_device, summarize_hierarchy


class DeviceSelectionTests(unittest.TestCase):
    def test_requires_authorization(self):
        with self.assertRaisesRegex(RuntimeError, "USB 调试授权"):
            choose_device("List of devices attached\nphone-a\tunauthorized\n")

    def test_does_not_guess_between_two_devices(self):
        with self.assertRaisesRegex(RuntimeError, "多台设备"):
            choose_device("List of devices attached\nphone-a\tdevice\nphone-b\tdevice\n")
        self.assertEqual(choose_device("phone-a\tdevice\nphone-b\tdevice\n", "phone-b"), "phone-b")

    def test_disconnected_device_is_not_ready(self):
        with self.assertRaisesRegex(RuntimeError, "未找到"):
            choose_device("List of devices attached\n\n")


class HierarchyTests(unittest.TestCase):
    def test_reports_structure_without_identity_text(self):
        result = summarize_hierarchy('''<hierarchy>
          <node package="cn.damai" text="测试姓名 13800000000" resource-id="cn.damai:id/person" clickable="false" />
          <node package="cn.damai" text="立即购买" clickable="true" />
          <node package="com.other" text="安全验证" clickable="true" />
        </hierarchy>''')
        self.assertEqual(result["damaiNodes"], 2)
        self.assertEqual(result["knownLabels"], ["立即购买"])
        self.assertEqual(result["riskKeywords"], [])
        self.assertNotIn("13800000000", str(result))

    def test_risk_hint_and_missing_nodes_are_distinguishable(self):
        warning = summarize_hierarchy('<hierarchy><node package="cn.damai" text="请完成安全验证" /></hierarchy>')
        self.assertEqual(warning["riskKeywords"], ["安全验证"])
        self.assertEqual(summarize_hierarchy('<hierarchy/>')["damaiNodes"], 0)


if __name__ == "__main__":
    unittest.main()
