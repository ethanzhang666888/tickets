package local.tickets.companion

import local.tickets.companion.execution.*
import local.tickets.companion.pages.*
import local.tickets.companion.policy.TicketTarget
import org.junit.Assert.*
import org.junit.Test

class LocalExecutionTest {
    private val first = TicketTarget("2026-11-07 19:00", "内场880元", 88000)
    private val other = TicketTarget("2026-11-07 19:00", "看台680元", 68000)
    private fun plan(targets: List<TicketTarget> = listOf(first), priority: Long = 180000) =
        LocalPlan("测试演出", targets, 1, 88000, 1000000, 2000000, priority, true)
    private fun engine(plan: LocalPlan = plan()) = LocalExecution(plan, 1000000, 100)
    private fun picker(selected: Int = -1, soldOut: Boolean = false, quantity: Int = 1,
                       event: String = "测试演出", capture: Long = 1): NodeSnapshot {
        val nodes = mutableListOf<NodeSpec>()
        fun add(id: String = "", text: String = "", parent: Int? = null, chosen: Boolean? = null, click: Boolean = false): Int {
            nodes += NodeSpec(parentIndex = parent, resourceId = if (id.isEmpty()) "" else "cn.damai:id/$id",
                text = text, selected = chosen, clickable = click, bounds = NodeBounds(0, 0, 600, 900))
            return nodes.lastIndex
        }
        add("tv_title", event)
        val sessions = add("project_detail_perform_flowlayout")
        val session = add("ll_perform_item", parent = sessions, chosen = true, click = true)
        add(text = first.session, parent = session)
        val tiers = add("project_detail_perform_price_flowlayout")
        for ((i, target) in listOf(first, other).withIndex()) {
            val tier = add("ll_perform_item", parent = tiers, chosen = selected == i, click = true)
            add(text = target.tier, parent = tier)
            if (soldOut && i == 0) add(text = "缺货", parent = tier)
        }
        val row = add("layout_sku_num_ll")
        add("tv_num", "${quantity}张", row)
        add("img_jia", parent = row, click = true)
        add("img_jian", parent = row, click = true)
        add("btn_buy_view", "确定", click = true)
        return NodeSnapshot.create(capture, nodes, true)
    }
    private fun confirmation(capture: Long = 5): NodeSnapshot = NodeSnapshot.create(capture, listOf(
        NodeSpec(resourceId = "cn.damai:id/order_activity_title", text = "确认购买", bounds = NodeBounds(0, 0, 600, 900))
    ), true)

