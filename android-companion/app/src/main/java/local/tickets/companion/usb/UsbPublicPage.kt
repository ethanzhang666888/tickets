package local.tickets.companion.usb

import local.tickets.companion.execution.ConfirmationReview
import local.tickets.companion.pages.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

/** The only page representation sent to the desktop. Private order text is never serialized. */
internal object UsbPublicPage {
    private val date = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    fun build(page: PageObservation, verifiedOrder: ConfirmationReview.Verified? = null): JSONObject {
        val public = page.kind == PageKind.DETAIL || page.kind == PageKind.PICKER
        val selectedBuyers = verifiedOrder?.buyers?.count { it.selected } ?: 0
        val verified = page.kind == PageKind.CONFIRMATION && verifiedOrder != null && verifiedOrder.change == null &&
            selectedBuyers > 0 && page.confirmation?.quantity?.known() == selectedBuyers
        fun option(value: TicketOption, session: Boolean): JSONObject = JSONObject()
            .put("label", if (session) DamaiPageParser.sessionKey(value.label)?.format(date) ?: value.label else DamaiPageParser.tierKey(value.label))
            .put("selected", value.selection.selected()).put("selectionVerified", value.selection != SelectionEvidence.UNKNOWN)
            .put("enabled", value.availability == Availability.AVAILABLE).put("availability", value.availability.name.lowercase())
            .also { if (!session) it.put("unitPriceCents", value.unitPriceCents) }
        val kind = if (page.kind == PageKind.CONFIRMATION) "confirm" else page.kind.name.lowercase()
        val result = JSONObject().put("kind", kind).put("title", if (public) page.eventTitle.known().orEmpty() else "")
            .put("sessions", JSONArray(if (public) page.sessions.map { option(it, true) } else emptyList<JSONObject>()))
            .put("tiers", JSONArray(if (public) page.tiers.map { option(it, false) } else emptyList<JSONObject>()))
            .put("blockers", JSONArray(page.blockers.map { it.name }))
            .put("canOpenPicker", page.kind == PageKind.DETAIL && page.blockers.isEmpty() && page.detailAvailability == Availability.AVAILABLE)
            .put("purchaseAvailability", page.detailAvailability.name.lowercase())
            .put("buyers", JSONArray()).put("buyersComplete", false).put("orderVerified", verified)
            .put("identityStaysOnPhone", true)
        if (public) result.put("quantity", page.quantity.known())
        if (verified) {
            result.put("quantity", page.confirmation?.quantity?.known()).put("totalCents", page.confirmation?.totalCents?.known())
                .put("selectedBuyerCount", verifiedOrder!!.buyers.count { it.selected })
                .put("defaultFirstSelected", verifiedOrder.buyers.firstOrNull()?.selected == true)
                .put("blockers", JSONArray()) // The complete native review, not the partial generic parser, verified these fields.
        }
        return result
    }
}
