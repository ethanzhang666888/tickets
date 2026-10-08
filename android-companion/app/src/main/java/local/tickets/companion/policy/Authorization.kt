package local.tickets.companion.policy

import java.text.Normalizer

private fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("\\s+"), "")
private fun label(value: String) = value.isNotBlank() && value.length <= 300 && value.none { it.isISOControl() }

/** Never log the name or the visible document digits through a data-class toString. */
data class BuyerIdentity(val name: String, val documentType: String, val maskedDocument: String) {
    fun validate() {
        require(listOf(name, documentType, maskedDocument).all(::label)) { "观演人标识不完整。" }
        val mask = maskedDocument.count { it == '*' || it == '＊' || it == '•' }
        require(mask >= 2 && maskedDocument.count { it.isLetterOrDigit() } >= 2) { "只接受有区分信息的脱敏证件标识。" }
    }
    internal fun identity() = listOf(name, documentType, maskedDocument).map(::key)
    override fun toString() = "BuyerIdentity(redacted)"
}

data class TicketTarget(val session: String, val tier: String, val unitPriceCents: Long) {
    internal fun identity() = listOf(key(session), key(tier), unitPriceCents.toString())
    fun validate() {
        require(label(session) && label(tier) && unitPriceCents in 1..100_000_000L) { "场次、票档或单价无效。" }
    }
}

data class PurchaseAuthorization(
    val eventTitle: String,
    val targets: List<TicketTarget>,
    val quantity: Int,
    val buyers: List<BuyerIdentity>,
    val maxTotalCents: Long,
    val startAtMs: Long,
    val endAtMs: Long,
    val autoSubmit: Boolean = false,
) {
    fun validate(observedCatalog: List<TicketTarget>, nowMs: Long) {
        require(label(eventTitle) && quantity in 1..20) { "演出或张数无效。" }
        require(targets.size in 1..50 && buyers.size == quantity) { "请明确授权目标和固定观演人。" }
        require(maxTotalCents in 1..2_000_000_000L) { "总预算无效。" }
        require(nowMs >= 0 && startAtMs >= 0 && endAtMs > startAtMs && endAtMs > nowMs
            && endAtMs - startAtMs <= 7 * 86400_000L) { "执行必须有未来截止时间，最多持续七天。" }
        targets.forEach { it.validate() }
        buyers.forEach { it.validate() }
        require(targets.map { it.identity() }.distinct().size == targets.size) { "授权组合重复。" }
        require(buyers.map { it.identity() }.distinct().size == buyers.size) { "观演人身份存在歧义。" }
        require(targets.all { target -> observedCatalog.count { it.identity() == target.identity() } == 1 }) { "授权场次与票档组合尚未从手机唯一核实。" }
        require(targets.all { it.unitPriceCents * quantity <= maxTotalCents }) { "授权目标超过总预算。" }
    }
    override fun toString() = "PurchaseAuthorization(redacted)"
}

data class BuyerRow(val identity: BuyerIdentity, val selected: Boolean, val enabled: Boolean = true)
data class OrderObservation(
    val eventTitle: String,
    val target: TicketTarget?,
    val quantity: Int?,
    val totalCents: Long?,
    val feesCents: Long?,
    val buyers: List<BuyerRow>,
    val buyerListComplete: Boolean,
)

sealed interface OrderDecision {
    data class Pause(val reason: String) : OrderDecision
    data class SetBuyer(val buyer: BuyerIdentity, val selected: Boolean) : OrderDecision
    data object ReadyForManualSubmit : OrderDecision
    /** Decision only. The executor MUST durably fence submission before issuing a click. */
    data object SubmitOnce : OrderDecision
}

/** Pure authorization policy; no device or network side effects. */
fun authorizeOrder(plan: PurchaseAuthorization, page: OrderObservation, submissionBlocked: Boolean = false): OrderDecision {
    fun pause(reason: String) = OrderDecision.Pause(reason)
    if (submissionBlocked) return pause("已有待核实的提交记录。")
    // Revalidate intrinsic constraints even if a caller forgot its prepare-time validation.
    try { plan.validate(plan.targets, plan.startAtMs) } catch (_: IllegalArgumentException) { return pause("授权配置无效。") }
    val target = page.target ?: return pause("确认页票档身份未核实。")
    if (key(plan.eventTitle) != key(page.eventTitle) || page.quantity != plan.quantity
        || plan.targets.count { it.identity() == target.identity() } != 1) return pause("演出、场次、票档或张数不一致。")
    val total = page.totalCents ?: return pause("应付总额未核实。")
    val fees = page.feesCents ?: return pause("费用构成未核实。")
    if (fees < 0 || fees > plan.maxTotalCents || total <= 0 || total > plan.maxTotalCents
        || target.unitPriceCents * plan.quantity + fees != total) return pause("金额或费用超出授权。")
    if (!page.buyerListComplete || page.buyers.isEmpty()) return pause("观演人列表未完整读取。")
    try { page.buyers.forEach { it.identity.validate() } } catch (_: IllegalArgumentException) { return pause("观演人标识无法核实。") }
    val rows = page.buyers.groupBy { it.identity.identity() }
    if (rows.values.any { it.size != 1 }) return pause("观演人存在重复或脱敏身份歧义。")
    val wanted = plan.buyers.map { it.identity() }.toSet()
    if (!rows.keys.containsAll(wanted)) return pause("目标观演人缺失，请人工处理。")
    // First deselect any other people; never briefly exceed the approved headcount.
    val extra = page.buyers.firstOrNull { it.selected && it.identity.identity() !in wanted }
    if (extra != null) return if (extra.enabled) OrderDecision.SetBuyer(extra.identity, false) else pause("无法取消非目标观演人。")
    val missing = plan.buyers.firstOrNull { rows.getValue(it.identity()).single().selected.not() }
    if (missing != null) return if (rows.getValue(missing.identity()).single().enabled) OrderDecision.SetBuyer(missing, true) else pause("目标观演人不可选。")
    if (page.buyers.count { it.selected } != plan.quantity) return pause("观演人数不一致。")
    return if (plan.autoSubmit) OrderDecision.SubmitOnce else OrderDecision.ReadyForManualSubmit
}

