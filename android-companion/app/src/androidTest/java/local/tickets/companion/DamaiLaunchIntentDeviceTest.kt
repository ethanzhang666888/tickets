package local.tickets.companion

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Explicit device regression: app navigation only, never permission confirmation or purchase actions. */
class DamaiLaunchIntentDeviceTest {
    @Test fun resumesExistingTaskUsingLauncherSemantics() {
        val intent = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.damaiLaunchIntent())
        assertEquals(Safety.DAMAI, intent.component?.packageName)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("Missing launcher task-resume flag", intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertEquals(0, intent.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        assertNull(intent.data)
    }

    /** Explicitly run only with the authorized Ella search already open; no search/result clicks. */
    @Test fun preservesExistingAuthorizedSearchOnRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        @Suppress("DEPRECATION")
        fun authorizedWindow(): Int? {
            val root = automation.rootInActiveWindow ?: return null
            try {
                if (root.packageName?.toString() != Safety.DAMAI) return null
                val nodes = root.findAccessibilityNodeInfosByText("Ella郑州")
                try {
                    return root.windowId.takeIf {
                        nodes.count { it.packageName?.toString() == Safety.DAMAI && it.isVisibleToUser &&
                            it.isEditable && it.text?.toString() == "Ella郑州" } == 1
                    }
                } finally { nodes.forEach { it.recycle() } }
            } finally { root.recycle() }
        }
        try {
            val before = checkNotNull(authorizedWindow()) { "Authorized search must already be open" }
            instrumentation.context.startActivity(Intent().setClassName("local.tickets.companion", "local.tickets.companion.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val ownDeadline = android.os.SystemClock.elapsedRealtime() + 3000
            var ownVisible = false
            while (!ownVisible && android.os.SystemClock.elapsedRealtime() < ownDeadline) {
                val root = automation.rootInActiveWindow
                @Suppress("DEPRECATION")
                try { ownVisible = root?.packageName?.toString() == "local.tickets.companion" }
                finally { root?.recycle() }
                if (!ownVisible) android.os.SystemClock.sleep(50)
            }
            assertTrue("Own app must be foreground before resume", ownVisible)
            instrumentation.context.startActivity(checkNotNull(instrumentation.targetContext.damaiLaunchIntent()))
            val until = android.os.SystemClock.elapsedRealtime() + 3000
            var after: Int? = null
            while (after == null && android.os.SystemClock.elapsedRealtime() < until) {
                after = authorizedWindow()
                if (after == null) android.os.SystemClock.sleep(50)
            }
            assertEquals("Existing authorized page must survive app switch", before, after)
        } finally { automation.serviceInfo = original }
    }
}
