package local.tickets.companion.execution

import local.tickets.companion.pages.*
import local.tickets.companion.policy.TicketTarget

/** This increment ends at confirmation. No submit/payment action exists in its action vocabulary. */
data class LocalPlan(
    val eventTitle: String,
    val targets: List<TicketTarget>,
    val quantity: Int,
    val maxTotalCents: Long,
    val startAtMs: Long,
    val endAtMs: Long,
    val priorityWindowMs: Long = 180_000,
    val waitForStock: Boolean = false,
    val autoSubmit: Boolean = false,
    val buyerLabels: List<String> = emptyList(),
) {
    fun validate(nowWallMs: Long) {
        require(eventTitle.isNotBlank() && eventTitle.length <= 300 && eventTitle.none { it.isISOControl() }) { "演出名称无效" }
        require(!autoSubmit) { "手机本地版本仅到确认页，尚不支持自动提交" }
        require(quantity in 1..20 && targets.size in 1..20) { "张数或目标数量无效" }
        require((buyerLabels.isEmpty() || buyerLabels.size == quantity) && buyerLabels.all { it.isNotBlank() && it.length <= 300 && it.none(Char::isISOControl) } &&
            buyerLabels.map(DamaiPageParser::normalized).distinct().size == buyerLabels.size) { "观演人名单须与张数一致且不能重复" }
        targets.forEach { target ->
            target.validate()
            require(DamaiPageParser.sessionKey(target.session) != null) { "日期必须包含完整年月日和时分" }
            require(target.unitPriceCents * quantity <= maxTotalCents) { "目标超过总预算" }
        }
        require(maxTotalCents in 1..2_000_000_000L) { "预算无效" }
        require(targets.map { listOf(DamaiPageParser.sessionKey(it.session).toString(), DamaiPageParser.tierKey(it.tier), it.unitPriceCents.toString()) }.distinct().size == targets.size) { "目标重复" }
        require(nowWallMs >= 0 && startAtMs >= 0 && endAtMs > maxOf(startAtMs, nowWallMs)
            && endAtMs - minOf(startAtMs, nowWallMs) <= 48 * 3600_000L
            && startAtMs - nowWallMs <= 24 * 3600_000L) { "任务必须有截止时间，最多48小时" }
        require(priorityWindowMs in 0..3600_000L) { "优先窗口无效" }
    }
    override fun toString() = "LocalPlan(redacted)"
}

enum class ActionKind { OPEN_PICKER, SESSION, TIER, INCREASE, DECREASE, NEXT, BUYER }
class NativeAction internal constructor(val captureId: Long, val nodeIndex: Int, val kind: ActionKind,
                                        val expectedLabel: String = "", internal val beforeQuantity: Int? = null,
                                        internal val expectedSelected: Boolean? = null,
                                        internal val touchNodeIndex: Int? = null) {
    override fun toString() = "NativeAction($kind, redacted)"
}
sealed interface ExecutionDecision {
    data class Wait(val untilElapsedMs: Long, val reason: String) : ExecutionDecision
    class Click(val action: NativeAction) : ExecutionDecision
    data class Finished(val reason: String) : ExecutionDecision
    data class Stopped(val reason: String) : ExecutionDecision
}

/** Pure phone-local state machine. Times after construction are monotonic, never wall-clock polls. */
class LocalExecution(input: LocalPlan, nowWallMs: Long, nowElapsedMs: Long) {
    private val plan = input.copy(targets = input.targets.toList(), buyerLabels = input.buyerLabels.toList())
    private val parser = DamaiPageParser()
    private val start = nowElapsedMs + maxOf(0L, plan.startAtMs - nowWallMs)
    private val end = nowElapsedMs + (plan.endAtMs - nowWallMs)
    private val priorityEnd = nowElapsedMs + maxOf(0L, plan.startAtMs + plan.priorityWindowMs - nowWallMs)
    private var lastNow = nowElapsedMs
    private var lastCapture = -1L
    private var terminal: ExecutionDecision? = null
    private var pending: NativeAction? = null
    private var pendingDispatched = false
    private var actionAt = 0L
    private var actions = 0
    private var currentTarget = 0
    private var nextCandidateAt = 0L
    private var passiveRound = 0
    private var transientSince: Long? = null
    private var confirmationEntered = false
    private var buyerIdentities: List<String>? = null
    private var pendingBuyerBefore: Map<String, Boolean>? = null

