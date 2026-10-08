package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class NativeAuditPermitTest {
    @Test fun absentGrantFailsAndLocalGrantIsConsumedOnce() {
        var now = 1000L
        val permit = NativeAuditPermit { now }
        assertFalse(permit.consume())
        permit.arm()
        assertTrue(permit.consume())
        assertFalse(permit.consume())
    }
    @Test fun exactExpiryAndRollbackRevoke() {
        var now = 1000L
        val permit = NativeAuditPermit { now }
        permit.arm()
        now = 120_999L
        assertTrue(permit.consume())
        permit.arm()
        now += 120_000
        assertFalse(permit.consume())
        permit.arm()
        now--
        assertFalse(permit.consume())
    }
    @Test fun stopRevokesAndRegenerationStartsNewBoundedGrant() {
        var now = 0L
        val permit = NativeAuditPermit { now }
        permit.arm()
        permit.revoke()
        assertFalse(permit.consume())
        permit.arm()
        now = 119_000L
        permit.arm()
        now = 121_000L
        assertTrue(permit.consume())
    }
}
