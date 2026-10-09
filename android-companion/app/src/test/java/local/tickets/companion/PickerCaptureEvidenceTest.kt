package local.tickets.companion

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PickerCaptureEvidenceTest {
    private data class Row(val parent: Int?, val id: String = "", val visible: Boolean = true, val text: String = "", val description: String = "")
    private fun original() = mutableListOf(
        Row(null), Row(0, "title_back_btn"), Row(0, "title_tv", false), Row(0),
        Row(0, "bottom_layout"), Row(3, "tv_title"), Row(3, "preform_scrollview"),
        Row(6, "tv_perform_name"), Row(6, "tv_price_name"), Row(4, "btn_buy_view"), Row(4, "tv_num"))
    private fun expanded() = mutableListOf(
        Row(null), Row(0), Row(1, "title_back_btn"), Row(1, "title_tv", false), Row(1),
        Row(4, "tv_title"), Row(4, "preform_scrollview"), Row(6),
        Row(7, "tv_perform_name"), Row(7, "project_detail_perform_flowlayout"),
        Row(9), Row(10, "ll_perform_item"), Row(7, "tv_price_name"),
        Row(7, "project_detail_perform_price_flowlayout"), Row(13), Row(14, "ll_perform_item"),
        Row(1, "bottom_layout"), Row(16, "btn_buy_view"), Row(16, "layout_sku_num_ll"), Row(18, "tv_num"))
    private fun recognized(rows: List<Row>, complete: Boolean = true) = PickerCaptureEvidence().also { evidence ->
        rows.forEach { row ->
            val id = row.id.takeIf(String::isNotEmpty)?.let { if (':' in it) it else "cn.damai:id/$it" }
            evidence.observe(row.parent, id, row.visible, row.text, row.description)
        }
    }.recognized(complete)

    /** Only parent/index/id/visibility from the measured device report; no original text. */
    private fun measured(): MutableList<Row> {
        val source = checkNotNull(javaClass.getResourceAsStream("/picker-expanded-unselected-layout.json"))
            .bufferedReader().use { it.readText() }
        val array = JSONArray(source)
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            assertEquals(setOf("i", "parent", "id", "visible"), row.keySet())
            assertEquals(index, row.getInt("i"))
            Row(if (row.isNull("parent")) null else row.getInt("parent"), row.getString("id"), row.getBoolean("visible"))
        }.toMutableList()
    }

    private fun measuredSelected(): MutableList<Row> = measured().take(102).toMutableList().apply {
        addAll(listOf(Row(12, "bottom_layout"), Row(102, "layout_num_top"),
            Row(102, "id_sku_bottom_bar_container"), Row(104, "id_sku_bottom_bar_action_content"),
            Row(105, "layout_num"), Row(106), Row(107), Row(108, "tv_limit_num_title"), Row(107, "tv_limit_num"),
            Row(106, "layout_sku_num_ll"), Row(111, "img_jian"), Row(111, "tv_num"), Row(111, "img_jia"),
            Row(105, "btn_buy_bottom_div_line"), Row(105, "btn_buy_bottom_div"), Row(116, "layout_left"), Row(117),
            Row(118, "tv_sku_price_prefix"), Row(118, "tv_price"), Row(118, "layout_price_detail"), Row(121), Row(121, "view_jiantou", text = "\uE600"),
            Row(116), Row(124), Row(125, "btn_buy_view"), Row(126, visible = false)))
    }

    @Test fun measuredSelectedPickerRequiresExactQuantityAndPriceDetailStructure() {
        val rows = measuredSelected()
        assertEquals(128, rows.size)
        assertTrue(recognized(rows))
        for (parent in rows.indices) assertFalse("selected overlay at $parent", recognized(rows + Row(parent)))
        for (index in listOf(106, 111, 112, 113, 114, 121, 126)) {
            assertFalse(recognized(rows.toMutableList().apply { this[index] = this[index].copy(id = "unexpected") }))
        }
    }

    @Test fun measuredInitialPickerHasSixCardsAndNeedsNoQuantityNode() {
        val rows = measured()
        assertEquals(116, rows.size)
        assertEquals(6, rows.count { it.id == "cn.damai:id/ll_perform_item" })
        assertTrue(rows.none { it.id == "cn.damai:id/tv_num" })
        assertTrue(recognized(rows))
        assertFalse(recognized(rows, complete = false))
    }

    @Test fun measuredDecorationsMustHaveNeitherTextNorDescription() {
        val base = measured()
        // Includes anonymous children of the measured background/decorative views.
        val decorations = listOf(3, 13, 14, 15, 17, 19, 20, 33, 34, 35, 47, 49, 55, 61, 63,
            70, 72, 79, 81, 88, 90, 95, 97, 101, 105, 112, 115)
        for (index in decorations) {
            assertFalse("text on decoration $index", recognized(base.toMutableList().apply {
                this[index] = this[index].copy(text = "异常装饰")
            }))
            assertFalse("description on decoration $index", recognized(base.toMutableList().apply {
                this[index] = this[index].copy(description = "异常装饰")
            }))
        }
    }

    @Test fun measuredShapeRejectsAdditionalNamedAndAnonymousOverlaysAtEveryLevel() {
        val base = measured()
        for (parent in base.indices) {
            for (id in listOf("", "unknown_overlay")) {
                assertFalse("overlay at $parent", recognized(base.toMutableList().apply { add(Row(parent, id)) }))
            }
        }
        assertFalse(recognized(base.toMutableList().apply { add(Row(null)) }))
        assertFalse(recognized(base.toMutableList().apply { this[3] = this[3].copy(id = "other_watermark") }))
    }

    @Test fun measuredAnchorsCannotBeDuplicatedMovedRemovedOrHidden() {
        val base = measured()
        val anchors = listOf(4, 5, 9, 12, 18, 22, 24, 31, 36, 39, 41, 43, 53, 56, 57, 102, 114)
        for (index in anchors) {
            assertFalse("duplicate $index", recognized(base.toMutableList().apply { add(this[index]) }))
            assertFalse("move $index", recognized(base.toMutableList().apply { this[index] = this[index].copy(parent = 0) }))
            assertFalse("rename $index", recognized(base.toMutableList().apply { this[index] = this[index].copy(id = "missing_anchor") }))
            assertFalse("hide $index", recognized(base.toMutableList().apply { this[index] = this[index].copy(visible = false) }))
        }
        assertFalse(recognized(base.toMutableList().apply { add(Row(102, "tv_num")) }))
    }

    @Test fun measuredPickerRetainsStrongPaymentAndDialogPriority() {
        val base = measured()
        for (marker in listOf("确认支付", "收银台", "支付密码", "立即付款", "提交订单", "确认购买", "checkout")) {
            assertFalse(recognized(base.toMutableList().apply { this[32] = this[32].copy(text = marker) }))
            assertFalse(recognized(base.toMutableList().apply { this[32] = this[32].copy(description = marker) }))
        }
        for (id in listOf("order_activity_title", "damai_theme_dialog_confirm_btn")) {
            assertFalse(recognized(base.toMutableList().apply { add(Row(12, id)) }))
        }
        val genericPaymentReference = base.toMutableList().apply { this[32] = this[32].copy(text = "支付说明") }
        assertTrue(recognized(genericPaymentReference))
        assertEquals("selection", Safety.pageKind(listOf("支付说明"), recognized(genericPaymentReference)))
    }

    @Test fun originalAndExpandedLegacyShapesAreRecognized() {
        assertTrue(recognized(original()))
        assertTrue(recognized(expanded()))
        assertFalse(recognized(expanded(), false))
    }
    @Test fun expandedShapeRequiresUniqueOwnedContainersAndNoOverlay() {
        val base = expanded()
        val variants = listOf(
            base.toMutableList().apply { this[9] = this[9].copy(id = "unknown") },
            base.toMutableList().apply { this[13] = this[13].copy(parent = 16) },
            base.toMutableList().apply { add(Row(7, "project_detail_perform_flowlayout")) },
            base.toMutableList().apply { add(Row(0, "unknown_overlay")) },
            base.toMutableList().apply { add(Row(1, "unknown_overlay")) },
            base.toMutableList().apply { this[6] = this[6].copy(visible = false) },
            base.toMutableList().apply { add(Row(4, "order_activity_title")) },
            base.toMutableList().apply { add(Row(4, "damai_theme_dialog_confirm_btn")) })
        variants.forEach { assertFalse(recognized(it)) }
    }
    @Test fun originalShapeCannotBypassLegacyContainerOwnership() {
        assertFalse(recognized(original().apply {
            add(Row(4, "project_detail_perform_flowlayout"))
            add(Row(4, "project_detail_perform_price_flowlayout"))
        }))
        assertFalse(recognized(original().apply { add(Row(6, "project_detail_perform_flowlayout")) }))
    }
    @Test fun strongMarkersWinOverBothLayouts() {
        for (text in listOf("确认支付", "收银台", "支付密码", "立即付款", "提交订单", "确认购买", "checkout")) {
            assertFalse(recognized(original().apply { add(Row(3, text = text)) }))
            assertFalse(recognized(expanded().apply { add(Row(4, text = text)) }))
        }
    }
}
