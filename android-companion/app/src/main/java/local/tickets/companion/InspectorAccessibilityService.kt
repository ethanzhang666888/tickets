package local.tickets.companion

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** No gestures/global actions. Local tasks use separately validated, native-only clicks and stop at confirmation. */
class InspectorAccessibilityService : AccessibilityService() {
    private var bridge: LocalBridge? = null
    private var screenReceiverRegistered = false
    // The audit stops projection, so its lock revocation must not depend on the
    // capture service's receiver still existing or on scoped app events arriving.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Session.revokeNativeAudit()
                local.tickets.companion.execution.LocalExecutionRuntime.stop("screen_locked")
            }
        }
    }
    override fun onServiceConnected() {
        Session.accessibility = this
        if (!screenReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenReceiverRegistered = true
        }
        bridge = LocalBridge().also { it.start() }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never stringify or log accessibility events; they can contain private user text.
        if (Session.locked(this)) Session.revokeNativeAudit()
        Session.capture?.checkForeground()
        local.tickets.companion.execution.LocalExecutionRuntime.foregroundChanged()
    }
    override fun onInterrupt() {
        local.tickets.companion.execution.LocalExecutionRuntime.stop("accessibility_interrupted")
        Session.revokeNativeAudit(); Session.capture?.stopSafely("accessibility_interrupted")
    }
    override fun onDestroy() {
        local.tickets.companion.execution.LocalExecutionRuntime.stop("accessibility_lost")
        Session.revokePairingCode()
        Session.revokeNativeAudit()
        Session.capture?.stopSafely("accessibility_lost")
        Session.accessibility = null
        bridge?.close()
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false }
        super.onDestroy()
    }

    fun guardError(): String? {
        if (Session.locked(this)) return "screen_locked"
        if (Session.accessibility !== this) return "accessibility_lost"
        // Examine one root snapshot: absence during an idle transition is distinct
        // from a known out-of-scope package or a failed/truncated node scan.
        @Suppress("DEPRECATION")
        val root = rootInActiveWindow ?: return "foreground_unavailable"
        val pkg = root.packageName?.toString()
        if (pkg == Safety.OWN && !Session.syntheticVisible) {
            @Suppress("DEPRECATION") root.recycle()
            return null // Secure settings staging; never traverse its pairing code.
        }
        if (pkg != Safety.OWN && pkg != Safety.DAMAI) {
            @Suppress("DEPRECATION") root.recycle()
            return "foreground_not_allowed"
        }
        val result = scan(root) ?: return "node_scan_failed"
        if (result.pageKind == "payment") return "payment_foreground"
        if (result.truncated) {
            Session.lastIncompleteScan = result
            return "node_scan_incomplete"
        }
        return null
    }

    fun inspect(): SafeSummary? {
        if (Session.locked(this)) {
            Session.lastError = "screen_locked"
            Session.capture?.stopSafely("screen_locked")
            return null
        }
        val result = scan()
        if (result == null) {
            Session.lastError = "foreground_not_allowed"
            Session.capture?.stopSafely("foreground_not_allowed")
            return null
        }
        if (result.pageKind == "payment" || result.truncated) {
            if (result.truncated) Session.lastIncompleteScan = result
            val reason = if (result.pageKind == "payment") "payment_foreground" else "node_scan_incomplete"
            Session.lastError = reason
            Session.capture?.stopSafely(reason)
            Session.summary = SafeSummary(pageKind = result.pageKind, captureState = reason)
            return null
        }
        Session.lastError = "none"
        return result
    }

    /** Explicit one-shot native audit only: capture is already stopped by Session. */
    @Suppress("DEPRECATION")
    fun inspectNativeAudit(): SafeSummary? {
        if (Session.locked(this) || Session.accessibility !== this) return null
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != Safety.DAMAI) { root.recycle(); return null }
        val windowId = root.windowId
        val indicators = NativeAuditIndicators()
        val result = scan(root, indicators) ?: return null
        if (result.truncated) { Session.lastIncompleteScan = result; return null }
        if (Session.locked(this)) return null
        val current = rootInActiveWindow ?: return null
        val sameWindow = try { current.packageName?.toString() == Safety.DAMAI && current.windowId == windowId }
            finally { current.recycle() }
        if (!sameWindow || !auditWindows(windowId, indicators)) return null
        Session.nativeAuditIndicators = indicators
        return result
    }

    /** Inspect package/window metadata only; never traverse a second window or read its title. */
    @Suppress("DEPRECATION")
    private fun auditWindows(inspectedWindowId: Int, audit: NativeAuditIndicators): Boolean {
        val started = SystemClock.elapsedRealtime()
        val currentWindows = try { windows } catch (_: RuntimeException) { return false }
        try {
            if (currentWindows.size > 16) return false
            for (window in currentWindows) {
                if (SystemClock.elapsedRealtime() - started > NodeScanBudget.MAX_DURATION_MS) return false
                val root = window.root ?: continue
                try {
                    audit.observeWindow(root.packageName?.toString() == Safety.DAMAI,
                        window.isActive, window.isFocused, window.id == inspectedWindowId)
                } finally { root.recycle() }
            }
            return !Session.locked(this)
        } catch (_: RuntimeException) { return false }
        finally { currentWindows.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun scan(snapshot: AccessibilityNodeInfo? = rootInActiveWindow, audit: NativeAuditIndicators? = null): SafeSummary? {
        val start = SystemClock.elapsedRealtime()
        val root = snapshot ?: return null
        val pkg = root.packageName?.toString()
        // Only the package identifier is examined for out-of-scope windows, never their text.
        if (!Safety.canInspect(pkg, false, true, false) || (pkg == Safety.OWN && !Session.syntheticVisible)) { root.recycle(); return null }
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int?>>()
        queue.add(root to null)
        var count = 0
        var textCount = 0
        var clickable = 0
        var candidates = 0
        var payment = false
        var selection = false
        var detail = false
        var truncated = false
        val pickerEvidence = PickerCaptureEvidence()
        try {
            while (queue.isNotEmpty()) {
                if (NodeScanBudget.exceeded(SystemClock.elapsedRealtime() - start, count)) { truncated = true; break }
                val (node, parentIndex) = queue.removeFirst()
                try {
                    // Ignore embedded out-of-scope nodes, including keyboards and overlays.
                    if (node.packageName?.toString() != pkg) continue
                    val nodeIndex = count++
                    audit?.structure?.observe(parentIndex, node.viewIdResourceName, node.className,
                        node.isVisibleToUser, node.isEnabled, node.isClickable, node.isSelected,
                        node.isCheckable, node.isChecked)
                    pickerEvidence.observe(parentIndex, node.viewIdResourceName, node.isVisibleToUser,
                        if (node.isVisibleToUser) node.text else null,
                        if (node.isVisibleToUser) node.contentDescription else null)
                    if (node.isVisibleToUser) {
                        val text = node.text
                        val description = node.contentDescription
                        audit?.observe(node.viewIdResourceName, text, description, true, node.isCheckable, node.isChecked, node.isScrollable, node.className)
                        if (audit?.cashierDetected == true) return null
                        if (!text.isNullOrBlank() || !description.isNullOrBlank()) textCount++
                        if (node.isClickable && node.isEnabled) clickable++
                        if (Safety.isPayment(text) || Safety.isPayment(description)) payment = true
                        if (Safety.isCandidate(text) || Safety.isCandidate(description)) { candidates++; detail = true }
                        if (text?.contains("票档") == true || text?.contains("场次") == true) selection = true
                    }
                    for (i in 0 until node.childCount.coerceAtMost(NodeScanBudget.MAX_NODES)) {
                        if (queue.size + count >= NodeScanBudget.MAX_NODES) { truncated = true; break }
                        node.getChild(i)?.let { queue.addLast(it to nodeIndex) }
                    }
                } finally { node.recycle() }
            }
        } catch (_: RuntimeException) {
            return null
        } finally { while (queue.isNotEmpty()) queue.removeFirst().first.recycle() }
        // MainActivity is only allowed as a capture-consent staging screen, not an inspection page.
        val recognizedPicker = pkg == Safety.DAMAI && pickerEvidence.recognized(!truncated)
        val kind = when {
            payment && !recognizedPicker -> "payment"
            recognizedPicker -> "selection"
            pkg == Safety.OWN && Session.syntheticVisible -> "synthetic"
            selection -> "selection"
            detail -> "detail"
            else -> "unknown"
        }
        return SafeSummary(kind, count, textCount, clickable, candidates, SystemClock.elapsedRealtime() - start, truncated,
            pickerLayoutRecognized = recognizedPicker)
    }

    fun pickerLayoutRecognized(): Boolean = !Session.locked(this) && Session.accessibility === this
        && scan()?.pickerLayoutRecognized == true

    @Suppress("DEPRECATION")
    fun clickSynthetic(): Boolean {
        if (!Session.syntheticVisible || Session.locked(this)) return false
        val root = rootInActiveWindow ?: return false
        val matches = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (root.packageName?.toString() != Safety.OWN) return false
            matches.addAll(root.findAccessibilityNodeInfosByViewId("${Safety.OWN}:id/synthetic_target"))
            val node = matches.singleOrNull() ?: return false
            if (!Safety.canClickTest(node.packageName?.toString(), matches.size, node.isClickable && node.isEnabled, node.isVisibleToUser)) return false
            if (node.className?.toString() != "android.widget.Button") return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally { matches.forEach { it.recycle() }; root.recycle() }
    }
}
