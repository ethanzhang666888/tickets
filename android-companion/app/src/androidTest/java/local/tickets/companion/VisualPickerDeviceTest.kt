package local.tickets.companion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import local.tickets.companion.execution.*
import local.tickets.companion.pages.*
import local.tickets.companion.policy.TicketTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Explicit public-page diagnostics. Images stay in memory after strict ownership checks.
 * Inspection never clicks. Separate action tests stop at picker opening or tier selection,
 * never dispatch NEXT, buyer, submission or payment. Production uses authorized MediaProjection.
 */
@Suppress("DEPRECATION")
class VisualPickerDeviceTest {
    private val event = "郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站"
    private var selectTierOnly = false
    private var explicitFingerTier = false
    private var unknownToolControl = false
    private var requireUnselectedTier = false

    /** Matched negative control: all event fields/timing match the finger control except toolType. */
    @Test fun unknownToolControlLeavesAuthorizedTierUnselected() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        unknownToolControl = true
        selectTierOnly = true
        inspectAuthorizedPickerVisualCategories()
        selectTierOnly = false
        resumeDamai = false
        requireUnselectedTier = true
        inspectAuthorizedPickerVisualCategories()
    }

    /** Public API compatibility control: same center/60ms contact, explicit finger tool type. */
    @Test fun selectAuthorizedTierWithExplicitFingerWithoutAdvancing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        explicitFingerTier = true
        selectAuthorizedTierWithoutAdvancing()
    }
    private var requireAuthorizedSelection = false
    private var resumeDamai = true
    private var inspectTouchOnly = false
    private var explicitFingerOpen = false
    private data class PublicRead(val snapshot: NodeSnapshot, val windowId: Int, val startedAt: Long, val display: List<Int>)
    private var latestRead: PublicRead? = null
    private var readStage = "unread"
    private var readFailureCategories: JSONObject? = null
    private var dispatchAllowed = true

    private fun displayShape(): List<Int> {
        val display = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.view.WindowManager::class.java).defaultDisplay
        val size = android.graphics.Point().also(display::getRealSize)
        return listOf(display.displayId, display.rotation, size.x, size.y)
    }

    private fun safeWindow(automation: UiAutomation, frame: PublicRead): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().context
        if (!context.getSystemService(PowerManager::class.java).isInteractive ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked || displayShape() != frame.display) return false
        val root = automation.rootInActiveWindow ?: return false
        val matches = try { root.packageName?.toString() == Safety.DAMAI && root.windowId == frame.windowId } finally { root.recycle() }
        if (!matches) return false
        val windows = automation.windows
        return try { windows.isNotEmpty() && windows.size <= 16 && windows.any { it.id == frame.windowId && it.isActive } &&
            windows.filter { it.isActive || it.isFocused }.all { it.id == frame.windowId } }
        finally { windows.forEach { it.recycle() } }
    }

    /** Both native and coordinate controls share final window/display/lock and 500ms read-age guards. */
    private fun guardedPublicAction(automation: UiAutomation, baseline: PublicRead, fresh: NodeSnapshot,
                                    actionIndex: Int, touchIndex: Int?, visualAt: Long,
                                    perform: (AccessibilityNodeInfo, NodeBounds) -> Boolean) {
        val current = checkNotNull(latestRead)
        check(current.snapshot === fresh && current.windowId == baseline.windowId && current.display == baseline.display &&
            ExecutionCapturePolicy.sameSnapshot(baseline.snapshot, fresh))
        val handles = mutableListOf<AccessibilityNodeInfo>()
        fun prepare(index: Int, allowNonClickable: Boolean): Boolean {
            val expected = fresh.nodes[index]
            val path = (fresh.ancestors(expected).reversed() + expected).drop(1)
            var node = automation.rootInActiveWindow ?: return false
            try {
                for (part in path) {
                    val parent = fresh.nodes[checkNotNull(part.parentIndex)]
                    val child = node.getChild(parent.children.indexOf(part.index)) ?: return false
                    node.recycle(); node = child
                }
                val rect = Rect()
                if (!node.refresh()) return false
                node.getBoundsInScreen(rect)
                if (node.packageName?.toString() != Safety.DAMAI || node.windowId != current.windowId || !node.isVisibleToUser || !node.isEnabled ||
                    (!node.isClickable && !allowNonClickable) || node.isClickable != expected.clickable ||
                    node.viewIdResourceName.orEmpty() != expected.resourceId || node.text?.toString().orEmpty() != expected.text ||
                    node.contentDescription?.toString().orEmpty() != expected.description || node.isSelected != expected.selected ||
                    (if (node.isCheckable) node.isChecked else null) != expected.checked || node.isScrollable != expected.scrollable ||
                    NodeBounds(rect.left, rect.top, rect.right, rect.bottom) != expected.bounds) return false
                handles += AccessibilityNodeInfo.obtain(node)
                return true
            } finally { node.recycle() }
        }
        try {
            val result = LocalDispatchGuard.dispatch(Any(), {
                prepare(actionIndex, touchIndex == actionIndex) && (touchIndex == null || touchIndex == actionIndex || prepare(touchIndex, true))
            }, { dispatchAllowed }, { safeWindow(automation, current) }, SystemClock::elapsedRealtime,
                visualAt + 2000, current.startedAt) {
                perform(handles.first(), fresh.nodes[touchIndex ?: actionIndex].bounds)
            }
            check(result == LocalDispatchGuard.Result.SENT) { "Public action rejected by final guard: $result" }
        } finally { handles.forEach { it.recycle() } }
    }

    @Test fun openAuthorizedPickerWithExplicitFingerWithoutAdvancing() {
        explicitFingerOpen = true
        openAuthorizedPickerWithoutAdvancing()
    }

    /** Read-only proof for a host control experiment after UiAutomation disconnects. */
    @Test fun inspectAuthorizedDetailTouchTargetWithoutClicking() {
        inspectTouchOnly = true
        openAuthorizedPickerWithoutAdvancing()
    }

    @Test fun verifyAuthorizedTierIsSelectedWithoutAdvancing() {
        requireAuthorizedSelection = true
        inspectAuthorizedPickerVisualCategories()
    }

    /** Navigation-only regression: selected tier/quantity must survive our ordinary app round trip. */
    @Test fun selectedPickerSurvivesOwnAppRoundTrip() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        resumeDamai = false
        requireAuthorizedSelection = true
        inspectAuthorizedPickerVisualCategories()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSELECTED_PICKER_BEFORE_ROUND_TRIP=true\n") })
        instrumentation.context.startActivity(android.content.Intent().setClassName("local.tickets.companion", "local.tickets.companion.MainActivity")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val until = SystemClock.elapsedRealtime() + 3000
        var ownVisible = false
        while (!ownVisible && SystemClock.elapsedRealtime() < until) {
            val root = automation.rootInActiveWindow
            ownVisible = try { root?.packageName?.toString() == "local.tickets.companion" } finally { root?.recycle() }
            if (!ownVisible) SystemClock.sleep(50)
        }
        check(ownVisible)
        instrumentation.context.startActivity(checkNotNull(instrumentation.targetContext.damaiLaunchIntent()))
        inspectAuthorizedPickerVisualCategories()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSELECTED_PICKER_AFTER_ROUND_TRIP=true\n") })
    }

    @Test fun selectAuthorizedTierWithoutAdvancing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        selectTierOnly = true
        inspectAuthorizedPickerVisualCategories()
        selectTierOnly = false
        resumeDamai = false
        requireAuthorizedSelection = true
        inspectAuthorizedPickerVisualCategories()
    }

    private fun read(automation: UiAutomation, requirePicker: Boolean = true): NodeSnapshot {
        readStage = "environment"
        val context = InstrumentationRegistry.getInstrumentation().context
        check(context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Screen must be awake and unlocked" }
        val began = SystemClock.elapsedRealtime()
        val shape = displayShape()
        check(shape[0] == 0 && shape[1] == 0) { "Public touch verification requires the primary portrait display" }
        val root = checkNotNull(automation.rootInActiveWindow)
        val windowId = root.windowId
        val specs = mutableListOf<NodeSpec>()
        fun visit(node: AccessibilityNodeInfo, parent: Int?) {
            try {
                check(specs.size < NodeSnapshot.MAX_NODES && SystemClock.elapsedRealtime() - began < 500)
                check(node.packageName?.toString() == Safety.DAMAI && !node.isPassword)
                val r = Rect().also(node::getBoundsInScreen)
                val index = specs.size
                specs += NodeSpec(parent, Safety.DAMAI, node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty(), node.isVisibleToUser, node.isEnabled, node.isClickable,
                    if (node.isCheckable) node.isChecked else null, node.isSelected, NodeBounds(r.left, r.top, r.right, r.bottom), node.isScrollable)
                for (i in 0 until node.childCount) visit(checkNotNull(node.getChild(i)), index)
            } finally { node.recycle() }
        }
        readStage = "tree"
        visit(root, null)
        val snapshot = NodeSnapshot.create(1, specs, true)
        readStage = "capture_ownership"
        if (!ExecutionCapturePolicy.allowed(snapshot) || (requirePicker && !ExecutionCapturePolicy.picker(snapshot))) {
            val page = DamaiPageParser().parse(snapshot)
            readFailureCategories = JSONObject().put("kind", page.kind.name).put("blockers", JSONArray(page.blockers.map { it.name }))
                .put("nodes", snapshot.nodes.size)
                .put("detailTitleCount", snapshot.nodes.count { it.resourceId == DamaiPageParser.PREFIX + DamaiPageParser.DETAIL_TITLE })
                .put("visibleDetailTitleCount", snapshot.nodes.count { it.resourceId == DamaiPageParser.PREFIX + DamaiPageParser.DETAIL_TITLE && snapshot.visible(it) })
                .put("detailButtonCount", snapshot.nodes.count { it.resourceId == DamaiPageParser.PREFIX + DamaiPageParser.DETAIL_BUTTON })
                .put("unsafeNativeWords", ExecutionCapturePolicy.unsafeWords(snapshot.nodes.filter { it.visible }.flatMap { listOf(it.text, it.description) }, ExecutionCapturePolicy.picker(snapshot)))
            error("Not an approved public ticket page")
        }
        readStage = "event_identity"
        val title = DamaiPageParser().parse(snapshot).eventTitle
        check(title is Observed.Known && DamaiPageParser.eventKey(title.value) == DamaiPageParser.eventKey(event)) { "Wrong authorized event" }
        val metadata = PublicRead(snapshot, windowId, began, shape)
        readStage = "window_guard"
        check(safeWindow(automation, metadata))
        readStage = "read_age"
        check(SystemClock.elapsedRealtime() - began < 500)
        latestRead = metadata
        return snapshot
    }

    /** Operator-only control experiment, not a fallback in the normal app. */
    @Test fun openAuthorizedPickerWithoutAdvancing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.context.startActivity(checkNotNull(instrumentation.targetContext.damaiLaunchIntent()))
            val readyBy = SystemClock.elapsedRealtime() + 3000
            var snapshot: NodeSnapshot? = null
            while (snapshot == null && SystemClock.elapsedRealtime() < readyBy) {
                snapshot = runCatching { read(automation, requirePicker = false) }.getOrNull()
                if (snapshot == null) SystemClock.sleep(50)
            }
            if (snapshot == null) instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nPUBLIC_READ_FAILURE_CATEGORIES=${readFailureCategories ?: JSONObject().put("stage", readStage)}\n")
            })
            val before = checkNotNull(snapshot) { "Public detail not ready: $readStage" }
            val baseline = checkNotNull(latestRead).also { check(it.snapshot === before) }
            check(DamaiPageParser().parse(before).kind == PageKind.DETAIL)
            val button = before.nodes.single { it.resourceId == DamaiPageParser.PREFIX + DamaiPageParser.DETAIL_BUTTON }
            check(ScopedTouchTarget.nodeIndex(before, button.index, ActionKind.OPEN_PICKER) == button.index)
            val bitmap = checkNotNull(automation.takeScreenshot())
            val capturedAt = SystemClock.elapsedRealtime()
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            try {
                val text = Tasks.await(task, 5, TimeUnit.SECONDS)
                check(!ExecutionCapturePolicy.unsafeWords(listOf(text.text), false))
                val labels = text.textBlocks.flatMap { it.lines }.filter { line -> line.boundingBox?.let {
                    button.bounds.contains(NodeBounds(it.left, it.top, it.right, it.bottom))
                } == true }.map { DamaiPageParser.normalized(it.text) }.distinct()
                check(labels.singleOrNull() in setOf("立即预订", "立即购买", "立即购票"))
                val fresh = read(automation, requirePicker = false)
                val current = checkNotNull(latestRead)
                check(ExecutionCapturePolicy.sameSnapshot(before, fresh) && current.windowId == baseline.windowId && current.display == baseline.display)
                check(SystemClock.elapsedRealtime() - capturedAt < 2000)
                val x = (button.bounds.left + button.bounds.right) / 2
                val y = (button.bounds.top + button.bounds.bottom) / 2
                if (inspectTouchOnly) {
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nAUTHORIZED_PUBLIC_TOUCH_TARGET=" +
                        JSONObject().put("x", x).put("y", y).put("action", "OPEN_PICKER")
                            .put("verifiedAtElapsedMs", SystemClock.elapsedRealtime()) + "\n") })
                    return
                }
                guardedPublicAction(automation, baseline, fresh, button.index, button.index, capturedAt) { _, bounds ->
                    if (explicitFingerOpen) automation.tapVerifiedPublicControlAsFinger(bounds)
                    else android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input touchscreen tap $x $y"))
                        .use { check(it.readBytes().isEmpty()); true }
                }
            } finally {
                if (task.isComplete) { bitmap.recycle(); recognizer.close() }
                else task.addOnCompleteListener { bitmap.recycle(); recognizer.close() }
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            var picker = false
            while (!picker && SystemClock.elapsedRealtime() < deadline) {
                picker = runCatching { read(automation); true }.getOrDefault(false)
                if (!picker) SystemClock.sleep(50)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nAUTHORIZED_PICKER_OPENED=$picker\n") })
            check(picker) { "Standard touch did not open the authorized picker" }
        } finally { dispatchAllowed = false; automation.serviceInfo = original }
    }

    @Test fun inspectAuthorizedPickerVisualCategories() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (resumeDamai) instrumentation.context.startActivity(checkNotNull(instrumentation.targetContext.damaiLaunchIntent()))
            val until = SystemClock.elapsedRealtime() + 3000
            var ready: NodeSnapshot? = null
            while (ready == null && SystemClock.elapsedRealtime() < until) {
                ready = runCatching { read(automation) }.getOrNull()
                if (ready == null) SystemClock.sleep(50)
            }
            val before = checkNotNull(ready)
            val baseline = checkNotNull(latestRead).also { check(it.snapshot === before) }
            // The native picker may be ready before the previous FLAG_SECURE window stops
            // obscuring screen capture. Retry only a null capture, with fresh public ownership each time.
            val captureBy = SystemClock.elapsedRealtime() + 1000
            var captured: android.graphics.Bitmap? = null
            while (captured == null && SystemClock.elapsedRealtime() < captureBy) {
                check(ExecutionCapturePolicy.sameSnapshot(before, read(automation)))
                captured = automation.takeScreenshot()
                if (captured == null) SystemClock.sleep(50)
            }
            val bitmap = checkNotNull(captured) { "Public picker capture remained unavailable" }
            val capturedAt = SystemClock.elapsedRealtime()
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            try {
                val text = Tasks.await(task, 5, TimeUnit.SECONDS)
                check(ExecutionCapturePolicy.sameSnapshot(before, read(automation))) { "Picker changed during OCR" }
                check(!ExecutionCapturePolicy.unsafeWords(listOf(text.text), true))
                val cards = checkNotNull(ExecutionCapturePolicy.cards(before))
                val words = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let { r -> VisualWord(line.text, NodeBounds(r.left, r.top, r.right, r.bottom)) }
                }.filter { before.nodes.first().bounds.contains(it.bounds) }
                val evidence = VisualEvidence(1, bitmap.width, bitmap.height, words, cards.associate {
                    it.index to ExecutionVisualSelection.selected(bitmap.width, bitmap.height, it.bounds, bitmap::getPixel)
                }, capturedAt)
                val page = DamaiPageParser().parse(before, evidence)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nVISUAL_PICKER_CATEGORIES=" +
                    JSONObject().put("kind", page.kind.name).put("blockers", JSONArray(page.blockers.map { it.name }))
                        .put("nodeCount", before.nodes.size).put("quantity", (page.quantity as? Observed.Known)?.value)
                        .put("sessions", page.sessions.size).put("tiers", page.tiers.size)
                        .put("selectedTiers", page.tiers.count { it.selection.selected() })
                        .put("cards", JSONArray(cards.map { card -> JSONObject().put("index", card.index)
                            .put("selected", evidence.selectedCards[card.index]).put("bounds", card.bounds.toString())
                            .put("clickable", card.clickable).put("clickableDescendants", before.subtree(card).count { it.clickable }) })) + "\n") })
                assertTrue("Visual picker parsing is incomplete", page.blockers.isEmpty())
                if (requireUnselectedTier) assertEquals("Unknown-tool control unexpectedly selected a tier", 0, page.tiers.count { it.selection.selected() })
                if (requireAuthorizedSelection) {
                    val selected = page.tiers.filter { it.selection.selected() }
                    assertEquals("Exactly one tier must visibly be selected", 1, selected.size)
                    assertEquals("内场880元", DamaiPageParser.tierKey(selected.single().label))
                    assertEquals(88000L, selected.single().unitPriceCents)
                    assertEquals(1, (page.quantity as? Observed.Known)?.value)
                }
                val wall = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val engine = LocalExecution(LocalPlan(event, listOf(TicketTarget("2026-11-07 19:00", "内场880元", 88000)),
                    1, 88000, wall, wall + 60000, 0, false), wall, elapsed)
                val decision = engine.tick(elapsed, before, evidence)
                assertTrue("Authorized picker must have a verified next action", decision is ExecutionDecision.Click)
                val action = (decision as ExecutionDecision.Click).action
                if (InstrumentationRegistry.getArguments().getString("diagnosePublicHitRegion") == "true") {
                    val tier = page.tiers.single { DamaiPageParser.tierKey(it.label) == "内场880元" && it.unitPriceCents == 88000L }
                    val labels = evidence.words.filter { tier.bounds.contains(it.bounds) && DamaiPageParser.tierKey(it.text) == "内场880元" }
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nAUTHORIZED_TIER_HIT_REGION=" + JSONObject()
                        .put("cardIndex", tier.nodeIndex).put("actionIndex", action.nodeIndex).put("cardBounds", tier.bounds.toString())
                        .put("labelBounds", JSONArray(labels.map { it.bounds.toString() })) + "\n") })
                }
                assertEquals(if (page.tiers.any { it.selection.selected() }) ActionKind.NEXT else ActionKind.TIER, action.kind)
                assertTrue(before.nodes[action.nodeIndex].clickable)
                if (selectTierOnly) {
                    val diagnoseEvents = InstrumentationRegistry.getArguments().getString("diagnosePublicClickEvents") == "true"
                    val eventCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
                    val toastCategories = java.util.concurrent.ConcurrentSkipListSet<String>()
                    if (diagnoseEvents) automation.setOnAccessibilityEventListener { event ->
                        if (event.packageName?.toString() == Safety.DAMAI) {
                            val category = when (event.eventType) {
                                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED -> "clicked"
                                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content_changed"
                                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_changed"
                                android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "notification"
                                else -> "other"
                            }
                            eventCounts.computeIfAbsent(category) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.className?.toString() == "android.widget.Toast") {
                                val text = event.text.joinToString("")
                                toastCategories += when {
                                    listOf("验证", "频繁", "异常", "风险", "机器人").any { it in text } -> "risk_or_verification"
                                    listOf("登录", "登陆").any { it in text } -> "login"
                                    listOf("网络", "连接", "超时").any { it in text } -> "network"
                                    listOf("缺货", "售罄", "无票").any { it in text } -> "stock"
                                    else -> "other_unexported"
                                }
                            }
                        }
                    }
                    try {
                        // Choose one backend before dispatch; API acceptance is never selection proof.
                        assertEquals(ActionKind.TIER, action.kind)
                        val fresh = read(automation)
                        check(ExecutionCapturePolicy.sameSnapshot(before, fresh))
                        check(SystemClock.elapsedRealtime() - evidence.capturedAtElapsedMs < 2000)
                        if (explicitFingerTier || unknownToolControl) {
                            val touchIndex = checkNotNull(ScopedTouchTarget.nodeIndex(fresh, action.nodeIndex, ActionKind.TIER))
                            val toolType = if (unknownToolControl) android.view.MotionEvent.TOOL_TYPE_UNKNOWN else android.view.MotionEvent.TOOL_TYPE_FINGER
                            guardedPublicAction(automation, baseline, fresh, action.nodeIndex, touchIndex, evidence.capturedAtElapsedMs) { _, bounds ->
                                automation.tapVerifiedPublicControlAsFinger(bounds, toolType)
                            }
                        } else {
                            guardedPublicAction(automation, baseline, fresh, action.nodeIndex, null, evidence.capturedAtElapsedMs) { node, _ ->
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            val effectDeadline = SystemClock.elapsedRealtime() + 2000
                            while (SystemClock.elapsedRealtime() < effectDeadline) {
                                if (!ExecutionCapturePolicy.sameSnapshot(fresh, read(automation))) break
                                SystemClock.sleep(50)
                            }
                        }
                        if (explicitFingerTier || unknownToolControl) {
                            val effectDeadline = SystemClock.elapsedRealtime() + 2000
                            while (SystemClock.elapsedRealtime() < effectDeadline) {
                                val changed = runCatching { !ExecutionCapturePolicy.sameSnapshot(fresh, read(automation)) }.getOrDefault(false)
                                if (changed) break
                                SystemClock.sleep(50)
                            }
                        }
                    } finally {
                        if (diagnoseEvents) {
                            automation.setOnAccessibilityEventListener(null)
                            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPUBLIC_TIER_EVENT_CATEGORIES=" +
                                JSONObject().put("counts", JSONObject(eventCounts.mapValues { it.value.get() }))
                                    .put("toastCategories", JSONArray(toastCategories.toList())) + "\n") })
                        }
                    }
                }
            } finally {
                if (task.isComplete) { bitmap.recycle(); recognizer.close() }
                else task.addOnCompleteListener { bitmap.recycle(); recognizer.close() }
            }
        } finally { dispatchAllowed = false; automation.serviceInfo = original }
    }
}
