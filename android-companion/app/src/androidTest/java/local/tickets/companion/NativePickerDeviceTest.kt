package local.tickets.companion

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import local.tickets.companion.pages.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Read-only comparison of accessibility tree flags on a user-authorized ticket picker. */
class NativePickerDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun compareNativeTreeVisibility() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        val reports = JSONArray()
        try {
            for (includeUnimportant in listOf(false, true)) {
                val info = automation.serviceInfo
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                info.flags = if (includeUnimportant) info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    else info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
                automation.serviceInfo = info
                // Service registration/flag propagation is asynchronous on API29.
                val readyBy = SystemClock.elapsedRealtime() + 3000
                var readyRoot: AccessibilityNodeInfo? = null
                while (readyRoot == null && SystemClock.elapsedRealtime() < readyBy) {
                    readyRoot = automation.rootInActiveWindow
                    if (readyRoot == null) SystemClock.sleep(50)
                }
                val root = readyRoot ?: error("No active window after service registration")
                val specs = mutableListOf<NodeSpec>()
                val actionMetadata = JSONArray()
                val began = SystemClock.elapsedRealtime()
                fun visit(node: AccessibilityNodeInfo, parent: Int?) {
                    try {
                        check(specs.size < NodeSnapshot.MAX_NODES && SystemClock.elapsedRealtime() - began < 3000)
                        check(!node.isPassword && node.packageName?.toString() == Safety.DAMAI)
                        val rect = Rect().also(node::getBoundsInScreen)
                        val index = specs.size
                        if (node.isClickable || node.viewIdResourceName?.endsWith("/ll_perform_item") == true) {
                            actionMetadata.put(JSONObject().put("index", index).put("class", node.className)
                                .put("clickable", node.isClickable).put("focusable", node.isFocusable)
                                .put("actions", JSONArray(node.actionList.map { it.id }))
                                .put("touchDelegateRegions", node.touchDelegateInfo?.regionCount ?: 0))
                        }
                        specs += NodeSpec(parent, Safety.DAMAI, node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(),
                            node.contentDescription?.toString().orEmpty(), node.isVisibleToUser, node.isEnabled, node.isClickable,
                            if (node.isCheckable) node.isChecked else null, node.isSelected,
                            NodeBounds(rect.left, rect.top, rect.right, rect.bottom), node.isScrollable)
                        for (i in 0 until node.childCount) visit(checkNotNull(node.getChild(i)), index)
                    } finally { node.recycle() }
                }
                visit(root, null)
                // Never export text or inspect further if this is not a recognized ticket selection structure.
                check(specs.none { it.resourceId == DamaiPageParser.PREFIX + "order_activity_title" })
                val snapshot = NodeSnapshot.create(if (includeUnimportant) 2 else 1, specs, true)
                val page = DamaiPageParser().parse(snapshot)
                reports.put(JSONObject().put("includeNotImportantViews", includeUnimportant)
                    .put("nodes", specs.size).put("nativeMs", SystemClock.elapsedRealtime() - began)
                    .put("legacySessions", specs.count { it.resourceId == DamaiPageParser.PREFIX + DamaiPageParser.SESSION_LIST })
                    .put("legacyTiers", specs.count { it.resourceId == DamaiPageParser.PREFIX + DamaiPageParser.TIER_LIST })
                    .put("selectedNodes", specs.count { it.selected == true || it.checked == true })
                    .put("clickableNodes", specs.count { it.clickable })
                    .put("kind", page.kind.name).put("blockers", JSONArray(page.blockers.map { it.name }))
                    .put("sessions", page.sessions.size).put("tiers", page.tiers.size)
                    .put("captureAllowed", local.tickets.companion.execution.ExecutionCapturePolicy.allowed(snapshot))
                    .put("pickerRecognized", local.tickets.companion.execution.ExecutionCapturePolicy.picker(snapshot))
                    .put("unsafeWords", local.tickets.companion.execution.ExecutionCapturePolicy.unsafeWords(specs.filter { it.visible }.flatMap { listOf(it.text, it.description) }, true))
                    .put("nativeCards", local.tickets.companion.execution.ExecutionCapturePolicy.cards(snapshot)?.size ?: 0)
                    .put("actionMetadata", actionMetadata)
                    .put("layout", if (includeUnimportant) JSONArray(specs.mapIndexed { i, node ->
                        JSONObject().put("i", i).put("parent", node.parentIndex ?: JSONObject.NULL)
                            .put("id", node.resourceId).put("visible", node.visible).put("bounds", node.bounds.toString())
                            .put("hasText", node.text.isNotEmpty()).put("hasDescription", node.description.isNotEmpty())
                    }) else JSONArray()))
                if (includeUnimportant) assertTrue("Measured picker must pass strict capture ownership", local.tickets.companion.execution.ExecutionCapturePolicy.allowed(snapshot))
            }
        } finally {
            automation.serviceInfo = original
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nNATIVE_PICKER_EVIDENCE=$reports\n") })
        }
        assertEquals(2, reports.length())
    }
}
