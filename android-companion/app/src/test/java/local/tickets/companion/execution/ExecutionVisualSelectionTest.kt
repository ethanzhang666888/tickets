package local.tickets.companion.execution

import local.tickets.companion.pages.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionVisualSelectionTest {
    private val box = NodeBounds(10, 10, 110, 70)
    private val orange = 0xffff6420.toInt()
    private val white = 0xffffffff.toInt()
    @Test fun borderMatchesDesktopRuleAndIgnoresInteriorText() {
        assertTrue(ExecutionVisualSelection.selected(120, 80, box) { x, y ->
            if (x < 15 || x > 104 || y < 15 || y > 64) orange else white
        })
        assertFalse(ExecutionVisualSelection.selected(120, 80, box) { x, y ->
            if (x in 20..100 && y in 20..60) orange else white
        })
        assertFalse(ExecutionVisualSelection.selected(120, 80, box) { _, _ -> white })
    }
    @Test fun exactlySixteenPercentIsNotSelected() {
        var count = 0
        ExecutionVisualSelection.selected(120, 80, box) { _, _ -> count++; white }
        var seen = 0
        assertFalse(ExecutionVisualSelection.selected(120, 80, box) { _, _ -> if (++seen <= count * 16 / 100) orange else white })
        seen = 0
        assertTrue(ExecutionVisualSelection.selected(120, 80, box) { _, _ -> if (++seen <= count * 16 / 100 + 1) orange else white })
    }
    @Test fun colorThresholdsAreStrictAndBadBoundsNeverReadPixels() {
        for (rgb in listOf(0xffc86420, 0xffff1e20, 0xffffb420, 0xffff647d)) {
            assertFalse(ExecutionVisualSelection.selected(120, 80, box) { _, _ -> rgb.toInt() })
        }
        for (bounds in listOf(NodeBounds(-1, 0, 80, 80), NodeBounds(0, -1, 80, 80), NodeBounds(0, 0, 121, 80),
            NodeBounds(0, 0, 100, 81), NodeBounds(10, 10, 40, 50), NodeBounds(0, 0, 60, 20))) {
            assertFalse(ExecutionVisualSelection.selected(120, 80, bounds) { _, _ -> error("Invalid bounds must not access a bitmap") })
        }
    }
}

