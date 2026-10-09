package local.tickets.companion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Explicitly selected acceptance test: configures only our local UI; never clicks Damai or system consent.
 * No networking, screenshot, hierarchy export, pairing-code access, or private attendee export.
 * Run only on the authorized Ella Zhengzhou picker with screen-reading consent already granted.
 */
@Suppress("DEPRECATION")
class LocalBusinessDeviceTest {
    private val own = "local.tickets.companion"
    private lateinit var automation: UiAutomation

    /** Never inspect other packages or hidden pairing-code controls. */
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != own) { root.recycle(); return null }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var count = 0
        fun visit(node: AccessibilityNodeInfo) {
            try {
                check(++count <= 512 && node.packageName?.toString() == own && !node.isPassword)
                if (node.isImportantForAccessibility && predicate(node)) matches += AccessibilityNodeInfo.obtain(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            } finally { node.recycle() }
        }
        try {
            visit(root)
            if (matches.size == 1) return matches.removeAt(0)
            return null
        } finally { matches.forEach { it.recycle() } }
    }

    private fun waitNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.elapsedRealtime() + 4000
        while (SystemClock.elapsedRealtime() < until) {
            find(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Own UI element unavailable: $description")
    }

    private fun click(label: String) {
        val first = waitNode("button") { it.text?.toString() == label && it.isClickable }
        try { if (!first.isVisibleToUser) first.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
        finally { first.recycle() }
        val node = waitNode("visible button") { it.text?.toString() == label && it.isClickable && it.isVisibleToUser }
        try { check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Own button click rejected" } }
        finally { node.recycle() }
    }

    private fun fill(hint: String, value: String) {
        val node = waitNode("configuration field") { it.isEditable && it.hintText?.toString() == hint }
        try {
            assertTrue("Own field update rejected", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        } finally { node.recycle() }
    }

    private fun openMain() = InstrumentationRegistry.getInstrumentation().context.startActivity(
        Intent().setComponent(ComponentName(own, "$own.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun ownStatus(): JSONObject {
        val node = waitNode("local status") { it.text?.startsWith("{") == true && it.text?.contains("\"localExecution\"") == true }
        return try { JSONObject(node.text.toString()) } finally { node.recycle() }
    }

    /** Read fixed local status only; useful before running a real task after a permission interruption. */
    @Test fun inspectLocalReadinessWithoutStartingTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        try {
            openMain()
            val status = ownStatus()
            val evidence = JSONObject()
                .put("accessibilityConnected", status.optBoolean("accessibilityConnected"))
                .put("captureState", status.optString("captureState"))
                .put("error", status.optString("error"))
                .put("localExecution", status.getJSONObject("localExecution"))
                .put("captureDiagnostics", status.optJSONObject("captureDiagnostics"))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nLOCAL_READINESS=$evidence\n") })
        } finally { automation.serviceInfo = original }
    }

    @Test fun stopScreenReadingWithoutStartingTask() {
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        openMain()
        check(!ownStatus().getJSONObject("localExecution").getBoolean("active"))
        click("停止屏幕读取并撤销配对码")
        val until = SystemClock.elapsedRealtime() + 3000
        while (ownStatus().optString("captureState") == "ready" && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        check(ownStatus().optString("captureState") != "ready")
    }

    @Test fun authorizedEllaPlanRunsLocallyWithoutSubmission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        val finished = CountDownLatch(1)
        var terminal: JSONObject? = null
        var started = false
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            }
            automation.setOnAccessibilityEventListener { event ->
                if (event.packageName?.toString() == own && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                    val notification = event.parcelableData as? Notification
                    val text = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    if (text.startsWith("已核对选票和观演人") || text.startsWith("任务已停止：")) finished.countDown()
                }
            }
            // Prove the authorized picker is the actual starting page before opening our UI.
            // Only query fixed public selection/title IDs; never traverse attendee content.
            // After manual consent the user can remain in our app. Resume Damai directly,
            // avoiding launcher/recents windows which correctly revoke screen-reading consent.
            var pickerRoot = checkNotNull(automation.rootInActiveWindow)
            if (pickerRoot.packageName?.toString() == own) {
                pickerRoot.recycle()
                instrumentation.context.startActivity(checkNotNull(instrumentation.targetContext.damaiLaunchIntent()))
                val until = SystemClock.elapsedRealtime() + 3000
                var resumed: AccessibilityNodeInfo? = null
                while (resumed == null && SystemClock.elapsedRealtime() < until) {
                    val candidate = automation.rootInActiveWindow
                    if (candidate?.packageName?.toString() == Safety.DAMAI) {
                        val titles = listOf("tv_title", local.tickets.companion.pages.DamaiPageParser.DETAIL_TITLE)
                            .flatMap { candidate.findAccessibilityNodeInfosByViewId("cn.damai:id/$it") }
                        val ready = try { titles.any { it.isVisibleToUser && !it.text.isNullOrBlank() } }
                            finally { titles.forEach { it.recycle() } }
                        if (ready) resumed = candidate else candidate.recycle()
                    } else candidate?.recycle()
                    if (resumed == null) SystemClock.sleep(50)
                }
                pickerRoot = checkNotNull(resumed) { "Damai did not resume; no task started" }
            }
            try {
                check(pickerRoot.packageName?.toString() == Safety.DAMAI) { "Open the authorized picker first" }
                val pickerNodes = pickerRoot.findAccessibilityNodeInfosByViewId("cn.damai:id/project_detail_perform_price_flowlayout")
                val isPicker = try { pickerNodes.count { it.isVisibleToUser } == 1 } finally { pickerNodes.forEach { it.recycle() } }
                if (isPicker) {
                    for (id in listOf("project_detail_perform_flowlayout", "project_detail_perform_price_flowlayout")) {
                        val nodes = pickerRoot.findAccessibilityNodeInfosByViewId("cn.damai:id/$id")
                        try { check(nodes.count { it.isVisibleToUser } == 1) { "Authorized picker is not ready" } }
                        finally { nodes.forEach { it.recycle() } }
                    }
                }
                val titleId = if (isPicker) "tv_title" else local.tickets.companion.pages.DamaiPageParser.DETAIL_TITLE
                val titles = pickerRoot.findAccessibilityNodeInfosByViewId("cn.damai:id/$titleId")
                try {
                    val expected = local.tickets.companion.pages.DamaiPageParser.eventKey("郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站")
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nAUTHORIZED_START_PAGE=" +
                        JSONObject().put("picker", isPicker).put("titleNodes", titles.size)
                            .put("publicEventTitles", org.json.JSONArray(titles.filter { it.isVisibleToUser }.map { it.text?.toString().orEmpty() })) + "\n") })
                    check(titles.filter { it.isVisibleToUser }.singleOrNull()?.let {
                        local.tickets.companion.pages.DamaiPageParser.eventKey(it.text?.toString().orEmpty()) == expected
                    } == true) { "Authorized event title does not match" }
                } finally { titles.forEach { it.recycle() } }
            } finally { pickerRoot.recycle() }
            openMain()
            var before = ownStatus()
            val readyBy = SystemClock.elapsedRealtime() + 3000
            while ((!before.optBoolean("accessibilityConnected") || before.optString("captureState") != "ready") && SystemClock.elapsedRealtime() < readyBy) {
                SystemClock.sleep(50)
                before = ownStatus()
            }
            check(before.optBoolean("accessibilityConnected") && before.optString("captureState") == "ready") { "Permissions unavailable: ${before.optString("captureState")}" }
            check(!before.getJSONObject("localExecution").getBoolean("active")) { "Existing task must not be changed" }
            click("配置手机本地任务（仅到确认页，不提交）")
            fill("准确演出名称", "郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站")
            fill("每行一个允许购买的日期与票档组合", "2026-11-07 19:00 | 内场880元 | 880")
            fill("张数", "1")
            fill("观演人：留空默认前N位，或每行一位", "")
            fill("最高总预算（元）", "880")
            fill("北京时间 uuuu-MM-dd HH:mm:ss；留空立即开始", "")
            fill("从开始起持续分钟数，最多2880", "1")
            fill("仅尝试第一目标的分钟数，0至60", "0")
            click("核对任务并开始（不提交、不付款）")
            // This is our task confirmation, not the system screen-reading consent dialog.
            waitNode("local task confirmation") { it.text?.toString() == "确认手机本地任务" }.recycle()
            click("确认并开始")
            started = true
            // Own terminal notification is the signal; the one-minute task deadline bounds the fallback.
            finished.await(70, TimeUnit.SECONDS)
            openMain()
            val status = ownStatus()
            terminal = JSONObject().put("localExecution", status.getJSONObject("localExecution"))
                .put("captureState", status.optString("captureState")).put("error", status.optString("error"))
                .put("captureDiagnostics", status.optJSONObject("captureDiagnostics"))
            assertEquals("confirmation_verified_manual_submit", terminal.getJSONObject("localExecution").getString("reason"))
        } finally {
            // Returning to our app revokes any unfinished task even if notification delivery was unavailable.
            if (started && terminal == null) runCatching { openMain(); click("停止手机本地任务") }
            automation.setOnAccessibilityEventListener(null)
            automation.serviceInfo = original
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nLOCAL_BUSINESS_EVIDENCE=${terminal ?: JSONObject().put("completed", false)}\n") })
        }
    }
}
