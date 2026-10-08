package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class NativeAuditIndicatorsTest {
    @Test fun onlyFixedCountersSurviveAndCheckboxesAreNotIdentities() {
        val audit = NativeAuditIndicators()
        audit.observe("cn.damai:id/order_activity_title", "确认订单", null, true, false, false, false)
        audit.observe("cn.damai:id/checkbox", "合成姓名", "身份证 1101**********1234", true, true, true, false)
        audit.observe("cn.damai:id/recycler_view", null, null, true, false, false, true)
        val result = audit.json()
        assertEquals(1, result.getInt("confirmationTitleCount"))
        assertEquals(1, result.getInt("checkboxCount"))
        assertEquals(1, result.getInt("checkedCheckboxCount"))
        assertEquals(1, result.getInt("maskedIdentifierNodeCount"))
        assertEquals(1, result.getInt("documentTypeNodeCount"))
        assertFalse(result.getBoolean("identityTriplesVerified"))
        assertFalse(result.getBoolean("executionReady"))
        assertFalse(result.toString().contains("合成姓名"))
        assertFalse(result.toString().contains("1101"))
        assertFalse(result.toString().contains("1234"))
    }
    @Test fun invisibleAndWrongResourceMarkersDoNotCount() {
        val audit = NativeAuditIndicators()
        audit.observe("other:id/order_activity_title", "确认订单", null, true, false, false, false)
        audit.observe("cn.damai:id/project_detail_perform_flowlayout", "", null, false, false, false, false)
        audit.observe("cn.damai:id/order_activity_title", "其他标题", null, true, false, false, false)
        assertEquals(0, audit.json().getInt("confirmationTitleCount"))
        assertEquals(0, audit.json().getInt("sessionContainerCount"))
    }
    @Test fun actualCashierMarkersStopButConfirmationTitleAloneDoesNot() {
        val audit = NativeAuditIndicators()
        audit.observe("cn.damai:id/order_activity_title", "确认订单", "支付宝", true, false, false, false)
        assertFalse(audit.cashierDetected)
        audit.observe(null, "确认支付", null, true, false, false, false)
        assertTrue(audit.cashierDetected)
    }
    @Test fun layoutDiagnosticsExportOnlyCountsAndKnownCategories() {
        val audit = NativeAuditIndicators()
        audit.observe("cn.damai:id/private_dynamic_identifier", "请选择场次", "票档", true,
            false, false, false, "android.webkit.WebView")
        audit.observe(null, "隐藏场次", null, false, false, false, false, "android.webkit.WebView")
        audit.observe("other:id/private_identifier", "secret arbitrary text", null, true,
            false, false, false, "private.custom.Class")
        val result = audit.json()
        assertEquals(1, result.getInt("resourceIdNodeCount"))
        assertEquals(1, result.getInt("webViewNodeCount"))
        assertEquals(1, result.getInt("sessionLabelNodeCount"))
        assertEquals(1, result.getInt("tierLabelNodeCount"))
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("请选择"))
        audit.observeWindow(true, true, true, true)
        audit.observeWindow(true, false, false, false)
        audit.observeWindow(false, true, true, false)
        assertEquals(2, audit.json().getInt("appWindowCount"))
        assertEquals(1, audit.json().getInt("activeAppWindowCount"))
        assertEquals(1, audit.json().getInt("focusedAppWindowCount"))
        assertEquals(1, audit.json().getInt("inspectedWindowMatchCount"))
    }
    @Test fun fullNumbersAreNotCountedAsMaskedIdentifiers() {
        val audit = NativeAuditIndicators()
        audit.observe(null, "身份证 110101199001011234", null, true, false, false, false)
        assertEquals(0, audit.json().getInt("maskedIdentifierNodeCount"))
        assertFalse(audit.json().toString().contains("110101199001011234"))
    }
}