class ExecutionCapturePolicyTest {
    private val full = NodeBounds(0, 0, 1080, 2200)
    private fun picker(): MutableList<NodeSpec> {
        val rows = mutableListOf<NodeSpec>()
        fun add(parent: Int?, id: String = "", text: String = "", visible: Boolean = true, bounds: NodeBounds = full): Int {
            rows += NodeSpec(parentIndex = parent, resourceId = if (id.isEmpty()) "" else "cn.damai:id/$id", text = text,
                visible = visible, bounds = bounds, selected = false)
            return rows.lastIndex
        }
        val root = add(null)
        val screen = add(root)
        add(screen, "title_back_btn"); add(screen, "title_tv", visible = false)
        val body = add(screen)
        add(body, "tv_title", "测试演出")
        val scroll = add(body, "preform_scrollview")
        val wrapper = add(scroll)
        add(wrapper, "tv_perform_name", "场次")
        val sessions = add(wrapper, DamaiPageParser.SESSION_LIST)
        val sessionWrapper = add(sessions)
        add(sessionWrapper, "ll_perform_item", bounds = NodeBounds(20, 300, 400, 420))
        add(wrapper, "tv_price_name", "票档")
        val tiers = add(wrapper, DamaiPageParser.TIER_LIST)
        add(tiers, "ll_perform_item", bounds = NodeBounds(20, 500, 400, 620))
        add(tiers, "ll_perform_item", bounds = NodeBounds(450, 500, 800, 620))
        val bottom = add(screen, "bottom_layout")
        add(bottom, "btn_buy_view", "立即预订")
        val quantity = add(bottom, "layout_sku_num_ll")
        add(quantity, "tv_num", "1张")
        return rows
    }
    private fun snapshot(rows: List<NodeSpec> = picker(), id: Long = 7, complete: Boolean = true) = NodeSnapshot.create(id, rows, complete)
    private fun detail() = listOf(NodeSpec(bounds = full),
        NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}", text = "测试演出", bounds = full),
        NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_BUTTON}", text = "立即预订", bounds = full))

    @Test fun recognizedPickerClassifiesEveryNativeCardNotOnlySelectedCards() {
        val snapshot = snapshot()
        assertTrue(ExecutionCapturePolicy.allowed(snapshot))
        val cards = ExecutionCapturePolicy.cards(snapshot)!!
        assertEquals(3, cards.size)
        assertTrue(cards.all { it.selected == false })
        assertTrue(ExecutionCapturePolicy.allowed(snapshot(detail())))
    }
    @Test fun deniedPagesMarkersForeignPackagesAndPartialTreesFailClosed() {
        assertFalse(ExecutionCapturePolicy.allowed(snapshot(complete = false)))
        for (marker in listOf("确认支付", "支付密码", "确认购买", "收银台", "登录", "请完成安全验证", "验证码", "账号存在风险")) {
            assertFalse(ExecutionCapturePolicy.allowed(snapshot(picker().apply { add(NodeSpec(parentIndex = 4, text = marker, bounds = full)) })))
        }
        for (id in listOf("order_activity_title", "damai_theme_dialog_confirm_btn")) {
            assertFalse(ExecutionCapturePolicy.allowed(snapshot(picker().apply { add(NodeSpec(parentIndex = 4, resourceId = "cn.damai:id/$id", bounds = full)) })))
        }
        assertFalse(ExecutionCapturePolicy.allowed(snapshot(picker().apply { this[11] = this[11].copy(packageName = "foreign") })))
        assertFalse(ExecutionCapturePolicy.allowed(snapshot(picker().apply { this[9] = this[9].copy(resourceId = "unknown") })))
        assertFalse(ExecutionCapturePolicy.allowed(snapshot(listOf(NodeSpec(text = "场次 票档", bounds = full)))))
    }
    @Test fun malformedCardsAreNeverPromotedToVisualEvidence() {
        val base = picker()
        val variants = listOf(
            base.toMutableList().apply { this[11] = this[11].copy(bounds = NodeBounds(-1, 300, 400, 420)) },
            base.toMutableList().apply { this[11] = this[11].copy(visible = false) },
            base.toMutableList().apply { this[11] = this[11].copy(resourceId = "unknown") },
            base.toMutableList().apply { add(NodeSpec(parentIndex = 10, resourceId = "cn.damai:id/ll_perform_item", bounds = full)) },
            base.toMutableList().apply { add(NodeSpec(parentIndex = 4, resourceId = "cn.damai:id/ll_perform_item", bounds = full)) })
        variants.forEach { assertFalse(ExecutionCapturePolicy.allowed(snapshot(it))) }
    }
    @Test fun correlationRejectsEveryChangedFieldAndCaptureId() {
        val rows = picker()
        val before = snapshot(rows)
        assertTrue(ExecutionCapturePolicy.sameSnapshot(before, snapshot(rows)))
        assertFalse(ExecutionCapturePolicy.sameSnapshot(before, snapshot(rows, id = 8)))
        assertFalse(ExecutionCapturePolicy.sameSnapshot(before, snapshot(rows, complete = false)))
        val node = rows[11]
        for (changed in listOf(node.copy(parentIndex = 9), node.copy(packageName = "other"), node.copy(resourceId = "other"),
            node.copy(text = "changed"), node.copy(description = "changed"), node.copy(visible = false), node.copy(enabled = false),
            node.copy(clickable = true), node.copy(checked = true), node.copy(selected = true), node.copy(scrollable = true),
            node.copy(bounds = NodeBounds(20, 301, 400, 420)))) {
            assertFalse(ExecutionCapturePolicy.sameSnapshot(before, snapshot(rows.toMutableList().apply { this[11] = changed })))
        }
    }
    @Test fun measuredDetailPulseOutsideBuyTargetDoesNotInvalidateThePage() {
        val rows = listOf(NodeSpec(bounds = full),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}", text = "测试演出", bounds = NodeBounds(20, 100, 900, 300)),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_BUTTON}", bounds = NodeBounds(200, 2000, 1040, 2150)),
            NodeSpec(parentIndex = 0, bounds = NodeBounds(850, 1800, 1050, 1950)),
            NodeSpec(parentIndex = 3, resourceId = "cn.damai:id/goto_setinfo_btn_bg", bounds = NodeBounds(871, 1846, 1012, 1917)),
            NodeSpec(parentIndex = 3, resourceId = "cn.damai:id/goto_setinfo_btn_text", clickable = true, bounds = NodeBounds(898, 1862, 985, 1900)))
        val changed = rows.toMutableList().apply {
            this[4] = this[4].copy(bounds = NodeBounds(872, 1847, 1011, 1916))
            this[5] = this[5].copy(bounds = NodeBounds(900, 1862, 983, 1900))
        }
        assertTrue(ExecutionCapturePolicy.sameSnapshot(snapshot(rows), snapshot(changed)))
        for (bad in listOf(
            changed.toMutableList().apply { this[5] = this[5].copy(text = "changed") },
            changed.toMutableList().apply { this[5] = this[5].copy(clickable = false) },
            changed.toMutableList().apply { this[4] = this[4].copy(resourceId = "cn.damai:id/unknown") },
            changed.toMutableList().apply { this[5] = this[5].copy(bounds = NodeBounds(300, 2010, 400, 2050)) },
            changed.toMutableList().apply { this[2] = this[2].copy(bounds = NodeBounds(201, 2000, 1040, 2150)) },
            changed.toMutableList().apply { this[3] = this[3].copy(bounds = full) },
            changed.toMutableList().apply { this[5] = this[5].copy(parentIndex = 0) },
            changed.toMutableList().apply { add(NodeSpec(parentIndex = 5, text = "new control", bounds = full)) }
        )) assertFalse(ExecutionCapturePolicy.sameSnapshot(snapshot(rows), snapshot(bad)))
        assertFalse(ExecutionCapturePolicy.sameSnapshot(snapshot(rows), snapshot(changed, id = 8)))
    }
    @Test fun paymentReferenceExceptionOnlyBelongsToStrictPicker() {
        assertFalse(ExecutionCapturePolicy.unsafeWords(listOf("支付说明"), picker = true))
        assertTrue(ExecutionCapturePolicy.unsafeWords(listOf("支付说明"), picker = false))
        assertTrue(ExecutionCapturePolicy.unsafeWords(listOf("确认支付"), picker = true))
        assertTrue(ExecutionCapturePolicy.unsafeWords(listOf("请完成安全验证"), picker = true))
        assertTrue(ExecutionCapturePolicy.unsafeWords(listOf("确认\n支 付"), picker = true))
        assertTrue(ExecutionCapturePolicy.unsafeWords(listOf("登　录"), picker = true))
        assertTrue(ExecutionCapturePolicy.unsafeWords(listOf("ＰＡＹ ＮＯＷ"), picker = true))
    }
}