enum class Stock { AVAILABLE, SOLD_OUT, UPCOMING, LOADING, QUEUE, RATE_LIMITED, RISK, UNKNOWN }
sealed interface RetryDecision {
    data class Wait(val untilMs: Long) : RetryDecision
    /** Adapter must perform a verified refresh; another OCR read is not a refresh. */
    data class Refresh(val target: TicketTarget) : RetryDecision
    data class Purchase(val target: TicketTarget) : RetryDecision
    data class Stop(val reason: String) : RetryDecision
}

/** Bounded scheduling policy. Not an executor and never a background infinite loop. */
class RetrySchedule(authorization: PurchaseAuthorization) {
    private val plan = authorization.copy(targets = authorization.targets.toList(), buyers = authorization.buyers.toList())
    private var index = 0
    private var round = 0
    private var seenRevision = -1L
    private var awaitingRevision = false
    private var refreshIssuedAt = 0L
    private var nextCheck = plan.startAtMs
    private var initialized = false
    private var lastNow = -1L
    private var cooldownUntil = 0L
    private var terminal: RetryDecision.Stop? = null

    init { plan.validate(plan.targets, plan.startAtMs) }

    fun observe(nowMs: Long, inventoryRevision: Long, stock: Stock, submissionBlocked: Boolean,
                observedTarget: TicketTarget? = null): RetryDecision {
        fun stop(reason: String): RetryDecision.Stop = RetryDecision.Stop(reason).also { terminal = it }
        terminal?.let { return it }
        if (submissionBlocked) return stop("已提交或结果待确认，不能继续捡漏。")
        if (nowMs < 0 || nowMs < lastNow || inventoryRevision < 0 || inventoryRevision < seenRevision) return stop("计时或库存观测版本异常。")
        lastNow = nowMs
        if (nowMs >= plan.endAtMs) return stop("已到任务截止时间。")
        if (stock == Stock.RISK || stock == Stock.UNKNOWN) return stop("验证或未知页面需要人工处理。")
        fun after(delay: Long) = nowMs + minOf(delay, plan.endAtMs - nowMs)
        fun wait(delay: Long) = RetryDecision.Wait(after(delay))
        if (nowMs < plan.startAtMs) return RetryDecision.Wait(plan.startAtMs)
        if (stock == Stock.QUEUE || stock == Stock.LOADING || stock == Stock.UPCOMING) return wait(1000)
        if (stock == Stock.RATE_LIMITED) {
            nextCheck = after(30_000)
            cooldownUntil = nextCheck
            return RetryDecision.Wait(nextCheck)
        }
        if (nowMs < cooldownUntil) return RetryDecision.Wait(cooldownUntil)
        if (awaitingRevision && inventoryRevision <= seenRevision) {
            if (nowMs - refreshIssuedAt >= 10_000) return stop("未确认库存刷新完成。")
            return wait(1000)
        }
        awaitingRevision = false
        seenRevision = inventoryRevision
        if (stock == Stock.AVAILABLE) {
            if (observedTarget?.identity() != plan.targets[index].identity()) return stop("可售库存与当前授权组合不一致。")
            terminal = RetryDecision.Stop("已选定目标，交给单次执行器；停止捡漏调度。")
            return RetryDecision.Purchase(plan.targets[index])
        }
        if (!initialized) {
            initialized = true
            seenRevision = inventoryRevision
            nextCheck = after(1000)
        }
        if (nowMs < nextCheck) return RetryDecision.Wait(nextCheck)
        index = (index + 1) % plan.targets.size
        if (index == 0) round = minOf(round + 1, 5)
        seenRevision = inventoryRevision
        awaitingRevision = true
        refreshIssuedAt = nowMs
        nextCheck = after(minOf(1000L shl round, 30_000L))
        return RetryDecision.Refresh(plan.targets[index])
    }
}
