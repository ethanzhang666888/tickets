package local.tickets.companion

import local.tickets.companion.execution.LocalForegroundGuard
import org.junit.Assert.*
import org.junit.Test

class LocalForegroundGuardTest {
    @Test fun launchGraceAllowsStagingOnlyUntilTargetHasBeenObserved() {
        val guard = LocalForegroundGuard("cn.damai", 1500)
        assertFalse(guard.departed("local.tickets.companion", 0))
        assertFalse(guard.departed("launcher", 1000))
        assertFalse(guard.departed("cn.damai", 1100))
        assertTrue(guard.departed("launcher", 1101))
    }

    @Test fun departureAndReturnDuringStockWaitNeverReauthorizesTask() {
        val guard = LocalForegroundGuard("cn.damai", 1500)
        assertFalse(guard.departed("cn.damai", 1500))
        assertTrue(guard.departed("another.app", 2000))
        assertTrue(guard.departed("cn.damai", 2100))
        assertTrue(guard.departed("cn.damai", 30_000))
    }

    @Test fun graceExpiresWithoutTargetAndMissingMetadataDoesNotProveDeparture() {
        val guard = LocalForegroundGuard("cn.damai", 1500)
        assertFalse(guard.departed(null, 1400))
        assertTrue(guard.departed("local.tickets.companion", 1500))
        assertTrue(guard.departed(null, 1600))
    }

    @Test fun newTaskHasIndependentLaunchGrace() {
        val old = LocalForegroundGuard("cn.damai", 1500)
        old.departed("cn.damai", 0)
        assertTrue(old.departed("launcher", 100))
        val fresh = LocalForegroundGuard("cn.damai", 2000)
        assertFalse(fresh.departed("local.tickets.companion", 500))
        assertFalse(fresh.departed("cn.damai", 600))
        assertFalse(fresh.departed("cn.damai", 30_000))
    }
}
