package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

/** Invented labels on the measured static shape; no real event or identity data. */
class PickerCaptureDeviceTest {
    private data class Row(val parent: Int?, val id: String, val visible: Boolean = true, val text: String = "")
    private fun fixture() = mutableListOf(
        Row(null, ""), Row(0, "title_back_btn"), Row(0, "title_tv", false), Row(0, ""),
        Row(0, "bottom_layout"), Row(3, "tv_title"), Row(3, "preform_scrollview"),
        Row(6, "tv_perform_name"), Row(6, "tv_price_name"), Row(4, "btn_buy_view"), Row(4, "tv_num"),
        Row(3, "tv_desc", text = "支付说明"))
    private fun evidence(rows: List<Row>) = PickerCaptureEvidence().also { evidence ->
        rows.forEach { evidence.observe(it.parent, if (it.id.isEmpty()) null else "cn.damai:id/${it.id}", it.visible, it.text, null) }
    }

    @Test fun genericPaymentReferenceOnlyAllowsReadOnMeasuredCompleteLayout() {
        val recognized = evidence(fixture()).recognized(true)
        assertTrue(recognized)
        assertEquals("selection", Safety.pageKind(listOf("场次", "票档", "支付说明"), recognized))
        assertEquals("payment", Safety.pageKind(listOf("场次", "票档", "支付说明")))
        assertFalse(evidence(fixture()).recognized(false))
    }

    @Test fun duplicatesMissingMarkersChangedHierarchyAndExtraOverlayFailClosed() {
        val base = fixture()
        val variants = listOf(
            base.toMutableList().apply { this[8] = this[8].copy(id = "unknown") },
            base.toMutableList().apply { add(Row(6, "tv_price_name")) },
            base.toMutableList().apply { this[8] = this[8].copy(parent = 3) },
            base.toMutableList().apply { add(Row(0, "unknown_overlay")) },
            base.toMutableList().apply { add(Row(3, "damai_theme_dialog_confirm_btn")) },
            base.toMutableList().apply { this[6] = this[6].copy(visible = false) })
        variants.forEach { assertFalse(evidence(it).recognized(true)) }
    }

    @Test fun idleIncompleteScanWaitsWithoutPermittingAnActiveFrame() {
        val guard = ForegroundGuard()
        assertEquals(ForegroundGuard.Decision.WAIT, guard.evaluate("node_scan_incomplete", true, 100))
        assertEquals(ForegroundGuard.Decision.ALLOW, guard.evaluate(null, true, 150))
        assertEquals(ForegroundGuard.Decision.STOP, guard.evaluate("node_scan_incomplete", false, 200))
        val bounded = ForegroundGuard()
        assertEquals(ForegroundGuard.Decision.WAIT, bounded.evaluate("node_scan_incomplete", true, 0))
        assertEquals(ForegroundGuard.Decision.WAIT, bounded.evaluate("foreground_unavailable", true, 999))
        assertEquals(ForegroundGuard.Decision.STOP, bounded.evaluate("node_scan_incomplete", true, 1000))
        for (error in listOf("payment_foreground", "screen_locked", "accessibility_lost", "foreground_not_allowed", "node_scan_failed")) {
            assertEquals(ForegroundGuard.Decision.STOP, ForegroundGuard().evaluate(error, true, 0))
        }
    }

    @Test fun strongNativeOrOcrMarkersAlwaysStopEvenOnRecognizedPicker() {
        for (marker in listOf("确认支付", "收银台", "支付密码", "立即支付", "付款码", "提交订单", "确认购买", "checkout")) {
            assertFalse(evidence(fixture().apply { add(Row(3, "", text = marker)) }).recognized(true))
            assertEquals("payment", Safety.pageKind(listOf("场次", "票档", marker), true))
        }
    }
}