    init { plan.validate(nowWallMs); require(nowElapsedMs >= 0) }
    fun stop(reason: String): ExecutionDecision {
        if (terminal == null) terminal = ExecutionDecision.Stopped(reason)
        pending = null
        pendingDispatched = false
        return terminal!!
    }
    fun deadline() = end
    /** Read-only settling after one actual dispatch. Never returns a new click or extends the action timeout. */
    internal fun nativeReadUnavailable(now: Long, failure: NativeReadFailure): ExecutionDecision {
        terminal?.let { return it }
        if (now < lastNow) return stop("monotonic_clock_regressed")
        lastNow = now
        if (now >= end) return stop("deadline")
        if (pending != null && pendingDispatched && failure.transientAfterAction && now - actionAt in 0 until 500L)
            return wait(minOf(now + 50, actionAt + 500), "waiting_native_after_action")
        return stop("native_page_unavailable:${failure.name.lowercase()}")
    }

    /** Starts effect timing at dispatch, not before the second visual observation. */
    fun actionDispatched(action: NativeAction, now: Long): Boolean {
        if (terminal != null || pending !== action || pendingDispatched || now < lastNow || now >= end) return false
        pendingDispatched = true
        actionAt = now
        lastNow = now
        return true
    }
    fun tick(now: Long, snapshot: NodeSnapshot, visual: VisualEvidence? = null): ExecutionDecision {
        terminal?.let { return it }
        if (now < lastNow) return stop("monotonic_clock_regressed")
        lastNow = now
        if (now >= end) return stop("deadline")
        if (snapshot.captureId <= lastCapture) return stop("stale_snapshot")
        lastCapture = snapshot.captureId
        if (!snapshot.complete) return stop("incomplete_snapshot")
        if (visual != null && (visual.captureId != snapshot.captureId || visual.capturedAtElapsedMs > now || now - visual.capturedAtElapsedMs > 2000))
            return stop("stale_visual_evidence")
        val page = parser.parse(snapshot, visual)
        if (page.kind == PageKind.CONFIRMATION) return confirmation(now, snapshot, page)
        if (confirmationEntered) return stop("confirmation_page_left")
        if (page.kind == PageKind.QUEUE || page.kind == PageKind.LOADING) {
            val since = transientSince ?: now.also { transientSince = it }
            if (now - since >= 30_000) return stop("queue_or_loading_timeout")
            return wait(now + 250, "waiting_page")
        }
        transientSince = null
        if (page.blockers.isNotEmpty()) return stop("unsupported_or_blocked_page")
        if (DamaiPageParser.eventKey(page.eventTitle.known().orEmpty()) != DamaiPageParser.eventKey(plan.eventTitle)) return stop("event_mismatch")
        if (now < start) return wait(minOf(start, now + 1000), "waiting_start")
        pending?.let { action ->
            val effect = when (action.kind) {
                ActionKind.OPEN_PICKER -> page.kind == PageKind.PICKER
                ActionKind.NEXT -> false // Confirmation was handled above; never repeat this click.
                ActionKind.BUYER -> return stop("buyer_action_outside_confirmation")
                ActionKind.SESSION -> page.sessions.count { it.selection.selected() } == 1 &&
                    page.sessions.any { it.selection.selected() && sameSession(it.label, action.expectedLabel) }
                ActionKind.TIER -> page.tiers.count { it.selection.selected() } == 1 &&
                    page.tiers.any { it.selection.selected() && same(it.label, action.expectedLabel) }
                ActionKind.INCREASE, ActionKind.DECREASE -> {
                    val expected = action.beforeQuantity!! + if (action.kind == ActionKind.INCREASE) 1 else -1
                    val current = page.quantity.known()
                    if (current != action.beforeQuantity && current != expected) return stop("quantity_effect_mismatch")
                    current == expected
                }
            }
            if (!effect) {
                if (now - actionAt >= if (action.kind == ActionKind.NEXT || action.kind == ActionKind.OPEN_PICKER) 10_000 else 2000)
                    return stop("action_effect_unconfirmed")
                return wait(now + 80, "waiting_action_effect")
            }
            pending = null
        }
        val target = plan.targets[currentTarget]
        if (page.kind == PageKind.DETAIL) {
            if (page.detailAvailability == Availability.UPCOMING) return wait(now + 150, "waiting_sale")
            if (page.detailAvailability == Availability.SOLD_OUT) return stockWait(now)
            if (page.detailAvailability != Availability.AVAILABLE) return stop("detail_not_purchasable")
            return click(snapshot, uniqueButton(snapshot, DamaiPageParser.DETAIL_BUTTON, BUY_LABELS, visual), ActionKind.OPEN_PICKER, now)
        }
        if (page.kind != PageKind.PICKER) return stop("unsupported_page")
        val session = page.sessions.singleOrNull { sameSession(it.label, target.session) } ?: return stop("target_session_missing_or_ambiguous")
        if (session.availability == Availability.SOLD_OUT || session.availability == Availability.UPCOMING) return stockWait(now)
        if (session.availability != Availability.AVAILABLE || session.selection == SelectionEvidence.UNKNOWN) return stop("session_unverified")
        if (!session.selection.selected()) return click(snapshot, clickableWithin(snapshot, session.nodeIndex), ActionKind.SESSION, now, session.label)
        // Association and selection evidence are checked before taking a tier or advancing.
        if (page.sessions.count { it.selection.selected() } != 1 ||
            page.sessions.any { it.selection == SelectionEvidence.UNKNOWN } || page.tiers.any { it.selection == SelectionEvidence.UNKNOWN } ||
            page.tiers.count { it.selection.selected() } > 1) return stop("selection_ambiguous")
        val tier = page.tiers.singleOrNull { same(it.label, target.tier) && it.unitPriceCents == target.unitPriceCents }
            ?: return stop("target_tier_missing_or_ambiguous")
        if (tier.availability == Availability.SOLD_OUT || tier.availability == Availability.UPCOMING) {
            if (!plan.waitForStock) return stockWait(now)
            // Only the next authorized target may use this shortcut. A same-session
            // card must never jump over a higher-priority target in another session.
            if (now >= priorityEnd && now >= nextCandidateAt && plan.targets.size > 1) {
                val available = ((currentTarget + 1) % plan.targets.size).takeIf { i ->
                    sameSession(plan.targets[i].session, session.label) && page.tiers.count {
                        same(it.label, plan.targets[i].tier) && it.unitPriceCents == plan.targets[i].unitPriceCents &&
                            it.availability == Availability.AVAILABLE
                    } == 1
                }
                if (available != null) {
                    currentTarget = available
                    val choice = page.tiers.single { same(it.label, plan.targets[available].tier) && it.unitPriceCents == plan.targets[available].unitPriceCents }
                    if (!choice.selection.selected())
                        return click(snapshot, clickableWithin(snapshot, choice.nodeIndex), ActionKind.TIER, now, choice.label)
                    return wait(now + 50, "alternative_selected")
                }
            }
            return stockWait(now)
        }
        if (tier.availability != Availability.AVAILABLE) return stop("tier_unavailable")
        if (!tier.selection.selected()) return click(snapshot, clickableWithin(snapshot, tier.nodeIndex), ActionKind.TIER, now, tier.label)
        val quantity = page.quantity.known() ?: return stop("quantity_unverified")
        if (quantity != plan.quantity) {
            val increase = quantity < plan.quantity
            val row = snapshot.nodes.singleOrNull { it.resourceId == DamaiPageParser.PREFIX + "layout_sku_num_ll" } ?: return stop("quantity_unverified")
            val button = snapshot.subtree(row).singleOrNull { it.resourceId == DamaiPageParser.PREFIX + if (increase) "img_jia" else "img_jian" }
            return click(snapshot, button?.let { clickableWithin(snapshot, it.index) }, if (increase) ActionKind.INCREASE else ActionKind.DECREASE, now, beforeQuantity = quantity)
        }
        return click(snapshot, uniqueButton(snapshot, "btn_buy_view", setOf("确定", "立即购买", "立即预订"), visual), ActionKind.NEXT, now)
    }
    private fun confirmation(now: Long, snapshot: NodeSnapshot, page: PageObservation): ExecutionDecision {
        if (!confirmationEntered) {
            if (pending?.kind != ActionKind.NEXT) return stop("confirmation_without_verified_selection")
            confirmationEntered = true
            pending = null
        }
        val review = reviewConfirmation(snapshot, page, plan, plan.targets[currentTarget])
        if (review is ConfirmationReview.Rejected) return stop(review.reason)
        review as ConfirmationReview.Verified
        val identities = review.buyers.map { DamaiPageParser.normalized(it.label) }
        if (buyerIdentities != null && buyerIdentities != identities) return stop("buyer_list_changed")
        buyerIdentities = identities
        val observed = review.buyers.associate { DamaiPageParser.normalized(it.label) to it.selected }
        pending?.let { action ->
            if (action.kind != ActionKind.BUYER) return stop("unexpected_confirmation_action")
            val key = DamaiPageParser.normalized(action.expectedLabel)
            val before = pendingBuyerBefore ?: return stop("buyer_effect_unverified")
            if (observed.any { (identity, selected) -> identity != key && before[identity] != selected }) return stop("buyer_selection_changed")
            if (observed[key] != action.expectedSelected) {
                if (now - actionAt >= 2000) return stop("action_effect_unconfirmed")
                return wait(now + 80, "waiting_buyer_effect")
            }
            pending = null; pendingBuyerBefore = null
        }
        val change = review.change
        if (change == null) {
            terminal = ExecutionDecision.Finished("confirmation_verified_manual_submit")
            return terminal!!
        }
        pendingBuyerBefore = observed
        return click(snapshot, change.first.index, ActionKind.BUYER, now, change.first.label, expectedSelected = change.second)
    }
    private fun stockWait(now: Long): ExecutionDecision {
        if (!plan.waitForStock) return stop("sold_out_or_upcoming")
        if (now >= priorityEnd && plan.targets.size > 1 && now >= nextCandidateAt) {
            currentTarget = (currentTarget + 1) % plan.targets.size
            nextCandidateAt = minOf(end, now + 2000)
            return wait(now + 100, "checking_authorized_alternative")
        }
        passiveRound = minOf(passiveRound + 1, 5)
        val until = minOf(now + minOf(1000L shl passiveRound, 30_000L), if (now < priorityEnd) priorityEnd else end)
        return wait(until, "waiting_stock_passive")
    }
    private fun wait(until: Long, reason: String) = ExecutionDecision.Wait(minOf(until, end), reason)
    private fun click(snapshot: NodeSnapshot, index: Int?, kind: ActionKind, now: Long, label: String = "", beforeQuantity: Int? = null, expectedSelected: Boolean? = null): ExecutionDecision {
        if (index == null) return stop("native_click_target_unavailable")
        if (++actions > 200) return stop("action_limit")
        val node = snapshot.nodes[index]
        val touchIndex = ScopedTouchTarget.nodeIndex(snapshot, index, kind)
        val wrappedCard = kind in setOf(ActionKind.SESSION, ActionKind.TIER) && node.resourceId.isEmpty() &&
            node.children.singleOrNull()?.let { snapshot.nodes[it].resourceId == DamaiPageParser.PREFIX + "ll_perform_item" } == true
        if (wrappedCard && touchIndex == null) return stop("scoped_touch_target_unavailable")
        if ((!node.clickable && touchIndex == null) || !snapshot.visible(node) || !snapshot.enabled(node)) return stop("native_click_target_unavailable")
        val action = NativeAction(snapshot.captureId, index, kind, label, beforeQuantity, expectedSelected, touchIndex)
        pending = action
        pendingDispatched = false
        actionAt = now
        return ExecutionDecision.Click(action)
    }
    private fun uniqueButton(snapshot: NodeSnapshot, id: String, labels: Set<String>, visual: VisualEvidence?): Int? {
        val node = snapshot.nodes.singleOrNull { it.resourceId == DamaiPageParser.PREFIX + id && snapshot.visible(it) } ?: return null
        val words = (snapshot.subtree(node).filter { snapshot.visible(it) }.flatMap { listOf(it.text, it.description) } + visual?.wordsIn(node.bounds).orEmpty())
            .filter { it.isNotBlank() }.map(DamaiPageParser::normalized).distinct()
        if (words.size != 1 || words.single() !in labels) return null
        return clickableWithin(snapshot, node.index)
    }
    private fun clickableWithin(snapshot: NodeSnapshot, index: Int): Int? {
        val node = snapshot.nodes[index]
        if (node.clickable && snapshot.visible(node) && snapshot.enabled(node)) return index
        val descendants = snapshot.subtree(node).filter { it.clickable && snapshot.visible(it) && snapshot.enabled(it) }
        if (descendants.isNotEmpty()) return descendants.singleOrNull()?.index
        if (ScopedTouchTarget.nodeIndex(snapshot, index, ActionKind.OPEN_PICKER) == index) return index
        // Measured Damai cards expose a clickable immediate anonymous wrapper.
        // Its native click was a no-op on device; scoped touch, where valid, is chosen before dispatch.
        // This is not an arbitrary ancestor fallback: the wrapper must own this card alone,
        // sit directly in a known option container and remain fully visible and bounded.
        if (node.resourceId != DamaiPageParser.PREFIX + "ll_perform_item") return null
        val owner = node.parentIndex?.let { snapshot.nodes[it] } ?: return null
        val container = owner.parentIndex?.let { snapshot.nodes[it] } ?: return null
        if (owner.resourceId.isNotEmpty() || owner.text.isNotBlank() || owner.description.isNotBlank() ||
            owner.children != listOf(node.index) || !owner.clickable || owner.scrollable ||
            !snapshot.visible(owner) || !snapshot.enabled(owner) ||
            !owner.bounds.contains(node.bounds) || !container.bounds.contains(owner.bounds) ||
            container.resourceId !in setOf(DamaiPageParser.PREFIX + DamaiPageParser.SESSION_LIST,
                DamaiPageParser.PREFIX + DamaiPageParser.TIER_LIST)) return null
        return owner.index
    }
    private fun same(a: String, b: String) = DamaiPageParser.tierKey(a) == DamaiPageParser.tierKey(b)
    private fun sameSession(a: String, b: String) = DamaiPageParser.sessionKey(a)?.let { it == DamaiPageParser.sessionKey(b) } == true
    override fun toString() = "LocalExecution(redacted)"
    companion object { private val BUY_LABELS = setOf("立即购票", "立即购买", "立即预订", "选票购买", "立即抢票") }
}
