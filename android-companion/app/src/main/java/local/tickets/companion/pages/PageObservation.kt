package local.tickets.companion.pages

import local.tickets.companion.policy.BuyerRow
import local.tickets.companion.policy.TicketTarget

/** Missing, ambiguous and unsupported are deliberately not nullable defaults. */
sealed interface Observed<out T> {
    class Known<T> internal constructor(val value: T) : Observed<T> {
        override fun toString() = "Known(redacted)"
    }
    data object Missing : Observed<Nothing>
    data object Ambiguous : Observed<Nothing>
    data object Unsupported : Observed<Nothing>
}

internal fun <T> Observed<T>.known(): T? = (this as? Observed.Known)?.value

enum class PageKind { DETAIL, PICKER, RESERVATION, CONFIRMATION, RISK, QUEUE, LOADING, UNKNOWN }
enum class Availability { AVAILABLE, SOLD_OUT, UPCOMING, UNAVAILABLE, UNKNOWN }
enum class SelectionEvidence {
    NATIVE_SELECTED, NATIVE_UNSELECTED, VISUAL_SELECTED, VISUAL_UNSELECTED, UNKNOWN;
    fun selected() = this == NATIVE_SELECTED || this == VISUAL_SELECTED
}
enum class PageBlocker {
    INCOMPLETE_SNAPSHOT, FOREIGN_PACKAGE, UNKNOWN_PAGE, RISK, QUEUE, LOADING, DIALOG, RESERVATION,
    AMBIGUOUS_STRUCTURE, MISSING_IDENTITY, UNSUPPORTED_OPTIONS, POTENTIALLY_HIDDEN_CONTENT,
    MISSING_QUANTITY, UNSUPPORTED_BUYER_STRUCTURE, UNSUPPORTED_FEE_STRUCTURE, UNSUPPORTED_TIER_IDENTITY,
    INCOMPLETE_CONFIRMATION, WRONG_PAGE, TARGET_MISSING_OR_AMBIGUOUS, EVENT_MISMATCH,
    SESSION_ASSOCIATION_UNVERIFIED, UNAVAILABLE_TARGET, INVALID_TARGET,
}

class TicketOption internal constructor(
    val nodeIndex: Int,
    val label: String,
    val unitPriceCents: Long?,
    val selection: SelectionEvidence,
    val availability: Availability,
    val bounds: NodeBounds,
) {
    override fun toString() = "TicketOption(redacted)"
}

/**
 * Partial native observations, NOT an OrderObservation and NOT permission to submit.
 * The known desktop structure only exposes a price-labelled tier, not its full identity.
 * Full tier identity and explicit fees stay Unsupported. Buyer selection is reviewed
 * separately against complete native rows by the local manual-submit workflow; this
 * general observation does not establish buyer identity or authorize submission.
 */
class ConfirmationFields internal constructor(
    val eventTitle: Observed<String>,
    val session: Observed<String>,
    val unitPriceCents: Observed<Long>,
    val quantity: Observed<Int>,
    val totalCents: Observed<Long>,
) {
    val tierIdentity: Observed<String> = Observed.Unsupported
    val feesCents: Observed<Long> = Observed.Unsupported
    val buyers: Observed<List<BuyerRow>> = Observed.Unsupported
    val buyerListComplete: Boolean = false
    override fun toString() = "ConfirmationFields(redacted)"
}

sealed interface TargetMatch {
    /** A unique currently displayed pair, never purchase eligibility or a click instruction. */
    class Matched internal constructor(
        val captureId: Long,
        val target: TicketTarget,
        val session: TicketOption,
        val tier: TicketOption,
    ) : TargetMatch {
        override fun toString() = "TargetMatch.Matched(redacted)"
    }
    class Blocked internal constructor(reasons: Collection<PageBlocker>) : TargetMatch {
        val reasons = frozenSet(reasons)
        override fun toString() = "TargetMatch.Blocked($reasons)"
    }
}

/** The only audit representation: a fixed schema of categories and bounded counts, no raw values. */
class PageAudit internal constructor(
    val kind: PageKind,
    val nodeCount: Int,
    val sessionCount: Int,
    val tierCount: Int,
    blockers: Collection<PageBlocker>,
) {
    val blockers = frozenSet(blockers)
    override fun toString() = "PageAudit(kind=$kind, nodeCount=$nodeCount, sessionCount=$sessionCount, tierCount=$tierCount, blockers=$blockers)"
}

class PageObservation internal constructor(
    val captureId: Long,
    val kind: PageKind,
    val eventTitle: Observed<String> = Observed.Missing,
    sessions: List<TicketOption> = emptyList(),
    tiers: List<TicketOption> = emptyList(),
    val quantity: Observed<Int> = Observed.Missing,
    val detailAvailability: Availability = Availability.UNKNOWN,
    val confirmation: ConfirmationFields? = null,
    blockers: Collection<PageBlocker> = emptySet(),
    private val nodeCount: Int,
) {
    val sessions = frozenList(sessions)
    val tiers = frozenList(tiers)
    val blockers = frozenSet(blockers)

    /**
     * Does not combine tiers with all sessions: tier association requires exactly one
     * native-selected session, and that session must be the uniquely requested session.
     * No fallback, cross-page cache, default buyer, submission, or policy bypass is provided.
     */
    fun matchTarget(expectedEvent: String, target: TicketTarget): TargetMatch {
        fun blocked(reason: PageBlocker) = TargetMatch.Blocked(listOf(reason))
        if (blockers.isNotEmpty()) return TargetMatch.Blocked(blockers)
        if (kind != PageKind.PICKER) return blocked(PageBlocker.WRONG_PAGE)
        try { target.validate() } catch (_: IllegalArgumentException) { return blocked(PageBlocker.INVALID_TARGET) }
        if (expectedEvent.isBlank() || expectedEvent.length > 300 ||
            DamaiPageParser.eventKey(eventTitle.known().orEmpty()) != DamaiPageParser.eventKey(expectedEvent)) {
            return blocked(PageBlocker.EVENT_MISMATCH)
        }
        val date = DamaiPageParser.sessionKey(target.session) ?: return blocked(PageBlocker.INVALID_TARGET)
        val matchingSessions = sessions.filter { DamaiPageParser.sessionKey(it.label) == date }
        val matchingTiers = tiers.filter { DamaiPageParser.tierKey(it.label) == DamaiPageParser.tierKey(target.tier) && it.unitPriceCents == target.unitPriceCents }
        if (matchingSessions.size != 1 || matchingTiers.size != 1) return blocked(PageBlocker.TARGET_MISSING_OR_AMBIGUOUS)
        val session = matchingSessions.single()
        val tier = matchingTiers.single()
        if (sessions.count { it.selection.selected() } != 1 ||
            !session.selection.selected() || sessions.any { it.selection == SelectionEvidence.UNKNOWN }) {
            return blocked(PageBlocker.SESSION_ASSOCIATION_UNVERIFIED)
        }
        if (tiers.any { it.selection == SelectionEvidence.UNKNOWN } ||
            tiers.count { it.selection.selected() } > 1) {
            return blocked(PageBlocker.SESSION_ASSOCIATION_UNVERIFIED)
        }
        if (session.availability != Availability.AVAILABLE || tier.availability != Availability.AVAILABLE) {
            return blocked(PageBlocker.UNAVAILABLE_TARGET)
        }
        return TargetMatch.Matched(captureId, target, session, tier)
    }

    fun audit() = PageAudit(kind, nodeCount, sessions.size, tiers.size, blockers)
    override fun toString() = "PageObservation(redacted)"
}