    @Test fun selectsTargetThenWaitsForEffectBeforeEnteringConfirmation() {
        val e = engine()
        val choose = e.tick(100, picker()) as ExecutionDecision.Click
        assertEquals(ActionKind.TIER, choose.action.kind)
        assertTrue(e.tick(150, picker(capture = 2)) is ExecutionDecision.Wait)
        val next = e.tick(200, picker(selected = 0, capture = 3)) as ExecutionDecision.Click
        assertEquals(ActionKind.NEXT, next.action.kind)
        assertTrue(e.tick(250, picker(selected = 0, capture = 4)) is ExecutionDecision.Wait)
        assertEquals("confirmation_reached_manual_review", (e.tick(300, confirmation()) as ExecutionDecision.Finished).reason)
        assertTrue(e.tick(400, picker(capture = 6)) is ExecutionDecision.Finished)
    }
    @Test fun confirmationNeverContainsAClickEvenIfStartedThere() {
        assertTrue(engine().tick(100, confirmation()) is ExecutionDecision.Finished)
    }
    @Test fun pendingClickIsNeverRepeatedAndTimeoutIsTerminal() {
        val e = engine()
        assertTrue(e.tick(100, picker()) is ExecutionDecision.Click)
        assertTrue(e.tick(3000, picker(capture = 2)) is ExecutionDecision.Stopped)
        assertTrue(e.tick(3100, picker(selected = 0, capture = 3)) is ExecutionDecision.Stopped)
    }
    @Test fun noActionBeforeStartOrAtDeadline() {
        val e = LocalExecution(plan(), 999000, 100)
        assertTrue(e.tick(100, picker()) is ExecutionDecision.Wait)
        assertTrue(e.tick(1099, picker(capture = 2)) is ExecutionDecision.Wait)
        assertTrue(e.tick(1100, picker(capture = 3)) is ExecutionDecision.Click)
        assertEquals("deadline", (engine().tick(1000100, picker()) as ExecutionDecision.Stopped).reason)
    }
    @Test fun stoppedGenerationCannotBeRestartedByLatePage() {
        val e = engine()
        e.stop("user_stop")
        assertEquals("user_stop", (e.tick(100, picker()) as ExecutionDecision.Stopped).reason)
    }
    @Test fun changedEventAndIncompleteOrUnknownTreeStop() {
        assertTrue(engine().tick(100, picker(event = "另一场演出")) is ExecutionDecision.Stopped)
        assertTrue(engine().tick(100, NodeSnapshot.create(1, emptyList(), false)) is ExecutionDecision.Stopped)
        assertTrue(engine().tick(100, NodeSnapshot.create(1, emptyList(), true)) is ExecutionDecision.Stopped)
    }
    @Test fun alternativesStayDisabledUntilPriorityWindowEnds() {
        val e = engine(plan(listOf(first, other), priority = 1000))
        assertTrue(e.tick(100, picker(soldOut = true)) is ExecutionDecision.Wait)
        assertTrue(e.tick(1099, picker(soldOut = true, capture = 2)) is ExecutionDecision.Wait)
        val alternative = e.tick(1100, picker(soldOut = true, capture = 3)) as ExecutionDecision.Click
        assertEquals(ActionKind.TIER, alternative.action.kind)
        assertEquals("看台680元", alternative.action.expectedLabel)
    }
    @Test fun disabledWaitingStopsOnSoldOutAndBudgetIsHardBoundary() {
        assertTrue(engine(plan().copy(waitForStock = false)).tick(100, picker(soldOut = true)) is ExecutionDecision.Stopped)
        assertThrows(IllegalArgumentException::class.java) { engine(plan().copy(maxTotalCents = 87999)) }
        assertThrows(IllegalArgumentException::class.java) { engine(plan().copy(targets = listOf(first, first))) }
        assertThrows(IllegalArgumentException::class.java) { engine(plan().copy(autoSubmit = true)) }
    }
    @Test fun quantityChangesRequireObservedSingleStepEffect() {
        val e = engine()
        val decrease = e.tick(100, picker(selected = 0, quantity = 2)) as ExecutionDecision.Click
        assertEquals(ActionKind.DECREASE, decrease.action.kind)
        assertTrue(e.tick(200, picker(selected = 0, quantity = 3, capture = 2)) is ExecutionDecision.Stopped)
    }
    @Test fun staleSnapshotsAndClockRollbackCannotProduceClicks() {
        val e = engine()
        e.tick(100, picker())
        assertTrue(e.tick(200, picker(selected = 0)) is ExecutionDecision.Stopped)
        val otherEngine = engine()
        otherEngine.tick(100, picker())
        assertTrue(otherEngine.tick(99, picker(selected = 0, capture = 2)) is ExecutionDecision.Stopped)
    }
    @Test fun submittedOrUncertainOrderGuardStopsBeforeAnyClick() {
        val e = engine()
        e.stop("unresolved_submission")
        assertTrue(e.tick(100, picker()) is ExecutionDecision.Stopped)
    }
    @Test fun unsafePageDuringCountdownStopsWithoutWaitingForSale() {
        val e = LocalExecution(plan(), 999000, 100)
        assertTrue(e.tick(100, picker(event = "另一演出")) is ExecutionDecision.Stopped)
        val risk = NodeSnapshot.create(1, listOf(NodeSpec(text = "请完成安全验证", bounds = NodeBounds(0, 0, 600, 900))), true)
        assertTrue(LocalExecution(plan(), 999000, 100).tick(100, risk) is ExecutionDecision.Stopped)
    }
    @Test fun semanticDuplicateDatesCannotCreateTwoAuthorizedTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            engine(plan(listOf(first, first.copy(session = "2026.11.07 19:00"))))
        }
    }
    @Test fun planCopyCannotChangeTheRunningTargets() {
        val targets = mutableListOf(first)
        val e = engine(plan(targets))
        targets[0] = other
        assertEquals(first.tier, (e.tick(100, picker()) as ExecutionDecision.Click).action.expectedLabel)
        assertFalse(e.toString().contains("测试演出"))
        assertFalse(plan().toString().contains("测试演出"))
    }
}
