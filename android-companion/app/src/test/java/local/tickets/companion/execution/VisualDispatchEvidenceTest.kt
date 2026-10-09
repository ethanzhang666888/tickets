package local.tickets.companion.execution

import local.tickets.companion.pages.*
import org.junit.Assert.*
import org.junit.Test

class VisualDispatchEvidenceTest {
    private val box = NodeBounds(0, 0, 100, 100)
    private fun evidence(id: Long = 1, text: String = "内场880元", selected: Boolean = true, at: Long = 100) =
        VisualEvidence(id, 1080, 2340, listOf(VisualWord(text, box)), mapOf(3 to selected), at)

    @Test fun twoFreshFramesMustAgreeOnTextGeometryAndAllCardSelections() {
        assertTrue(VisualDispatchEvidence.agrees(evidence(), evidence(id = 2), 200))
        assertFalse(VisualDispatchEvidence.agrees(evidence(), evidence(id = 2, text = "内场980元"), 200))
        assertFalse(VisualDispatchEvidence.agrees(evidence(), evidence(id = 2, selected = false), 200))
        assertFalse(VisualDispatchEvidence.agrees(evidence(), VisualEvidence(2, 1080, 2340,
            listOf(VisualWord("内场880元", NodeBounds(1, 0, 101, 100))), mapOf(3 to true), 100), 200))
    }
    @Test fun onlyMeasuredDetailPulseTextMayResizeInsideItsVerifiedBackground() {
        val full = NodeBounds(0, 0, 1080, 2340)
        val rows = listOf(NodeSpec(bounds = full),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}", text = "测试演出", bounds = NodeBounds(20, 100, 900, 300)),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_BUTTON}", bounds = NodeBounds(200, 2200, 1040, 2300)),
            NodeSpec(parentIndex = 0, bounds = NodeBounds(850, 1800, 1050, 1950)),
            NodeSpec(parentIndex = 3, resourceId = "cn.damai:id/goto_setinfo_btn_bg", bounds = NodeBounds(870, 1840, 1015, 1920)),
            NodeSpec(parentIndex = 3, resourceId = "cn.damai:id/goto_setinfo_btn_text", clickable = true, bounds = NodeBounds(890, 1850, 1000, 1910)))
        val snapshot = NodeSnapshot.create(1, rows, true)
        val label = VisualWord("立即预订", NodeBounds(500, 2230, 700, 2280))
        fun frame(text: String = "去填写", bounds: NodeBounds = NodeBounds(899, 1864, 986, 1895)) =
            VisualEvidence(1, 1080, 2340, listOf(label, VisualWord(text, bounds)), emptyMap(), 100)
        val changed = frame(bounds = NodeBounds(898, 1863, 983, 1894))
        assertTrue(VisualDispatchEvidence.agrees(frame(), frame("去填与"), 200, snapshot))
        val withStatus = NodeSnapshot.create(1, rows + NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/project_detail_title_bar_status_space", visible = false, bounds = NodeBounds(0, 0, 1080, 90)), true)
        fun status(text: String) = VisualEvidence(1, 1080, 2340, frame().words + VisualWord(text, NodeBounds(700, 28, 1000, 72)), emptyMap(), 100)
        assertTrue(VisualDispatchEvidence.agrees(status("12:30"), status("12:31"), 200, withStatus))
        assertFalse(VisualDispatchEvidence.agrees(status("12:30"), status("确认支付"), 200, withStatus))
        assertFalse(VisualDispatchEvidence.agrees(status("12:30"), status("12:31"), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(frame(), changed, 200))
        assertTrue(VisualDispatchEvidence.agrees(frame(), changed, 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(frame(), frame("去支付"), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(frame(), frame(bounds = NodeBounds(898, 1900, 983, 1940)), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(frame(), VisualEvidence(1, 1080, 2340,
            listOf(VisualWord("立即预订", NodeBounds(501, 2230, 700, 2280)), frame().words.last()), emptyMap(), 100), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(frame(), changed, 200,
            NodeSnapshot.create(1, rows.toMutableList().apply { this[4] = this[4].copy(resourceId = "unknown") }, true)))
    }
    private fun selectedPicker(): MutableList<NodeSpec> {
        val full = NodeBounds(0, 0, 1080, 2340)
        val source = checkNotNull(javaClass.getResourceAsStream("/picker-expanded-unselected-layout.json"))
            .bufferedReader().use { it.readText() }
        val array = org.json.JSONArray(source)
        val cardBounds = mapOf(45 to NodeBounds(54, 566, 612, 688), 59 to NodeBounds(54, 833, 436, 955),
            68 to NodeBounds(459, 833, 841, 955), 77 to NodeBounds(54, 978, 436, 1100),
            86 to NodeBounds(459, 978, 767, 1100), 93 to NodeBounds(54, 1123, 436, 1245))
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val id = row.getString("id")
            val bounds = when (id) {
                "cn.damai:id/title_bar_space" -> NodeBounds(0, 0, 1080, 101)
                "cn.damai:id/tv_title" -> NodeBounds(54, 246, 1054, 354)
                "cn.damai:id/btn_buy_view" -> NodeBounds(648, 2207, 1026, 2319)
                else -> cardBounds[index] ?: full
            }
            NodeSpec(parentIndex = if (row.isNull("parent")) null else row.getInt("parent"), resourceId = id,
                text = if (id == "cn.damai:id/tv_title") "测试演出" else "", visible = row.getBoolean("visible"), bounds = bounds)
        }.toMutableList()
    }

    @Test fun measuredPickerStatusAndFourPixelNextLabelJitterKeepVerifiedSelection() {
        val snapshot = NodeSnapshot.create(1, selectedPicker(), true)
        assertTrue(ExecutionCapturePolicy.allowed(snapshot))
        fun VisualWord.copy(text: String = this.text, bounds: NodeBounds = this.bounds) = VisualWord(text, bounds)
        val label = VisualWord("确定", NodeBounds(798, 2239, 876, 2279))
        val tier = VisualWord("内场880元", NodeBounds(480, 1000, 740, 1060))
        fun frame(status: String, next: VisualWord = label, words: List<VisualWord> = listOf(tier), selected: Boolean = true) =
            VisualEvidence(1, 1080, 2340, words + next + VisualWord(status, NodeBounds(661, 32, 1018, 70)), mapOf(45 to true, 59 to false, 68 to false, 77 to false, 86 to selected, 93 to false), 100)
        val before = frame("10KB/s")
        val changed = frame("11KB/s", label.copy(bounds = NodeBounds(797, 2241, 872, 2280)))
        assertTrue(VisualDispatchEvidence.agrees(before, changed, 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, changed, 200))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("11KB/s", label.copy(bounds = NodeBounds(793, 2239, 876, 2279))), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("11KB/s", label.copy(bounds = NodeBounds(640, 2239, 718, 2279))), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("11KB/s", label.copy(text = "立即预订")), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("11KB/s", words = listOf(tier.copy(text = "内场980元"))), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("11KB/s", words = listOf(tier.copy(bounds = NodeBounds(481, 1000, 740, 1060)))), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("11KB/s", selected = false), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("11KB/s", words = listOf(tier, label)), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("请完成安全验证"), 200, snapshot))
        assertFalse(VisualDispatchEvidence.agrees(before, frame("确认支付"), 200, snapshot))
        for (mutate in listOf<(MutableList<NodeSpec>) -> Unit>(
            { it[17] = it[17].copy(resourceId = "unknown") },
            { it[17] = it[17].copy(visible = true) },
            { it[17] = it[17].copy(bounds = NodeBounds(0, 0, 1080, 400)) },
            { it[17] = it[17].copy(clickable = true) },
            { it[114] = it[114].copy(resourceId = "unknown") },
            { it[114] = it[114].copy(visible = false) },
            { it[114] = it[114].copy(enabled = false) },
            { it[114] = it[114].copy(parentIndex = 0) }
        )) assertFalse(VisualDispatchEvidence.agrees(before, changed, 200,
            NodeSnapshot.create(1, selectedPicker().also(mutate), true)))
    }

    @Test fun staleFutureOrMissingVisualObservationsNeverAuthorizeDispatch() {
        assertFalse(VisualDispatchEvidence.agrees(evidence(), evidence(id = 2), 2101))
        assertFalse(VisualDispatchEvidence.agrees(evidence(), evidence(id = 2, at = 201), 200))
        assertFalse(VisualDispatchEvidence.agrees(evidence(), null, 200))
        assertTrue(VisualDispatchEvidence.agrees(null, null, 200)) // Native confirmation only.
    }
}
