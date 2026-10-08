package local.tickets.companion

import local.tickets.companion.policy.*
import org.junit.Assert.*
import org.junit.Test

class AuthorizationTest {
    private val alice = BuyerIdentity("张甲", "身份证", "110***********1234")
    private val bob = BuyerIdentity("张甲", "身份证", "110***********5678")
    private val first = TicketTarget("2026-10-11 19:00", "看台680元", 68000)
    private val second = TicketTarget("2026-10-12 19:00", "看台380元", 38000)
    private fun plan() = PurchaseAuthorization("测试演出", listOf(first, second), 1, listOf(alice), 70000, 1000, 10000, false)
    private fun confirmation() = OrderObservation("测试演出", first, 1, 68000, 0, listOf(BuyerRow(alice, true), BuyerRow(bob, false)), true)

    @Test fun configurationRequiresObservedCombinationsAndFixedPeople() {
        plan().validate(listOf(first, second), 0)
        assertThrows(IllegalArgumentException::class.java) { plan().validate(listOf(first), 0) }
        assertThrows(IllegalArgumentException::class.java) { plan().copy(targets = listOf(first.copy(session = second.session))).validate(listOf(first, second), 0) }
        assertThrows(IllegalArgumentException::class.java) { plan().copy(buyers = listOf(alice, bob)).validate(listOf(first, second), 0) }
        assertThrows(IllegalArgumentException::class.java) { plan().copy(targets = listOf(first, first)).validate(listOf(first, second), 0) }
        assertThrows(IllegalArgumentException::class.java) { plan().copy(maxTotalCents = 67999).validate(listOf(first, second), 0) }
        assertThrows(IllegalArgumentException::class.java) { plan().copy(endAtMs = 1000).validate(listOf(first, second), 0) }
    }

    @Test fun documentMustAlreadyBeMaskedAndIdentityStringsAreNotLogged() {
        assertThrows(IllegalArgumentException::class.java) { BuyerIdentity("张甲", "身份证", "110123456789001234").validate() }
        assertThrows(IllegalArgumentException::class.java) { BuyerIdentity("张甲", "身份证", "****************").validate() }
        assertFalse(alice.toString().contains("张甲"))
        assertFalse(alice.toString().contains("1234"))
    }

    @Test fun exactIdentitySetNotJustCountIsRequired() {
        assertEquals(OrderDecision.ReadyForManualSubmit, authorizeOrder(plan(), confirmation()))
        val wrong = confirmation().copy(buyers = listOf(BuyerRow(alice, false), BuyerRow(bob, true)))
        assertEquals(OrderDecision.SetBuyer(bob, false), authorizeOrder(plan(), wrong))
        val none = wrong.copy(buyers = listOf(BuyerRow(alice, false), BuyerRow(bob, false)))
        assertEquals(OrderDecision.SetBuyer(alice, true), authorizeOrder(plan(), none))
    }

    @Test fun ambiguityIncompleteListOrMissingPersonCannotSubmit() {
        for (observation in listOf(
            confirmation().copy(buyers = listOf(BuyerRow(alice, true), BuyerRow(alice, false))),
            confirmation().copy(buyers = listOf(BuyerRow(bob, true))),
            confirmation().copy(buyerListComplete = false)
        )) assertTrue(authorizeOrder(plan().copy(autoSubmit = true), observation) is OrderDecision.Pause)
    }

    @Test fun priceFeesAndCombinationAreCheckedBeforeTouchingPeople() {
        for (observation in listOf(
            confirmation().copy(eventTitle = "另一演出"),
            confirmation().copy(target = first.copy(session = second.session)),
            confirmation().copy(quantity = 2),
            confirmation().copy(totalCents = 70001, feesCents = 2001),
            confirmation().copy(totalCents = 68001, feesCents = 0),
            confirmation().copy(feesCents = null)
        )) assertTrue(authorizeOrder(plan().copy(autoSubmit = true), observation) is OrderDecision.Pause)
        assertEquals(OrderDecision.SubmitOnce, authorizeOrder(plan().copy(autoSubmit = true), confirmation().copy(totalCents = 68500, feesCents = 500)))
    }

    @Test fun submissionFenceAlwaysWinsEvenForOtherwiseValidOrder() {
        assertTrue(authorizeOrder(plan().copy(autoSubmit = true), confirmation(), submissionBlocked = true) is OrderDecision.Pause)
    }

    @Test fun retryRequiresFreshInventoryAndStopsAtDeadlineOrSubmission() {
        val schedule = RetrySchedule(plan())
        assertEquals(RetryDecision.Wait(1000), schedule.observe(0, 1, Stock.SOLD_OUT, false))
        assertEquals(RetryDecision.Wait(2000), schedule.observe(1000, 1, Stock.SOLD_OUT, false))
        assertEquals(RetryDecision.Refresh(second), schedule.observe(2000, 1, Stock.SOLD_OUT, false))
        // Reading the old inventory again is not a successful refresh.
        assertEquals(RetryDecision.Wait(3000), schedule.observe(2000, 1, Stock.AVAILABLE, false))
        assertEquals(RetryDecision.Purchase(second), schedule.observe(2001, 2, Stock.AVAILABLE, false, second))
        assertTrue(schedule.observe(2002, 3, Stock.AVAILABLE, true) is RetryDecision.Stop)
        assertTrue(RetrySchedule(plan()).observe(10000, 1, Stock.AVAILABLE, false) is RetryDecision.Stop)
    }

    @Test fun queueDoesNotRefreshAndRiskRequiresHuman() {
        assertEquals(RetryDecision.Wait(2000), RetrySchedule(plan()).observe(1000, 1, Stock.QUEUE, false))
        assertTrue(RetrySchedule(plan()).observe(1000, 1, Stock.RISK, false) is RetryDecision.Stop)
    }
}
