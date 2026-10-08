package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class CaptureGuardTest {
    @Test fun capturesCannotCrossForegroundsOrRetainStaleTestVisibility() {
        assertTrue(Safety.captureMatches(Safety.DAMAI, false, false))
        assertTrue(Safety.captureMatches(Safety.OWN, true, true))
        assertFalse(Safety.captureMatches(Safety.OWN, false, false))
        assertFalse(Safety.captureMatches(Safety.OWN, true, false))
        assertFalse(Safety.captureMatches(Safety.DAMAI, true, true))
        assertFalse(Safety.captureMatches("com.android.systemui", false, false))
        assertFalse(Safety.captureMatches(null, false, false))
    }
    @Test fun fixedPageClassificationDoesNotReturnPrivateText() {
        assertEquals("unknown", Safety.pageKind(listOf("测试姓名", "证件号码 110101199001011234", "13800138000")))
        assertEquals("unknown", SafeSummary(pageKind = "测试姓名").json().getString("pageKind"))
        assertFalse(Safety.canInspect("com.damai", false, true, false))
    }
}
