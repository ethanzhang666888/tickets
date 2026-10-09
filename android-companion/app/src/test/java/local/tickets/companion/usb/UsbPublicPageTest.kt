package local.tickets.companion.usb

import local.tickets.companion.execution.ConfirmationReview
import local.tickets.companion.execution.NativeBuyerChoice
import local.tickets.companion.pages.*
import org.junit.Assert.*
import org.junit.Test

class UsbPublicPageTest {
    private fun order() = PageObservation(1, PageKind.CONFIRMATION,
        eventTitle = Observed.Known("不应从私人页面导出的标题"),
        confirmation = ConfirmationFields(Observed.Known("测试演出"), Observed.Known("2026-11-07 19:00"),
            Observed.Known(88000L), Observed.Known(1), Observed.Known(88000L)),
        blockers = setOf(PageBlocker.UNSUPPORTED_BUYER_STRUCTURE, PageBlocker.UNSUPPORTED_TIER_IDENTITY, PageBlocker.UNSUPPORTED_FEE_STRUCTURE),
        nodeCount = 20)
    @Test fun privateOrderDataIsNotAReadOnlyAuthorizationOrIdentityExport() {
        val json = UsbPublicPage.build(order())
        assertFalse(json.getBoolean("orderVerified"))
        assertEquals("", json.getString("title"))
        assertEquals(0, json.getJSONArray("buyers").length())
        assertFalse(json.has("selectedBuyerCount"))
        assertFalse(json.has("totalCents"))
        assertFalse(json.toString().contains("不应"))
    }
    @Test fun verifiedOrderExportsOnlyAmountQuantityAndSelectionCounts() {
        val first = NativeBuyerChoice(9, "秘密甲", "秘密甲 · 身份证 · 123****456", true, true)
        val second = NativeBuyerChoice(10, "秘密乙", "秘密乙 · 身份证 · 987****654", false, true)
        val json = UsbPublicPage.build(order(), ConfirmationReview.Verified(listOf(first, second), null))
        assertTrue(json.getBoolean("orderVerified"))
        assertEquals(1, json.getInt("selectedBuyerCount"))
        assertTrue(json.getBoolean("defaultFirstSelected"))
        assertEquals(88000L, json.getLong("totalCents"))
        assertEquals(0, json.getJSONArray("buyers").length())
        for (secret in listOf("秘密", "身份证", "123", "987", "不应")) assertFalse(json.toString().contains(secret))
        assertFalse(UsbPublicPage.build(order(), ConfirmationReview.Verified(emptyList(), null)).getBoolean("orderVerified"))
        assertFalse(UsbPublicPage.build(order(), ConfirmationReview.Verified(listOf(first, second), second to true)).getBoolean("orderVerified"))
    }
    @Test fun publicCatalogUsesCanonicalDateAndPriceLabels() {
        val bounds = NodeBounds(0, 0, 100, 100)
        val page = PageObservation(1, PageKind.PICKER, Observed.Known("测试演出"),
            sessions = listOf(TicketOption(1, "2026-11-07 周六 19:00", null, SelectionEvidence.VISUAL_SELECTED, Availability.AVAILABLE, bounds)),
            tiers = listOf(TicketOption(2, "內場880元", 88000, SelectionEvidence.VISUAL_UNSELECTED, Availability.AVAILABLE, bounds)),
            nodeCount = 3)
        val json = UsbPublicPage.build(page)
        assertEquals("2026-11-07 19:00", json.getJSONArray("sessions").getJSONObject(0).getString("label"))
        assertEquals("内场880元", json.getJSONArray("tiers").getJSONObject(0).getString("label"))
        assertEquals(88000L, json.getJSONArray("tiers").getJSONObject(0).getLong("unitPriceCents"))
        assertFalse(json.getBoolean("orderVerified"))
    }
}
