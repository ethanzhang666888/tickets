package local.tickets.companion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One-off operator helper for the user's explicit request to approve screen reading on their behalf.
 * Uses the normal OS dialog, never appops/settings/root or a grant bypass. Not part of business execution.
 * Skipped unless explicitly opted in; no persistent or automatic re-grant behavior in the normal app.
 */
@Suppress("DEPRECATION")
class ExplicitScreenConsentDeviceTest {
    @Test fun inspectOnlyOwnProjectionDialog() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitScreenConsent") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val root = checkNotNull(automation.rootInActiveWindow)
        try {
            check(root.packageName?.toString() == "com.android.systemui")
            val words = mutableListOf<String>()
            val buttons = org.json.JSONArray()
            fun visit(node: AccessibilityNodeInfo) {
                check(node.packageName?.toString() == "com.android.systemui" && !node.isPassword)
                node.text?.toString()?.let(words::add)
                if (node.isClickable || node.className?.contains("Button") == true) buttons.put(JSONObject()
                    .put("id", node.viewIdResourceName).put("class", node.className).put("visible", node.isVisibleToUser)
                    .put("enabled", node.isEnabled).put("label", node.text?.toString()?.takeIf { it in setOf("立即开始", "开始", "允许", "取消", "同意", "确定", "不再提示") } ?: "unknown"))
                for (i in 0 until node.childCount) node.getChild(i)?.let { try { visit(it) } finally { it.recycle() } }
            }
            visit(root)
            val label = instrumentation.context.packageManager.getApplicationLabel(instrumentation.context.packageManager.getApplicationInfo("local.tickets.companion", 0)).toString()
            check(words.any { label in it } && words.any { "屏幕" in it })
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nOWN_PROJECTION_BUTTONS=$buttons\n") })
        } finally { root.recycle() }
    }

    @Test fun approveOnlyOwnProjectionAfterExplicitUserRequest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitScreenConsent") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val own = "local.tickets.companion"
        val context = instrumentation.context
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val original = automation.serviceInfo
        fun nodes(packageName: String): List<AccessibilityNodeInfo> {
            val root = automation.rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() != packageName) { root.recycle(); return emptyList() }
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                result += node
                check(result.size <= 512 && node.packageName?.toString() == packageName && !node.isPassword)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            try { visit(root); return result }
            catch (e: Exception) { result.forEach { it.recycle() }; throw e }
        }
        fun await(packageName: String, action: (List<AccessibilityNodeInfo>) -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                val current = nodes(packageName)
                try { if (current.isNotEmpty() && action(current)) return }
                finally { current.forEach { it.recycle() } }
                SystemClock.sleep(50)
            }
            error("Expected own projection UI was not available; no other permission approved")
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            val pendingDialog = nodes("com.android.systemui")
            val appLabel = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(own, 0)).toString()
            val ownPending = try {
                val words = pendingDialog.filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
                words.any { appLabel in it } && words.any { "屏幕" in it }
            } finally { pendingDialog.forEach { it.recycle() } }
            var alreadyReady = false
            if (!ownPending) {
                context.startActivity(Intent().setComponent(ComponentName(own, "$own.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await(own) { current ->
                    val status = current.singleOrNull { it.text?.startsWith("{") == true && it.text?.contains("\"localExecution\"") == true }
                        ?.let { JSONObject(it.text.toString()) } ?: return@await false
                    check(status.optBoolean("accessibilityConnected"))
                    check(!status.getJSONObject("localExecution").getBoolean("active"))
                    alreadyReady = status.optString("captureState") == "ready"
                    if (alreadyReady) return@await true
                    val button = current.singleOrNull { it.text?.toString() == "2. 授权屏幕读取（系统确认）" && it.isClickable && it.isEnabled }
                        ?: return@await false
                    if (!button.isVisibleToUser) { button.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); return@await false }
                    check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    true
                }
            }
            if (!alreadyReady) {
                await("com.android.systemui") { current ->
                    val words = current.filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
                    check(words.any { appLabel in it } && words.any { "屏幕" in it }) { "Not our screen-reading dialog" }
                    val button = current.singleOrNull { it.viewIdResourceName == "android:id/button1" &&
                        it.text?.toString() in setOf("立即开始", "允许") && it.isClickable && it.isEnabled && it.isVisibleToUser }
                        ?: return@await false
                    check(button.refresh() && button.packageName?.toString() == "com.android.systemui" &&
                        button.text?.toString() in setOf("立即开始", "允许") && button.isEnabled && button.isVisibleToUser)
                    assertTrue("System consent button rejected", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    true
                }
            }
            await(own) { current ->
                current.singleOrNull { it.text?.startsWith("{") == true && it.text?.contains("\"localExecution\"") == true }
                    ?.let { JSONObject(it.text.toString()).optString("captureState") == "ready" } == true
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nEXPLICIT_SCREEN_CONSENT=ready\n") })
        } finally { automation.serviceInfo = original }
    }
}
