package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class ForegroundGuardTest {
    @Test fun onlyIdleMissingRootsGetABoundedWait() {
        val guard = ForegroundGuard()
        assertEquals(ForegroundGuard.Decision.WAIT, guard.evaluate("foreground_unavailable", true, 100))
        assertEquals(ForegroundGuard.Decision.WAIT, guard.evaluate("foreground_unavailable", true, 1099))
        assertEquals(ForegroundGuard.Decision.STOP, guard.evaluate("foreground_unavailable", true, 1100))
        assertEquals(ForegroundGuard.Decision.STOP, guard.evaluate(null, true, 1200))
    }
    @Test fun knownUnsafeStatesAndActiveCapturesStopImmediately() {
        for (error in listOf("screen_locked", "accessibility_lost", "foreground_not_allowed", "payment_foreground", "node_scan_failed")) {
            assertEquals(ForegroundGuard.Decision.STOP, ForegroundGuard().evaluate(error, true, 0))
        }
        assertEquals(ForegroundGuard.Decision.STOP, ForegroundGuard().evaluate("foreground_unavailable", false, 0))
        assertEquals(ForegroundGuard.Decision.STOP, ForegroundGuard().evaluate("node_scan_incomplete", false, 0))
    }
    @Test fun idleIncompleteAndMissingScansShareOneDeadline() {
        val guard = ForegroundGuard()
        assertEquals(ForegroundGuard.Decision.WAIT, guard.evaluate("node_scan_incomplete", true, 0))
        assertEquals(ForegroundGuard.Decision.WAIT, guard.evaluate("foreground_unavailable", true, 999))
        assertEquals(ForegroundGuard.Decision.STOP, guard.evaluate("node_scan_incomplete", true, 1000))
    }
    @Test fun recoveryResetsWaitButRollbackNeverExtendsIt() {
        val guard = ForegroundGuard()
        assertEquals(ForegroundGuard.Decision.WAIT, guard.evaluate("foreground_unavailable", true, 100))
        assertEquals(ForegroundGuard.Decision.ALLOW, guard.evaluate(null, true, 200))
        assertEquals(ForegroundGuard.Decision.WAIT, guard.evaluate("foreground_unavailable", true, 2000))
        assertEquals(ForegroundGuard.Decision.STOP, guard.evaluate("foreground_unavailable", true, 1999))
    }
}
