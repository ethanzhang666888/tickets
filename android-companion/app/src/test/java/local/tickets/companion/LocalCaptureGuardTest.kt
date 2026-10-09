package local.tickets.companion.execution

import org.junit.Assert.*
import org.junit.Test

class LocalCaptureGuardTest {
    @Test fun permissionRevocationAlwaysStopsEvenAfterAdvancing() {
        for (reason in listOf("capture_permission_lost", "capture_service_stopped", "stopped_by_user", "screen_locked", "foreground_not_allowed")) {
            assertTrue(LocalCaptureGuard.revokes(reason, false))
            assertTrue(LocalCaptureGuard.revokes(reason, true))
        }
    }
    @Test fun onlyExpectedConfirmationPrivacyStopCanContinueNativeBuyerReview() {
        assertTrue(LocalCaptureGuard.revokes("payment_foreground", false))
        assertFalse(LocalCaptureGuard.revokes("payment_foreground", true))
        assertTrue(LocalCaptureGuard.revokes("capture_page_not_allowed", true))
    }
}
