package local.tickets.companion.execution

import local.tickets.companion.pages.*
import local.tickets.companion.policy.BuyerIdentity
import local.tickets.companion.policy.TicketTarget

internal class NativeBuyerChoice(val index: Int, val name: String, val label: String, val selected: Boolean, val enabled: Boolean) {
    override fun toString() = "NativeBuyerChoice(redacted)"
}
internal sealed interface ConfirmationReview {
    class Verified(val buyers: List<NativeBuyerChoice>, val change: Pair<NativeBuyerChoice, Boolean>?) : ConfirmationReview
    class Rejected(val reason: String) : ConfirmationReview
}

/** Uses the measured complete native buyer rows, never OCR names or positional clicks. */
internal fun reviewConfirmation(snapshot: NodeSnapshot, page: PageObservation, plan: LocalPlan, target: TicketTarget): ConfirmationReview {
    fun reject(reason: String) = ConfirmationReview.Rejected(reason)
    val allowedPartialFields = setOf(PageBlocker.UNSUPPORTED_BUYER_STRUCTURE, PageBlocker.UNSUPPORTED_TIER_IDENTITY, PageBlocker.UNSUPPORTED_FEE_STRUCTURE)
    if (page.kind != PageKind.CONFIRMATION || page.blockers.any { it !in allowedPartialFields }) return reject("confirmation_incomplete_or_blocked")
    val fields = page.confirmation ?: return reject("confirmation_incomplete")
    val total = fields.totalCents.known() ?: return reject("confirmation_amount_missing")
    if (DamaiPageParser.eventKey(fields.eventTitle.known().orEmpty()) != DamaiPageParser.eventKey(plan.eventTitle) ||
        DamaiPageParser.sessionKey(fields.session.known().orEmpty()) != DamaiPageParser.sessionKey(target.session) ||
        fields.unitPriceCents.known() != target.unitPriceCents || fields.quantity.known() != plan.quantity ||
        total <= 0 || total > plan.maxTotalCents || total > target.unitPriceCents * plan.quantity) return reject("confirmation_target_or_amount_mismatch")
    val prefix = DamaiPageParser.PREFIX
    fun unique(id: String) = snapshot.nodes.singleOrNull { it.resourceId == prefix + id }
    val content = unique("recycler_view") ?: return reject("buyer_list_incomplete")
    val header = content.children.firstOrNull()?.let { snapshot.nodes[it] } ?: return reject("confirmation_incomplete")
    if (!snapshot.visible(header) || !content.bounds.contains(header.bounds)) return reject("confirmation_header_clipped")
    val list = unique("recycler_main") ?: return reject("buyer_list_incomplete")
    if (list !in snapshot.subtree(content) || !snapshot.visible(list) || list.scrollable ||
        !content.bounds.contains(list.bounds) || list.children.size !in 1..20) return reject("buyer_list_incomplete")
    val buyers = mutableListOf<NativeBuyerChoice>()
    for (index in list.children) {
        val row = snapshot.nodes[index]
        if (row.resourceId != prefix + "layout_main" || !snapshot.visible(row) || !list.bounds.contains(row.bounds)) return reject("buyer_list_incomplete")
        fun field(id: String) = snapshot.subtree(row).singleOrNull { it.resourceId == prefix + id }
            ?.takeIf { snapshot.visible(it) && row.bounds.contains(it.bounds) }
        val name = field("text_name")?.text?.trim() ?: return reject("buyer_identity_incomplete")
        val type = field("text_num_type")?.text?.trim() ?: return reject("buyer_identity_incomplete")
        val masked = field("text_num")?.text?.trim() ?: return reject("buyer_identity_incomplete")
        if (name.length > 80 || type.length > 40 || !Regex("[A-Za-z0-9*＊•·]{4,40}").matches(masked)) return reject("buyer_identity_incomplete")
        try { BuyerIdentity(name, type, masked).validate() } catch (_: IllegalArgumentException) { return reject("buyer_identity_incomplete") }
        val check = field("checkbox") ?: return reject("buyer_checkbox_incomplete")
        val selected = check.checked ?: return reject("buyer_checkbox_incomplete")
        buyers += NativeBuyerChoice(check.index, name, "$name · $type · $masked", selected,
            snapshot.enabled(row) && snapshot.enabled(check) && check.clickable)
    }
    if (snapshot.nodes.count { it.resourceId == prefix + "checkbox" } != buyers.size ||
        snapshot.nodes.count { it.resourceId == prefix + "text_name" } != buyers.size ||
        buyers.map { DamaiPageParser.normalized(it.label) }.distinct().size != buyers.size || buyers.size < plan.quantity)
        return reject("buyer_list_ambiguous")
    val wanted = if (plan.buyerLabels.isEmpty()) buyers.take(plan.quantity) else plan.buyerLabels.map { label ->
        buyers.singleOrNull { DamaiPageParser.normalized(label) in listOf(DamaiPageParser.normalized(it.name), DamaiPageParser.normalized(it.label)) }
            ?: return reject("authorized_buyer_missing_or_ambiguous")
    }
    if (wanted.distinct().size != plan.quantity || wanted.any { !it.enabled }) return reject("authorized_buyer_unavailable")
    val extra = buyers.firstOrNull { it.selected && it !in wanted }
    val change = if (extra != null) extra to false else wanted.firstOrNull { !it.selected }?.let { it to true }
    if (change != null && !change.first.enabled) return reject("buyer_checkbox_unavailable")
    return ConfirmationReview.Verified(buyers, change)
}
