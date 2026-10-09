package local.tickets.companion.usb

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import local.tickets.companion.Safety
import local.tickets.companion.damaiLaunchIntent
import local.tickets.companion.execution.*
import local.tickets.companion.pages.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One authenticated USB operation. All OCR and private order data stay in this process.
 * Public self-drawn controls use explicit finger input; quantities and attendees use native actions.
 * NEXT permanently disables screenshots for the remainder of this operation.
 */
@Suppress("DEPRECATION")
internal class UsbDeviceExecutor(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val lease: UsbLease,
    private val update: (JSONObject) -> Unit,
) {
    private val context = instrumentation.targetContext
    private val parser = DamaiPageParser()
    private var captureId = 0L
    private var shape = emptyList<Int>()
    private var deadline = 0L
    private var nextAt: Long? = null
    private var lastDispatchAt: Long? = null
    private var actions = 0
    private var readRetries = 0
    private var lastReadFailure = "none"
    private var readMs = 0L
    private var observationMs = 0L
    private var visualMs = 0L
    @Volatile private var boundAtUptime: Long? = null
    private val gate = Any()
    private class Missing(val failure: NativeReadFailure) : IllegalStateException(failure.name)

    private fun displayShape(): List<Int> {
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point().also(display::getRealSize)
        return listOf(display.displayId, display.rotation, size.x, size.y)
    }
    private fun live(): Boolean {
        if (!lease.isLive(SystemClock.elapsedRealtime())) return false
        if (!context.getSystemService(android.os.PowerManager::class.java).isInteractive ||
            context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            lease.cancel("screen_locked"); return false
        }
        if (shape.isNotEmpty() && displayShape() != shape) { lease.cancel("display_changed"); return false }
        return SystemClock.elapsedRealtime() < deadline
    }
    private fun requireLive() {
        if (!live()) throw UsbProtocol.Invalid(if (lease.reason != "none") lease.reason else "deadline")
    }
    private fun waitUntil(until: Long) {
        while (SystemClock.elapsedRealtime() < minOf(until, deadline)) {
            requireLive()
            SystemClock.sleep(minOf(50L, until - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
    }

    private fun windowMatches(id: Int, capture: Boolean = false): Boolean {
        if (!live()) return false
        val root = automation.rootInActiveWindow ?: return false
        val same = try { root.packageName?.toString() == Safety.DAMAI && root.windowId == id } finally { root.recycle() }
        if (!same) return false
        val windows = automation.windows
        return try {
            if (windows.isEmpty() || windows.size > 16 || windows.none { it.id == id && it.isActive } ||
                windows.filter { it.isActive || it.isFocused }.any { it.id != id }) return false
            if (capture) {
                // Reject split-screen, input methods and other application/accessibility overlays.
                // System chrome is allowed only within narrow screen-edge bands, never a notification panel.
                for (window in windows.filter { it.id != id }) {
                    if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
                    val r = Rect().also(window::getBoundsInScreen)
                    val edge = r.bottom <= shape[3] / 10 || r.top >= shape[3] * 9 / 10 ||
                        r.right <= shape[2] / 20 || r.left >= shape[2] * 19 / 20
                    if (!edge) return false
                }
            }
            true
        } finally { windows.forEach { it.recycle() } }
    }

    private fun read(): NativeFrame {
        requireLive()
        val began = SystemClock.elapsedRealtime()
        val root = automation.rootInActiveWindow ?: throw Missing(NativeReadFailure.ROOT_UNAVAILABLE)
        val id = root.windowId
        val handles = mutableListOf<AccessibilityNodeInfo>()
        val specs = mutableListOf<NodeSpec>()
        var characters = 0
        fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            handles += node
            if (!lease.isLive(SystemClock.elapsedRealtime())) throw Missing(NativeReadFailure.CANCELLED)
            if (node.packageName?.toString() != Safety.DAMAI) throw Missing(NativeReadFailure.FOREIGN_PACKAGE)
            if (node.isPassword) throw Missing(NativeReadFailure.PASSWORD_FIELD)
            if (depth > NodeSnapshot.MAX_DEPTH || specs.size >= NodeSnapshot.MAX_NODES || node.childCount > NodeSnapshot.MAX_NODES)
                throw Missing(NativeReadFailure.SNAPSHOT_LIMIT)
            if (SystemClock.elapsedRealtime() - began >= 500) throw Missing(NativeReadFailure.READ_TIMEOUT)
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val resource = node.viewIdResourceName.orEmpty()
            characters += text.length + description.length + resource.length + Safety.DAMAI.length
            if (listOf(text, description, resource).any { it.length > NodeSnapshot.MAX_TEXT_LENGTH } || characters > NodeSnapshot.MAX_TOTAL_CHARACTERS)
                throw Missing(NativeReadFailure.SNAPSHOT_LIMIT)
            val r = Rect().also(node::getBoundsInScreen)
            val index = specs.size
            specs += NodeSpec(parent, Safety.DAMAI, resource, text, description, node.isVisibleToUser, node.isEnabled,
                node.isClickable, if (node.isCheckable) node.isChecked else null, node.isSelected,
                NodeBounds(r.left, r.top, r.right, r.bottom), node.isScrollable)
            for (i in 0 until node.childCount) visit(node.getChild(i) ?: throw Missing(NativeReadFailure.TREE_CHANGED), index, depth + 1)
        }
        try {
            visit(root, null, 1)
            requireLive()
            if (!windowMatches(id)) throw Missing(NativeReadFailure.WINDOW_UNAVAILABLE)
            if (SystemClock.elapsedRealtime() - began >= 500) throw Missing(NativeReadFailure.READ_TIMEOUT)
            val snapshot = try { NodeSnapshot.create(++captureId, specs, true) }
                catch (_: IllegalArgumentException) { throw Missing(NativeReadFailure.INVALID_SNAPSHOT) }
            readMs = SystemClock.elapsedRealtime() - began
            return NativeFrame(snapshot, specs, id, began, handles)
        } catch (error: Exception) {
            handles.forEach { it.recycle() }
            if (error is Missing || error is UsbProtocol.Invalid) throw error
            throw Missing(NativeReadFailure.PLATFORM_ERROR)
        }
    }

    private fun blank(bitmap: Bitmap): Boolean {
        var lit = 0
        var count = 0
        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 24)) {
            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 24)) {
                val value = bitmap.getPixel(x, y)
                if (((value ushr 16) and 255) > 12 || ((value ushr 8) and 255) > 12 || (value and 255) > 12) lit++
                count++
            }
        }
        return count == 0 || lit * 100 < count
    }

    private fun capture(frame: NativeFrame): VisualEvidence {
        if (nextAt != null || !ExecutionCapturePolicy.allowed(frame.snapshot)) throw UsbProtocol.Invalid("capture_page_not_allowed")
        if (frame.snapshot.nodes.first().bounds != NodeBounds(0, 0, shape[2], shape[3])) throw UsbProtocol.Invalid("full_screen_required")
        val began = SystemClock.elapsedRealtime()
        var bitmap: Bitmap? = null
        val until = minOf(deadline, began + 1000)
        while (bitmap == null && SystemClock.elapsedRealtime() < until) {
            requireLive()
            if (!windowMatches(frame.windowId, capture = true)) throw UsbProtocol.Invalid("capture_window_not_allowed")
            read().use { if (!frame.samePage(it)) throw UsbProtocol.Invalid("native_page_changed") }
            bitmap = automation.takeScreenshot()
            if (bitmap == null) waitUntil(SystemClock.elapsedRealtime() + 50)
        }
        val image = bitmap ?: throw UsbProtocol.Invalid("public_capture_unavailable")
        val capturedAt = SystemClock.elapsedRealtime()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        var task: com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text>? = null
        try {
            if (image.width != shape[2] || image.height != shape[3] || blank(image)) throw UsbProtocol.Invalid("secure_or_blank_frame")
            task = recognizer.process(InputImage.fromBitmap(image, 0))
            val text = Tasks.await(task, 5, TimeUnit.SECONDS)
            requireLive()
            read().use { if (!frame.samePage(it)) throw UsbProtocol.Invalid("native_page_changed") }
            if (!windowMatches(frame.windowId, capture = true)) throw UsbProtocol.Invalid("capture_window_not_allowed")
            val picker = ExecutionCapturePolicy.picker(frame.snapshot)
            if (ExecutionCapturePolicy.unsafeWords(listOf(text.text), picker)) throw UsbProtocol.Invalid("capture_page_not_allowed")
            val cards = if (picker) ExecutionCapturePolicy.cards(frame.snapshot) ?: throw UsbProtocol.Invalid("cards_unverified") else emptyList()
            val words = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { r ->
                VisualWord(line.text, NodeBounds(r.left, r.top, r.right, r.bottom))
            } }.filter { frame.snapshot.nodes.first().bounds.contains(it.bounds) }
            val evidence = VisualEvidence(frame.snapshot.captureId, image.width, image.height, words,
                cards.associate { it.index to ExecutionVisualSelection.selected(image.width, image.height, it.bounds, image::getPixel) }, capturedAt)
            visualMs = SystemClock.elapsedRealtime() - began
            if (SystemClock.elapsedRealtime() - capturedAt > 2000) throw UsbProtocol.Invalid("stale_visual_evidence")
            return evidence
        } finally {
            val pending = task
            if (pending == null || pending.isComplete) { image.recycle(); recognizer.close() }
            else pending.addOnCompleteListener { image.recycle(); recognizer.close() }
        }
    }

    private fun report(phase: String, reason: String, page: PageObservation? = null,
                       verified: ConfirmationReview.Verified? = null): JSONObject = JSONObject()
        .put("phase", phase).put("reason", reason).put("actions", actions)
        .put("metrics", JSONObject().put("readMs", observationMs).put("nativeReadMs", readMs).put("visualMs", visualMs).put("nativeReadRetries", readRetries))
        .put("lastNativeReadFailure", lastReadFailure).put("automaticSubmissionSupported", false)
        .put("attendeeIdentityExported", false).also { if (page != null) it.put("page", UsbPublicPage.build(page, verified)) }

    private fun sameEvent(page: PageObservation, expected: String): Boolean =
        page.eventTitle.known()?.let(DamaiPageParser::eventKey) == DamaiPageParser.eventKey(expected)

    private fun pointer(bounds: NodeBounds): Boolean {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = (bounds.left + bounds.right) / 2f; y = (bounds.top + bounds.bottom) / 2f; pressure = 1f; size = 1f
        }
        val downAt = SystemClock.uptimeMillis()
        fun inject(kind: Int): Boolean {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), kind, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            return try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        var completed = false
        try {
            requireLive()
            if (!inject(MotionEvent.ACTION_DOWN)) return false
            waitUntil(SystemClock.elapsedRealtime() + 60)
            requireLive()
            completed = inject(MotionEvent.ACTION_UP)
            return completed
        } finally { if (!completed) runCatching { inject(MotionEvent.ACTION_CANCEL) } }
    }

    private fun dispatch(frame: NativeFrame, visual: VisualEvidence?, action: NativeAction, engine: LocalExecution? = null) {
        val second = visual?.let { capture(frame) }
        if (!VisualDispatchEvidence.agrees(visual, second, SystemClock.elapsedRealtime(), frame.snapshot))
            throw UsbProtocol.Invalid("preclick_visual_changed")
        read().use { fresh ->
            if (!frame.samePage(fresh)) throw UsbProtocol.Invalid("preclick_page_changed")
            if (fresh.snapshot.nodes.first().bounds != NodeBounds(0, 0, shape[2], shape[3])) throw UsbProtocol.Invalid("full_screen_required")
            val touchIndex = action.touchNodeIndex
            val nextBounds = if (action.kind == ActionKind.NEXT) ExecutionCapturePolicy.pickerNextBounds(fresh.snapshot) else null
            if (action.kind == ActionKind.NEXT && nextBounds == null) throw UsbProtocol.Invalid("next_target_unverified")
            if (touchIndex != null && ScopedTouchTarget.nodeIndex(fresh.snapshot, action.nodeIndex, action.kind) != touchIndex)
                throw UsbProtocol.Invalid("touch_target_unverified")
            if (action.kind == ActionKind.BUYER && (visual != null || touchIndex != null)) throw UsbProtocol.Invalid("private_action_requires_native")
            val sent = LocalDispatchGuard.dispatch(gate, {
                fresh.prepareClick(action.nodeIndex, touchIndex == action.nodeIndex) &&
                    (touchIndex == null || touchIndex == action.nodeIndex || fresh.prepareClick(touchIndex, true))
            }, { lease.isLive(SystemClock.elapsedRealtime()) }, {
                windowMatches(fresh.windowId, capture = visual != null) &&
                    VisualDispatchEvidence.agrees(visual, second, SystemClock.elapsedRealtime(), frame.snapshot)
            }, SystemClock::elapsedRealtime, deadline, fresh.startedAt) {
                requireLive()
                val dispatchedAt = SystemClock.elapsedRealtime()
                if (engine != null && !engine.actionDispatched(action, dispatchedAt)) return@dispatch false
                lastDispatchAt = dispatchedAt
                if (action.kind == ActionKind.NEXT) nextAt = dispatchedAt
                val accepted = when {
                    nextBounds != null -> pointer(nextBounds)
                    touchIndex != null -> pointer(fresh.snapshot.nodes[touchIndex].bounds)
                    else -> fresh.performClick(action.nodeIndex)
                }
                if (accepted) actions++
                accepted
            }
            if (sent != LocalDispatchGuard.Result.SENT) throw UsbProtocol.Invalid("dispatch_${sent.name.lowercase()}")
        }
    }

    /** Build only the two explicitly requested catalog-navigation actions, never an arbitrary selector. */
    private fun navigationAction(frame: NativeFrame, page: PageObservation, request: UsbProtocol.Request): NativeAction? {
        if (!sameEvent(page, request.eventTitle) || page.blockers.isNotEmpty()) throw UsbProtocol.Invalid("public_target_unverified")
        if (request.operation == UsbProtocol.Operation.OPEN_PICKER) {
            if (page.kind == PageKind.PICKER) return null
            if (page.kind != PageKind.DETAIL || page.detailAvailability != Availability.AVAILABLE) throw UsbProtocol.Invalid("detail_not_purchasable")
            val node = frame.snapshot.nodes.singleOrNull { it.resourceId == DamaiPageParser.PREFIX + DamaiPageParser.DETAIL_BUTTON }
                ?: throw UsbProtocol.Invalid("open_target_missing")
            val touch = ScopedTouchTarget.nodeIndex(frame.snapshot, node.index, ActionKind.OPEN_PICKER)
            if (touch == null && !node.clickable) throw UsbProtocol.Invalid("open_target_unverified")
            return NativeAction(frame.snapshot.captureId, node.index, ActionKind.OPEN_PICKER, touchNodeIndex = touch)
        }
        if (page.kind != PageKind.PICKER) throw UsbProtocol.Invalid("picker_required")
        val session = page.sessions.singleOrNull { DamaiPageParser.sessionKey(it.label) == DamaiPageParser.sessionKey(request.session) }
            ?: throw UsbProtocol.Invalid("session_missing_or_ambiguous")
        if (session.availability != Availability.AVAILABLE || session.selection == SelectionEvidence.UNKNOWN) throw UsbProtocol.Invalid("session_unverified")
        if (session.selection.selected()) return null
        val card = frame.snapshot.nodes[session.nodeIndex]
        val owner = if (card.clickable) card else card.parentIndex?.let { frame.snapshot.nodes[it] }
            ?: throw UsbProtocol.Invalid("session_target_missing")
        val touch = ScopedTouchTarget.nodeIndex(frame.snapshot, owner.index, ActionKind.SESSION)
        if (touch == null && owner.index != card.index) throw UsbProtocol.Invalid("session_target_unverified")
        return NativeAction(frame.snapshot.captureId, owner.index, ActionKind.SESSION, session.label, touchNodeIndex = touch)
    }

    fun execute(request: UsbProtocol.Request): JSONObject {
        shape = displayShape()
        if (shape[0] != 0 || shape[1] != 0) throw UsbProtocol.Invalid("primary_portrait_display_required")
        val version = context.packageManager.getPackageInfo(Safety.DAMAI, 0).versionName
        if (version != "9.0.35") throw UsbProtocol.Invalid("damai_version_unsupported")
        val began = SystemClock.elapsedRealtime()
        deadline = began + 30_000
        val engine = request.plan?.let { LocalExecution(it, System.currentTimeMillis(), began) }
        if (engine != null) deadline = engine.deadline()
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // Content events must reach the framework's node cache after selections/checkbox changes.
            // Our listener below still reads only window/package metadata, never event text or sources.
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        }
        automation.setOnAccessibilityEventListener { event ->
            val bound = boundAtUptime
            if (bound != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.eventTime >= bound &&
                event.packageName != null && event.packageName.toString() != Safety.DAMAI) lease.cancel("foreground_changed")
        }
        requireLive()
        var root = automation.rootInActiveWindow
        val onDamai = try { root?.packageName?.toString() == Safety.DAMAI } finally { root?.recycle() }
        if (!onDamai) {
            context.startActivity(context.damaiLaunchIntent() ?: throw UsbProtocol.Invalid("damai_not_installed"))
            val until = minOf(deadline, SystemClock.elapsedRealtime() + 3000)
            var resumed = false
            while (!resumed && SystemClock.elapsedRealtime() < until) {
                requireLive()
                root = automation.rootInActiveWindow
                resumed = try { root?.packageName?.toString() == Safety.DAMAI } finally { root?.recycle() }
                if (!resumed) waitUntil(SystemClock.elapsedRealtime() + 50)
            }
            if (!resumed) throw UsbProtocol.Invalid("damai_not_foreground")
        }
        boundAtUptime = SystemClock.uptimeMillis()
        var navigationSentAt: Long? = null
        var navigationKind: ActionKind? = null
        var lastProgressAt = 0L
        val initialReadUntil = minOf(deadline, SystemClock.elapsedRealtime() + 3000)
        var observedPage = false
        while (live()) {
            val observationBegan = SystemClock.elapsedRealtime()
            val frame = try { read() } catch (missing: Missing) {
                lastReadFailure = missing.failure.name.lowercase()
                if (!observedPage && missing.failure.transientAfterAction && SystemClock.elapsedRealtime() < initialReadUntil) {
                    waitUntil(SystemClock.elapsedRealtime() + 50)
                    continue
                }
                if (engine == null && navigationSentAt != null && missing.failure.transientAfterAction &&
                    SystemClock.elapsedRealtime() - navigationSentAt!! in 0 until 500L) {
                    readRetries++
                    waitUntil(SystemClock.elapsedRealtime() + 50)
                    continue
                }
                val waiting = engine?.nativeReadUnavailable(SystemClock.elapsedRealtime(), missing.failure)
                if (waiting is ExecutionDecision.Wait) {
                    readRetries++
                    waitUntil(waiting.untilElapsedMs)
                    continue
                }
                throw UsbProtocol.Invalid((waiting as? ExecutionDecision.Stopped)?.reason ?: "native_page_unavailable:$lastReadFailure")
            }
            frame.use {
                val nativePage = parser.parse(frame.snapshot)
                if (!observedPage && SystemClock.elapsedRealtime() < initialReadUntil &&
                    (nativePage.kind == PageKind.LOADING || (nativePage.kind == PageKind.UNKNOWN && nativePage.blockers == setOf(PageBlocker.UNKNOWN_PAGE)))) {
                    // Startup/flag propagation only: no capture or action on an unrecognized page.
                    waitUntil(SystemClock.elapsedRealtime() + 50)
                    return@use
                }
                if (nextAt != null && nativePage.kind == PageKind.PICKER) {
                    if (SystemClock.elapsedRealtime() - nextAt!! >= 10000) throw UsbProtocol.Invalid("action_effect_unconfirmed")
                    waitUntil(SystemClock.elapsedRealtime() + 80)
                    return@use
                }
                val visual = if (nextAt == null && nativePage.kind in setOf(PageKind.DETAIL, PageKind.PICKER)) {
                    try { capture(frame) } catch (changed: UsbProtocol.Invalid) {
                        if (!observedPage && changed.reason == "native_page_changed" && SystemClock.elapsedRealtime() < initialReadUntil) {
                            waitUntil(SystemClock.elapsedRealtime() + 50)
                            return@use
                        }
                        throw changed
                    }
                } else null
                val page = if (visual == null) nativePage else parser.parse(frame.snapshot, visual)
                observationMs = SystemClock.elapsedRealtime() - observationBegan
                observedPage = true
                if (request.operation == UsbProtocol.Operation.INSPECT) return report("inspected", "page_inspected", page)
                if (engine == null) {
                    val sentAt = navigationSentAt
                    if (sentAt != null) {
                        val effect = sameEvent(page, request.eventTitle) && page.blockers.isEmpty() && page.kind == PageKind.PICKER &&
                            (navigationKind == ActionKind.OPEN_PICKER || (page.sessions.count { it.selection.selected() } == 1 &&
                                page.sessions.any { it.selection.selected() && DamaiPageParser.sessionKey(it.label) == DamaiPageParser.sessionKey(request.session) }))
                        if (effect) return report("inspected", "navigation_verified", page)
                        if (page.kind !in setOf(PageKind.LOADING, PageKind.QUEUE, PageKind.DETAIL, PageKind.PICKER) ||
                            SystemClock.elapsedRealtime() - sentAt >= 10000) throw UsbProtocol.Invalid("navigation_effect_unconfirmed")
                        waitUntil(SystemClock.elapsedRealtime() + 80)
                    } else {
                        val action = navigationAction(frame, page, request) ?: return report("inspected", "navigation_already_satisfied", page)
                        dispatch(frame, visual, action)
                        navigationKind = action.kind
                        navigationSentAt = checkNotNull(lastDispatchAt)
                        waitUntil(SystemClock.elapsedRealtime() + 40)
                    }
                    return@use
                }
                when (val decision = engine.tick(SystemClock.elapsedRealtime(), frame.snapshot, visual)) {
                    is ExecutionDecision.Stopped -> return report("stopped", decision.reason, page)
                    is ExecutionDecision.Finished -> {
                        read().use { fresh ->
                            if (!frame.samePage(fresh)) throw UsbProtocol.Invalid("final_order_changed")
                            val review = reviewConfirmation(fresh.snapshot, parser.parse(fresh.snapshot), request.plan!!, request.plan.targets.single())
                            if (review !is ConfirmationReview.Verified || review.change != null) throw UsbProtocol.Invalid("final_order_unverified")
                            return report("confirmed", decision.reason, page, review)
                        }
                    }
                    is ExecutionDecision.Wait -> {
                        if (request.operation == UsbProtocol.Operation.PREPARE && decision.reason == "waiting_sale")
                            return report("ready", "waiting_sale", page).put("verification", "event_only")
                        if (SystemClock.elapsedRealtime() - lastProgressAt >= 500) {
                            update(report(if (decision.reason == "waiting_start") "waiting" else "running", decision.reason, page))
                            lastProgressAt = SystemClock.elapsedRealtime()
                        }
                        waitUntil(decision.untilElapsedMs)
                    }
                    is ExecutionDecision.Click -> {
                        if (request.operation == UsbProtocol.Operation.PREPARE && decision.action.kind == ActionKind.NEXT) {
                            val second = visual?.let { capture(frame) }
                            if (!VisualDispatchEvidence.agrees(visual, second, SystemClock.elapsedRealtime(), frame.snapshot)) throw UsbProtocol.Invalid("preparation_visual_changed")
                            read().use { fresh -> if (!frame.samePage(fresh)) throw UsbProtocol.Invalid("preparation_page_changed") }
                            return report("ready", "selected", page).put("verification", "full")
                        }
                        dispatch(frame, visual, decision.action, engine)
                        update(report("running", "waiting_action_effect", page))
                        waitUntil(SystemClock.elapsedRealtime() + 40)
                    }
                }
            }
        }
        throw UsbProtocol.Invalid(if (lease.reason != "none") lease.reason else "deadline")
    }
}
