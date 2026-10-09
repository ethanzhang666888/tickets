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
                       event: String = "测试演出", capture: Long = 1,
                       extraSession: String? = null, wrapTiers: Boolean = false,
                       mutate: (MutableList<NodeSpec>) -> Unit = {}): NodeSnapshot {
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
        if (wrapTiers) for (index in session..nodes.lastIndex) nodes[index] = nodes[index].copy(bounds = NodeBounds(20, 50, 400, 150))
        if (extraSession != null) {
            val secondSession = add("ll_perform_item", parent = sessions, chosen = false, click = true)
            add(text = extraSession, parent = secondSession)
        }
        val tiers = add("project_detail_perform_price_flowlayout")
        for ((i, target) in listOf(first, other).withIndex()) {
            val owner = if (wrapTiers) add(parent = tiers, click = true) else tiers
            val tier = add("ll_perform_item", parent = owner, chosen = selected == i, click = !wrapTiers)
            add(text = target.tier, parent = tier)
            if (soldOut && i == 0) add(text = "缺货", parent = tier)
            if (wrapTiers) for (index in owner..nodes.lastIndex)
                nodes[index] = nodes[index].copy(bounds = NodeBounds(20 + i * 220, 200, 200 + i * 220, 320))
        }
        val row = add("layout_sku_num_ll")
        add("tv_num", "${quantity}张", row)
        add("img_jia", parent = row, click = true)
        add("img_jian", parent = row, click = true)
        if (wrapTiers) for (index in row..nodes.lastIndex) nodes[index] = nodes[index].copy(bounds = NodeBounds(20, 400, 580, 550))
        val next = add("btn_buy_view", "确定", click = true)
        if (wrapTiers) nodes[next] = nodes[next].copy(bounds = NodeBounds(20, 700, 580, 850))
        mutate(nodes)
        return NodeSnapshot.create(capture, nodes, true)
    }
    private fun confirmation(capture: Long = 5, buyerSelected: Boolean = true, event: String = "测试演出", overlay: String = ""): NodeSnapshot {
        val nodes = mutableListOf<NodeSpec>()
        fun add(id: String = "", text: String = "", parent: Int? = null, checked: Boolean? = null, click: Boolean = false): Int {
            nodes += NodeSpec(parentIndex = parent, resourceId = if (id.isEmpty()) "" else "cn.damai:id/$id",
                text = text, checked = checked, clickable = click, bounds = NodeBounds(0, 0, 600, 900))
            return nodes.lastIndex
        }
        add("order_activity_title", "确认购买")
        val content = add("recycler_view")
        val header = add(parent = content)
        add(text = event, parent = header)
        add(text = "2026.11.07 19:00", parent = header)
        add(text = "¥880票档", parent = header)
        add(text = "×1张", parent = header)
        val list = add("recycler_main", parent = content)
        val row = add("layout_main", parent = list)
        add("text_name", "测试甲", row)
        add("text_num_type", "身份证", row)
        add("text_num", "000************000", row)
        add("checkbox", parent = row, checked = buyerSelected, click = true)
        val bottom = add("bottom_layout")
        add(text = "¥880", parent = bottom)
        if (overlay.isNotEmpty()) add(text = overlay)
        return NodeSnapshot.create(capture, nodes, true)
    }

    @Test fun measuredSingleCardWrapperOwnsClickAndStillRequiresCardEffect() {
        val snapshot = picker(wrapTiers = true)
        val e = engine()
        val decision = e.tick(100, snapshot) as ExecutionDecision.Click
        val owner = snapshot.nodes[decision.action.nodeIndex]
        assertEquals(ActionKind.TIER, decision.action.kind)
        assertEquals("", owner.resourceId)
        assertEquals(1, owner.children.size)
        assertEquals("cn.damai:id/ll_perform_item", snapshot.nodes[owner.children.single()].resourceId)
        assertEquals(owner.children.single(), decision.action.touchNodeIndex)
        assertTrue(e.tick(150, picker(wrapTiers = true, capture = 2)) is ExecutionDecision.Wait)
        assertEquals(ActionKind.NEXT, (e.tick(200, picker(wrapTiers = true, selected = 0, capture = 3)) as ExecutionDecision.Click).action.kind)
    }
    @Test fun rejectedWrapperTouchCannotFallBackToNativeClick() {
        for (fault in listOf("small", "overlap")) {
            val snapshot = picker(wrapTiers = true) { nodes ->
                val container = nodes.indexOfFirst { it.resourceId == "cn.damai:id/${DamaiPageParser.TIER_LIST}" }
                val owner = nodes.indexOfFirst { it.parentIndex == container }
                val card = nodes.indexOfFirst { it.parentIndex == owner }
                if (fault == "small") {
                    for (index in owner..card + 1) nodes[index] = nodes[index].copy(bounds = NodeBounds(20, 200, 40, 220))
                } else nodes += NodeSpec(resourceId = "cn.damai:id/unknown_action", clickable = true, bounds = nodes[card].bounds)
            }
            assertTrue(fault, engine().tick(100, snapshot) is ExecutionDecision.Stopped)
        }
    }

    @Test fun wrapperFallbackRejectsUnknownDisabledClippedOrSharedOwners() {
        for (fault in listOf("id", "text", "disabled", "hidden", "scroll", "clipped", "extra_child", "clickable_child")) {
            val snapshot = picker(wrapTiers = true) { nodes ->
                val container = nodes.indexOfFirst { it.resourceId == "cn.damai:id/project_detail_perform_price_flowlayout" }
                val owner = nodes.indexOfFirst { it.parentIndex == container }
                when (fault) {
                    "id" -> nodes[owner] = nodes[owner].copy(resourceId = "cn.damai:id/unknown")
                    "text" -> nodes[owner] = nodes[owner].copy(text = "其他操作")
                    "disabled" -> nodes[owner] = nodes[owner].copy(enabled = false)
                    "hidden" -> nodes[owner] = nodes[owner].copy(visible = false)
                    "scroll" -> nodes[owner] = nodes[owner].copy(scrollable = true)
                    "clipped" -> nodes[owner] = nodes[owner].copy(bounds = NodeBounds(-1, 0, 600, 900))
                    "extra_child" -> nodes += NodeSpec(parentIndex = owner, bounds = nodes[owner].bounds)
                    "clickable_child" -> {
                        val card = nodes.indexOfFirst { it.parentIndex == owner }
                        repeat(2) { nodes += NodeSpec(parentIndex = card, clickable = true, bounds = nodes[owner].bounds) }
                    }
                }
            }
            assertTrue(fault, engine().tick(100, snapshot) is ExecutionDecision.Stopped)
        }
    }
    @Test fun verifiedSelfDrawnDetailUsesTouchButUnknownLabelAndOtherKindsDoNot() {
        fun detail(label: String) = NodeSnapshot.create(1, listOf(
            NodeSpec(bounds = NodeBounds(0, 0, 1080, 2340)),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}", text = "测试演出", bounds = NodeBounds(50, 200, 1000, 400)),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_BUTTON}", text = label, bounds = NodeBounds(100, 2100, 950, 2250))
        ), true)
        val action = (engine().tick(100, detail("立即预订")) as ExecutionDecision.Click).action
        assertEquals(ActionKind.OPEN_PICKER, action.kind)
        assertEquals(2, action.touchNodeIndex)
        assertTrue(engine().tick(100, detail("选座购买")) is ExecutionDecision.Stopped)
        assertNull((engine().tick(100, picker(selected = 0)) as ExecutionDecision.Click).action.touchNodeIndex)
    }

    @Test fun selectsTargetThenWaitsForEffectBeforeEnteringConfirmation() {
        val e = engine()
        val choose = e.tick(100, picker()) as ExecutionDecision.Click
        assertEquals(ActionKind.TIER, choose.action.kind)
        assertTrue(e.tick(150, picker(capture = 2)) is ExecutionDecision.Wait)
        val next = e.tick(200, picker(selected = 0, capture = 3)) as ExecutionDecision.Click
        assertEquals(ActionKind.NEXT, next.action.kind)
        assertTrue(e.tick(250, picker(selected = 0, capture = 4)) is ExecutionDecision.Wait)
        assertEquals("confirmation_verified_manual_submit", (e.tick(300, confirmation()) as ExecutionDecision.Finished).reason)
        assertTrue(e.tick(400, picker(capture = 6)) is ExecutionDecision.Finished)
    }
    @Test fun cashierOverlayCannotAllowBuyerClickOrSuccessfulConfirmation() {
        for (marker in listOf("收银台", "立即支付", "确认支付", "支付密码", "立即付款", "Pay now")) {
            for (selected in listOf(false, true)) {
                val e = engine()
                assertEquals(ActionKind.NEXT, (e.tick(100, picker(selected = 0)) as ExecutionDecision.Click).action.kind)
                assertTrue(marker, e.tick(200, confirmation(2, selected, overlay = marker)) is ExecutionDecision.Stopped)
            }
        }
    }
    @Test fun confirmationWithoutVerifiedSelectionStops() {
        assertTrue(engine().tick(100, confirmation()) is ExecutionDecision.Stopped)
    }
    @Test fun confirmationRequiresOrderVerificationAndSelectsBuyerExactlyOnce() {
        val e = engine()
        assertEquals(ActionKind.NEXT, (e.tick(100, picker(selected = 0)) as ExecutionDecision.Click).action.kind)
        val buyer = e.tick(200, confirmation(2, buyerSelected = false)) as ExecutionDecision.Click
        assertEquals("BUYER", buyer.action.kind.name)
        assertTrue(e.tick(250, confirmation(3, buyerSelected = false)) is ExecutionDecision.Wait)
        assertEquals("confirmation_verified_manual_submit", (e.tick(300, confirmation(4)) as ExecutionDecision.Finished).reason)
        val wrong = engine()
        wrong.tick(100, picker(selected = 0))
        assertTrue(wrong.tick(200, confirmation(2, event = "另一演出")) is ExecutionDecision.Stopped)
    }
    @Test fun verifiedNextWaitsThroughEmptyNativeLoadingShellWithoutAnotherClick() {
        val e = engine()
        val next = (e.tick(100, picker(selected = 0)) as ExecutionDecision.Click).action
        assertTrue(e.actionDispatched(next, 110))
        val full = NodeBounds(0, 0, 600, 900)
        val loading = NodeSnapshot.create(2, listOf(NodeSpec(bounds = full), NodeSpec(parentIndex = 0, bounds = full),
            NodeSpec(parentIndex = 1, resourceId = "android:id/content", bounds = full), NodeSpec(parentIndex = 2, bounds = full),
            NodeSpec(parentIndex = 3, resourceId = "cn.damai:id/classics_arrow", bounds = full)), true)
        assertTrue(e.tick(200, loading) is ExecutionDecision.Wait)
        assertEquals("confirmation_verified_manual_submit", (e.tick(400, confirmation(3)) as ExecutionDecision.Finished).reason)
        val unsupported = engine()
        val pending = (unsupported.tick(100, picker(selected = 0)) as ExecutionDecision.Click).action
        assertTrue(unsupported.actionDispatched(pending, 110))
        assertTrue(unsupported.tick(200, NodeSnapshot.create(2, listOf(NodeSpec(text = "未知内容", bounds = full)), true)) is ExecutionDecision.Stopped)
    }

    @Test fun transientNativeReadAfterDispatchWaitsWithoutRepeatingAction() {
        val e = engine()
        val choose = (e.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(e.actionDispatched(choose, 200))
        assertTrue(e.nativeReadUnavailable(210, NativeReadFailure.TREE_CHANGED) is ExecutionDecision.Wait)
        assertTrue(e.tick(250, picker(capture = 2)) is ExecutionDecision.Wait)
        assertEquals(ActionKind.NEXT, (e.tick(300, picker(selected = 0, capture = 3)) as ExecutionDecision.Click).action.kind)
        // A newly proposed NEXT has not been dispatched: it gets no read retry window.
        assertTrue(e.nativeReadUnavailable(310, NativeReadFailure.ROOT_UNAVAILABLE) is ExecutionDecision.Stopped)

        val order = engine()
        val next = (order.tick(100, picker(selected = 0)) as ExecutionDecision.Click).action
        assertTrue(order.actionDispatched(next, 110))
        val buyer = (order.tick(200, confirmation(2, buyerSelected = false)) as ExecutionDecision.Click).action
        assertTrue(order.actionDispatched(buyer, 210))
        assertTrue(order.nativeReadUnavailable(220, NativeReadFailure.TREE_CHANGED) is ExecutionDecision.Wait)
        assertEquals("confirmation_verified_manual_submit", (order.tick(300, confirmation(3)) as ExecutionDecision.Finished).reason)
    }

    @Test fun nativeReadSettlingIsBoundedAndNeverAppliesToUnsafeOrUnsentActions() {
        for (failure in NativeReadFailure.entries) {
            val e = engine()
            val action = (e.tick(100, picker()) as ExecutionDecision.Click).action
            assertTrue(e.actionDispatched(action, 200))
            val result = e.nativeReadUnavailable(210, failure)
            if (failure.transientAfterAction) assertTrue(failure.name, result is ExecutionDecision.Wait)
            else assertTrue(failure.name, result is ExecutionDecision.Stopped)
        }
        val unsent = engine()
        unsent.tick(100, picker())
        assertTrue(unsent.nativeReadUnavailable(110, NativeReadFailure.TREE_CHANGED) is ExecutionDecision.Stopped)
        val idle = engine()
        assertTrue(idle.nativeReadUnavailable(100, NativeReadFailure.ROOT_UNAVAILABLE) is ExecutionDecision.Stopped)
        val bounded = engine()
        val action = (bounded.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(bounded.actionDispatched(action, 200))
        assertFalse("Duplicate dispatch must not extend the settling window", bounded.actionDispatched(action, 650))
        assertEquals(700L, (bounded.nativeReadUnavailable(699, NativeReadFailure.TREE_CHANGED) as ExecutionDecision.Wait).untilElapsedMs)
        assertTrue(bounded.nativeReadUnavailable(700, NativeReadFailure.TREE_CHANGED) is ExecutionDecision.Stopped)
        val timedOut = engine()
        val timedOutAction = (timedOut.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(timedOut.actionDispatched(timedOutAction, 200))
        // Runtime starts the read around dispatch+40ms; a >500ms read cannot fit the settling window.
        assertEquals("native_page_unavailable:read_timeout", (timedOut.nativeReadUnavailable(741, NativeReadFailure.READ_TIMEOUT) as ExecutionDecision.Stopped).reason)
        val expired = LocalExecution(plan().copy(endAtMs = 1000040), 1000000, 100)
        val shortAction = (expired.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(expired.actionDispatched(shortAction, 110))
        assertEquals(140L, (expired.nativeReadUnavailable(139, NativeReadFailure.TREE_CHANGED) as ExecutionDecision.Wait).untilElapsedMs)
        assertEquals("deadline", (expired.nativeReadUnavailable(140, NativeReadFailure.TREE_CHANGED) as ExecutionDecision.Stopped).reason)
        val cancelled = engine()
        val cancelledAction = (cancelled.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(cancelled.actionDispatched(cancelledAction, 110))
        cancelled.stop("user_stop")
        assertEquals("user_stop", (cancelled.nativeReadUnavailable(120, NativeReadFailure.TREE_CHANGED) as ExecutionDecision.Stopped).reason)
        val regressed = engine()
        val regressedAction = (regressed.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(regressed.actionDispatched(regressedAction, 200))
        assertEquals("monotonic_clock_regressed", (regressed.nativeReadUnavailable(199, NativeReadFailure.TREE_CHANGED) as ExecutionDecision.Stopped).reason)
        val incomplete = engine()
        val incompleteAction = (incomplete.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(incomplete.actionDispatched(incompleteAction, 110))
        assertTrue(incomplete.nativeReadUnavailable(120, NativeReadFailure.TREE_CHANGED) is ExecutionDecision.Wait)
        assertEquals("incomplete_snapshot", (incomplete.tick(130, NodeSnapshot.create(2, emptyList(), false)) as ExecutionDecision.Stopped).reason)
    }

    @Test fun effectTimeoutStartsAtActualDispatchAfterVisualRevalidation() {
        val e = engine()
        val action = (e.tick(100, picker()) as ExecutionDecision.Click).action
        assertTrue(e.actionDispatched(action, 2500))
        assertTrue(e.tick(2600, picker(capture = 2)) is ExecutionDecision.Wait)
        assertTrue(e.tick(4500, picker(capture = 3)) is ExecutionDecision.Stopped)
        assertFalse(e.actionDispatched(action, 4501))
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
    @Test fun disabledWaitingCannotChooseAnAlreadyVisibleAlternative() {
        val e = engine(plan(listOf(first, other), priority = 0).copy(waitForStock = false))
        assertEquals("sold_out_or_upcoming", (e.tick(100, picker(soldOut = true)) as ExecutionDecision.Stopped).reason)
    }
    @Test fun alternativesRespectPriorityAcrossSessions() {
        val second = first.copy(session = "2026-11-08 19:00")
        val e = engine(plan(listOf(first, second, other), priority = 0))
        assertTrue(e.tick(100, picker(soldOut = true, extraSession = second.session)) is ExecutionDecision.Wait)
        val choice = e.tick(200, picker(soldOut = true, extraSession = second.session, capture = 2)) as ExecutionDecision.Click
        assertEquals(ActionKind.SESSION, choice.action.kind)
        assertEquals(second.session, choice.action.expectedLabel)
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
