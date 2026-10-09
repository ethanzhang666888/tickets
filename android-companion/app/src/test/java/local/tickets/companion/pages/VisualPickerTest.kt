package local.tickets.companion.pages

import org.junit.Assert.*
import org.junit.Test

class VisualPickerTest {
    private fun fixture(capture: Long = 1, nativeSession: String = ""): Pair<NodeSnapshot, VisualEvidence> {
        val specs = mutableListOf<NodeSpec>()
        fun add(id: String, parent: Int? = null, text: String = "", box: NodeBounds, clickable: Boolean = false): Int {
            specs += NodeSpec(parent, resourceId = "cn.damai:id/$id", text = text, bounds = box, clickable = clickable, selected = false)
            return specs.lastIndex
        }
        add("tv_title", text = "测试演出", box = NodeBounds(0, 0, 600, 80))
        val sessions = add("project_detail_perform_flowlayout", box = NodeBounds(0, 100, 600, 250))
        val session = add("ll_perform_item", sessions, box = NodeBounds(10, 110, 590, 240), clickable = true)
        if (nativeSession.isNotEmpty()) add("session_label", session, nativeSession, NodeBounds(20, 150, 570, 190))
        val tiers = add("project_detail_perform_price_flowlayout", box = NodeBounds(0, 300, 600, 550))
        val tier = add("ll_perform_item", tiers, box = NodeBounds(10, 310, 280, 420), clickable = true)
        val sold = add("ll_perform_item", tiers, box = NodeBounds(310, 310, 590, 420), clickable = true)
        val quantity = add("layout_sku_num_ll", box = NodeBounds(0, 600, 600, 700))
        add("tv_num", quantity, "1张", NodeBounds(240, 610, 340, 690))
        add("btn_buy_view", box = NodeBounds(0, 720, 600, 800), clickable = true)
        val words = listOf(
            VisualWord("2026-11-07 周六 19:00", NodeBounds(20, 150, 570, 190)),
            VisualWord("内场880元", NodeBounds(30, 320, 250, 370)),
            VisualWord("看台680元", NodeBounds(330, 320, 570, 370)),
            VisualWord("缺货", NodeBounds(400, 375, 510, 410)),
            VisualWord("确定", NodeBounds(250, 745, 350, 780)))
        return NodeSnapshot.create(capture, specs, true) to VisualEvidence(capture, 600, 900, words,
            mapOf(session to true, tier to false, sold to false), 100)
    }
    @Test fun equivalentNativeAndVisualSessionsMergeButDifferentTimesDoNot() {
        val (snapshot, visual) = fixture(nativeSession = "2026-11-07 19:00")
        assertTrue(DamaiPageParser().parse(snapshot, visual).blockers.isEmpty())
        val (different, conflicting) = fixture(nativeSession = "2026-11-07 20:00")
        assertTrue(PageBlocker.UNSUPPORTED_OPTIONS in DamaiPageParser().parse(different, conflicting).blockers)
    }
    @Test fun nativePresaleBadgeIsSeparatedFromTheOcrSessionIdentity() {
        val (snapshot, visual) = fixture(nativeSession = "预售")
        val words = visual.words.mapIndexed { i, w -> if (i == 0) VisualWord(w.text + " 预售", w.bounds) else w }
        val page = DamaiPageParser().parse(snapshot, VisualEvidence(1, 600, 900, words, visual.selectedCards, 100))
        assertTrue(page.blockers.toString(), page.blockers.isEmpty())
        assertEquals(DamaiPageParser.sessionKey("2026-11-07 19:00"), DamaiPageParser.sessionKey(page.sessions.single().label))
        assertEquals(Availability.AVAILABLE, page.sessions.single().availability)
    }
    @Test fun uncorroboratedOrUnknownSessionSuffixStillBlocks() {
        for ((native, suffix) in listOf("" to "预售", "预售" to "可售", "预售" to "预售20:00")) {
            val (snapshot, visual) = fixture(nativeSession = native)
            val words = visual.words.mapIndexed { i, w -> if (i == 0) VisualWord(w.text + suffix, w.bounds) else w }
            val page = DamaiPageParser().parse(snapshot, VisualEvidence(1, 600, 900, words, visual.selectedCards, 100))
            assertTrue(PageBlocker.UNSUPPORTED_OPTIONS in page.blockers)
        }
    }
    @Test fun presaleBadgeNeverOverridesSoldOutOrUpcomingStatus() {
        for ((suffix, expected) in listOf("预售缺货" to Availability.SOLD_OUT, "预售未开售" to Availability.UPCOMING)) {
            val (snapshot, visual) = fixture(nativeSession = "预售")
            val words = visual.words.mapIndexed { i, w -> if (i == 0) VisualWord(w.text + suffix, w.bounds) else w }
            val page = DamaiPageParser().parse(snapshot, VisualEvidence(1, 600, 900, words, visual.selectedCards, 100))
            assertTrue(page.blockers.toString(), page.blockers.isEmpty())
            assertEquals(expected, page.sessions.single().availability)
        }
    }
    @Test fun traditionalInnerTierSpellingMatchesWithoutChangingPriceOrArea() {
        for (spelling in listOf("內場880元", "內场880元", "内場880元")) {
            val (snapshot, visual) = fixture()
            val words = visual.words.mapIndexed { i, w -> if (i == 1) VisualWord(spelling, w.bounds) else w }
            val evidence = VisualEvidence(1, 600, 900, words, visual.selectedCards, 100)
            val target = local.tickets.companion.policy.TicketTarget("2026-11-07 19:00", "内场880元", 88000)
            val page = DamaiPageParser().parse(snapshot, evidence)
            assertTrue(page.matchTarget("测试演出", target) is TargetMatch.Matched)
            val engine = local.tickets.companion.execution.LocalExecution(
                local.tickets.companion.execution.LocalPlan("测试演出", listOf(target), 1, 88000, 1000, 61000), 1000, 100)
            val decision = engine.tick(100, snapshot, evidence)
            assertTrue(decision is local.tickets.companion.execution.ExecutionDecision.Click)
            assertEquals(local.tickets.companion.execution.ActionKind.TIER,
                (decision as local.tickets.companion.execution.ExecutionDecision.Click).action.kind)
        }
    }
    @Test fun traditionalSpellingDoesNotPermitFuzzyAreaOrPriceMatching() {
        val target = local.tickets.companion.policy.TicketTarget("2026-11-07 19:00", "内场880元", 88000)
        for (spelling in listOf("内埸880元", "看台880元", "內場980元", "內場A区880元")) {
            val (snapshot, visual) = fixture()
            val words = visual.words.mapIndexed { i, w -> if (i == 1) VisualWord(spelling, w.bounds) else w }
            val page = DamaiPageParser().parse(snapshot, VisualEvidence(1, 600, 900, words, visual.selectedCards, 100))
            assertTrue(page.matchTarget("测试演出", target) is TargetMatch.Blocked)
        }
    }
    @Test fun equivalentInnerTierSpellingsRemainAmbiguousAcrossCardsAndPlans() {
        val (snapshot, visual) = fixture()
        val words = visual.words.mapIndexed { i, w -> when (i) {
            1 -> VisualWord("內場880元", w.bounds)
            2 -> VisualWord("内场880元", w.bounds)
            else -> w
        } }
        val page = DamaiPageParser().parse(snapshot, VisualEvidence(1, 600, 900, words, visual.selectedCards, 100))
        assertTrue(PageBlocker.AMBIGUOUS_STRUCTURE in page.blockers)
        val targets = listOf("內場880元", "内场880元").map { local.tickets.companion.policy.TicketTarget("2026-11-07 19:00", it, 88000) }
        try {
            local.tickets.companion.execution.LocalPlan("测试演出", targets, 1, 88000, 1000, 61000).validate(1000)
            fail("Equivalent targets must not bypass duplicate detection")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun scopedVisualWordsAndBordersCompleteMeasuredNativeCards() {
        val (snapshot, visual) = fixture()
        val p = DamaiPageParser().parse(snapshot, visual)
        assertTrue(p.blockers.toString(), p.blockers.isEmpty())
        assertEquals(1, p.sessions.size)
        assertEquals(SelectionEvidence.VISUAL_SELECTED, p.sessions.single().selection)
        assertEquals("内场880元", p.tiers.first().label)
        assertEquals(88000L, p.tiers.first().unitPriceCents)
        assertEquals(Availability.SOLD_OUT, p.tiers.last().availability)
    }
    @Test fun absentMismatchedOrIncompleteVisualEvidenceNeverProvesSelection() {
        val (snapshot, visual) = fixture()
        assertTrue(DamaiPageParser().parse(snapshot).blockers.isNotEmpty())
        assertTrue(DamaiPageParser().parse(fixture(2).first, visual).blockers.isNotEmpty())
        val missing = VisualEvidence(1, 600, 900, visual.words, emptyMap(), 100)
        val p = DamaiPageParser().parse(snapshot, missing)
        assertTrue(p.tiers.all { it.selection == SelectionEvidence.UNKNOWN })
    }
    @Test fun ocrRiskAndConflictingAmountsStopInsteadOfGuessing() {
        val (snapshot, visual) = fixture()
        val risk = VisualEvidence(1, 600, 900, visual.words + VisualWord("请完成安全验证", NodeBounds(5, 5, 590, 60)), visual.selectedCards, 100)
        assertEquals(PageKind.RISK, DamaiPageParser().parse(snapshot, risk).kind)
        val ambiguous = VisualEvidence(1, 600, 900, visual.words + VisualWord("内场980元", NodeBounds(30, 375, 250, 410)), visual.selectedCards, 100)
        assertTrue(PageBlocker.UNSUPPORTED_OPTIONS in DamaiPageParser().parse(snapshot, ambiguous).blockers)
    }
}
