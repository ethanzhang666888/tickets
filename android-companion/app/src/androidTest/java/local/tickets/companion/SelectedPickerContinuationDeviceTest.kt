package local.tickets.companion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.UiAutomation
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import local.tickets.companion.execution.*
import local.tickets.companion.pages.*
import local.tickets.companion.policy.TicketTarget
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Explicit assisted acceptance, NOT the normal application's full local execution acceptance.
 * Starts on the already manually selected authorized picker without launching/switching apps.
 * Reuses the production decision/review guards. Verified public NEXT is native or an explicit
 * standard finger control; BUYER is native-only. No submit/payment actions.
 * Screenshots are memory-only, public-picker-only, and permanently disabled before the first NEXT.
 * Order/attendee text stays in memory and is never emitted in diagnostics.
 */
@Suppress("DEPRECATION")
class SelectedPickerContinuationDeviceTest {
    private val event = "郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站"
    private lateinit var automation: UiAutomation
    private var captureId = 0L
    private var nextSent = false
    private var active = true
    private var deadline = 0L
    private var displayShape = emptyList<Int>()

    private fun environment(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point().also(display::getRealSize)
        return active && SystemClock.elapsedRealtime() < deadline &&
            context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            listOf(display.displayId, display.rotation, size.x, size.y) == displayShape
    }

    private fun windowMatches(id: Int): Boolean {
        if (!environment()) return false
        val root = automation.rootInActiveWindow ?: return false
        val matches = try { root.packageName?.toString() == Safety.DAMAI && root.windowId == id }
            finally { root.recycle() }
        if (!matches) return false
        val windows = automation.windows
        return try {
            windows.isNotEmpty() && windows.size <= 16 && windows.any { it.id == id && it.isActive } &&
                windows.filter { it.isActive || it.isFocused }.all { it.id == id }
        } finally { windows.forEach { it.recycle() } }
    }

    private class NativeSnapshotUnavailable(val failure: NativeReadFailure) :
        IllegalStateException("Native assisted snapshot unavailable: ${failure.name.lowercase()}")

    private fun read(): NativeFrame {
        if (!environment()) throw NativeSnapshotUnavailable(NativeReadFailure.CANCELLED)
        val began = SystemClock.elapsedRealtime()
        val root = automation.rootInActiveWindow ?: throw NativeSnapshotUnavailable(NativeReadFailure.ROOT_UNAVAILABLE)
        val windowId = root.windowId
        val handles = mutableListOf<AccessibilityNodeInfo>()
        val specs = mutableListOf<NodeSpec>()
        fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            handles += node
            if (depth > NodeSnapshot.MAX_DEPTH || specs.size >= NodeSnapshot.MAX_NODES)
                throw NativeSnapshotUnavailable(NativeReadFailure.SNAPSHOT_LIMIT)
            if (node.isPassword) throw NativeSnapshotUnavailable(NativeReadFailure.PASSWORD_FIELD)
            if (node.packageName?.toString() != Safety.DAMAI) throw NativeSnapshotUnavailable(NativeReadFailure.FOREIGN_PACKAGE)
            if (SystemClock.elapsedRealtime() - began >= 500) throw NativeSnapshotUnavailable(NativeReadFailure.READ_TIMEOUT)
            val rect = Rect().also(node::getBoundsInScreen)
            val index = specs.size
            specs += NodeSpec(parent, Safety.DAMAI, node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(), node.isVisibleToUser, node.isEnabled, node.isClickable,
                if (node.isCheckable) node.isChecked else null, node.isSelected,
                NodeBounds(rect.left, rect.top, rect.right, rect.bottom), node.isScrollable)
            if (node.childCount > NodeSnapshot.MAX_NODES) throw NativeSnapshotUnavailable(NativeReadFailure.SNAPSHOT_LIMIT)
            for (i in 0 until node.childCount) visit(node.getChild(i) ?: throw NativeSnapshotUnavailable(NativeReadFailure.TREE_CHANGED), index, depth + 1)
        }
        try {
            visit(root, null, 1)
            if (!environment()) throw NativeSnapshotUnavailable(NativeReadFailure.CANCELLED)
            if (!windowMatches(windowId)) throw NativeSnapshotUnavailable(NativeReadFailure.WINDOW_UNAVAILABLE)
            if (SystemClock.elapsedRealtime() - began >= 500) throw NativeSnapshotUnavailable(NativeReadFailure.READ_TIMEOUT)
            val snapshot = try { NodeSnapshot.create(++captureId, specs, true) }
                catch (_: IllegalArgumentException) { throw NativeSnapshotUnavailable(NativeReadFailure.INVALID_SNAPSHOT) }
            return NativeFrame(snapshot, specs, windowId, began, handles)
        } catch (error: Throwable) {
            handles.forEach { it.recycle() }
            // Never propagate node text in a framework assertion/constructor exception.
            if (error is NativeSnapshotUnavailable) throw error
            throw NativeSnapshotUnavailable(NativeReadFailure.PLATFORM_ERROR)
        }
    }

    private fun pickerVisual(frame: NativeFrame): VisualEvidence {
        check(!nextSent && environment() && windowMatches(frame.windowId))
        check(ExecutionCapturePolicy.allowed(frame.snapshot) && ExecutionCapturePolicy.picker(frame.snapshot))
        check((DamaiPageParser().parse(frame.snapshot).eventTitle as? Observed.Known)?.value?.let(DamaiPageParser::eventKey) == DamaiPageParser.eventKey(event))
        val bitmap = checkNotNull(automation.takeScreenshot())
        val capturedAt = SystemClock.elapsedRealtime()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
        try {
            check(bitmap.width == displayShape[2] && bitmap.height == displayShape[3])
            val text = Tasks.await(task, 5, TimeUnit.SECONDS)
            check(!ExecutionCapturePolicy.unsafeWords(listOf(text.text), true))
            read().use { check(frame.samePage(it)) { "Picker changed during visual verification" } }
            val cards = checkNotNull(ExecutionCapturePolicy.cards(frame.snapshot))
            val words = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { rect ->
                VisualWord(line.text, NodeBounds(rect.left, rect.top, rect.right, rect.bottom))
            } }.filter { frame.snapshot.nodes.first().bounds.contains(it.bounds) }
            return VisualEvidence(frame.snapshot.captureId, bitmap.width, bitmap.height, words, cards.associate {
                it.index to ExecutionVisualSelection.selected(bitmap.width, bitmap.height, it.bounds, bitmap::getPixel)
            }, capturedAt)
        } finally {
            if (task.isComplete) { bitmap.recycle(); recognizer.close() }
            else task.addOnCompleteListener { bitmap.recycle(); recognizer.close() }
        }
    }

    /** Read-only audit after the user manually entered the order page; no picker/submit actions. */
    @Test fun inspectCurrentAuthorizedOrderWithoutActions() = reviewCurrentOrder(selectDefaultBuyer = false)

    /** One native checkbox action only, and only when no attendee is currently selected. */
    @Test fun selectDefaultFirstBuyerOnCurrentAuthorizedOrderWithoutSubmission() = reviewCurrentOrder(selectDefaultBuyer = true)

    @Test fun returnVerifiedOrderToPickerWithoutSubmission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        reviewCurrentOrder(selectDefaultBuyer = false, returnToPicker = true)
    }

    private fun reviewCurrentOrder(selectDefaultBuyer: Boolean, returnToPicker: Boolean = false) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitNativeOrderReview") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        val evidence = JSONObject().put("completed", false).put("nativeActions", 0).put("screenshots", 0)
            .put("submissionPerformed", false).put("paymentPerformed", false)
            .put("normalAppFullAutomationAccepted", false)
        try {
            val display = instrumentation.targetContext.getSystemService(WindowManager::class.java).defaultDisplay
            val size = Point().also(display::getRealSize)
            check(display.displayId == 0 && display.rotation == 0)
            displayShape = listOf(display.displayId, display.rotation, size.x, size.y)
            deadline = SystemClock.elapsedRealtime() + 10_000
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val wall = System.currentTimeMillis()
            val target = TicketTarget("2026-11-07 19:00", "内场880元", 88000)
            val plan = LocalPlan(event, listOf(target), 1, 88000, wall, wall + 10_000, 0, false)
            read().use { before ->
                val page = DamaiPageParser().parse(before.snapshot)
                evidence.put("pageKind", page.kind.name).put("blockers", org.json.JSONArray(page.blockers.map { it.name }))
                if (page.kind == PageKind.CONFIRMATION) evidence.put("backControls", org.json.JSONArray(before.snapshot.nodes.filter {
                    before.snapshot.visible(it) && it.clickable && it.resourceId.substringAfterLast('/').contains("back", ignoreCase = true)
                }.map { JSONObject().put("index", it.index).put("id", it.resourceId).put("enabled", before.snapshot.enabled(it)) }))
                val fields = page.confirmation
                evidence.put("eventMatches", fields?.eventTitle?.known()?.let(DamaiPageParser::eventKey) == DamaiPageParser.eventKey(event))
                    .put("sessionMatches", fields?.session?.known()?.let(DamaiPageParser::sessionKey) == DamaiPageParser.sessionKey(target.session))
                    .put("unitPriceMatches", fields?.unitPriceCents?.known() == target.unitPriceCents)
                    .put("quantity", fields?.quantity?.known()).put("totalCents", fields?.totalCents?.known())
                    .put("tierIdentityOnOrderPage", "unsupported_price_only")
                when (val review = reviewConfirmation(before.snapshot, page, plan, target)) {
                    is ConfirmationReview.Rejected -> evidence.put("reason", review.reason)
                    is ConfirmationReview.Verified -> {
                        read().use { fresh ->
                            check(before.samePage(fresh) && windowMatches(fresh.windowId)) { "Order changed during native review" }
                        }
                        evidence.put("buyerCount", review.buyers.size).put("selectedBuyerCount", review.buyers.count { it.selected })
                            .put("defaultFirstSelected", review.buyers.firstOrNull()?.selected == true)
                            .put("buyerChangeRequired", review.change != null)
                            .put("selectionObservationPhase", if (selectDefaultBuyer && review.change != null) "before_action" else "read_only")
                            .put("completed", review.change == null)
                            .put("reason", if (review.change == null) "assisted_native_order_review_verified" else "default_buyer_selection_required")
                        if (returnToPicker) {
                            check(!selectDefaultBuyer && review.change == null)
                            val back = before.snapshot.nodes.single { it.resourceId == "cn.damai:id/tv_goback" && before.snapshot.visible(it) && it.clickable }
                            read().use { fresh ->
                                check(before.samePage(fresh))
                                val sent = LocalDispatchGuard.dispatch(Any(), { fresh.prepareClick(back.index) }, { active },
                                    { windowMatches(fresh.windowId) }, SystemClock::elapsedRealtime, deadline, fresh.startedAt) {
                                    fresh.performClick(back.index)
                                }
                                check(sent == LocalDispatchGuard.Result.SENT) { "Verified order back control rejected" }
                            }
                            evidence.put("completed", false).put("reviewCompletedBeforeNavigation", true)
                                .put("nativeActions", 1).put("reason", "waiting_verified_back_effect")
                            val until = minOf(deadline, SystemClock.elapsedRealtime() + 3000)
                            var returned = false
                            while (!returned && environment() && SystemClock.elapsedRealtime() < until) {
                                val after = runCatching { read() }.getOrNull()
                                after?.use {
                                    val afterPage = DamaiPageParser().parse(it.snapshot)
                                    evidence.put("pageKindAfterNavigation", afterPage.kind.name)
                                    returned = afterPage.kind == PageKind.PICKER && ExecutionCapturePolicy.allowed(it.snapshot) &&
                                        afterPage.eventTitle.known()?.let(DamaiPageParser::eventKey) == DamaiPageParser.eventKey(event)
                                    if (!returned && afterPage.kind != PageKind.CONFIRMATION) error("Unexpected page after native back")
                                }
                                if (!returned) SystemClock.sleep(80)
                            }
                            evidence.put("navigationConfirmed", returned).put("reason", if (returned) "verified_picker_restored" else "back_effect_unconfirmed")
                            check(returned) { "Native back did not restore the authorized picker" }
                        }
                        if (selectDefaultBuyer && review.change != null) {
                            val change = checkNotNull(review.change)
                            check(change.second && review.buyers.none { it.selected } && change.first === review.buyers.first())
                            val identities = review.buyers.map { DamaiPageParser.normalized(it.label) }
                            var buyerDispatchedAt = -1L
                            read().use { fresh ->
                                check(before.samePage(fresh)) { "Order changed before default-buyer action" }
                                val sent = LocalDispatchGuard.dispatch(Any(), { fresh.prepareClick(change.first.index) }, { active },
                                    { windowMatches(fresh.windowId) }, SystemClock::elapsedRealtime, deadline, fresh.startedAt) {
                                    evidence.put("buyerClickAttempted", true)
                                    buyerDispatchedAt = SystemClock.elapsedRealtime()
                                    fresh.performClick(change.first.index)
                                }
                                evidence.put("nativeClickAccepted", sent == LocalDispatchGuard.Result.SENT)
                                check(sent == LocalDispatchGuard.Result.SENT) { "Default-buyer checkbox click rejected" }
                                evidence.put("nativeActions", 1).put("reason", "buyer_effect_pending")
                                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nASSISTED_BUYER_CLICK_ACCEPTED=true\n") })
                            }
                            val until = minOf(deadline, SystemClock.elapsedRealtime() + 2500)
                            var verified = false
                            while (!verified && SystemClock.elapsedRealtime() < until) {
                                val effectFrame = try { read() } catch (missing: NativeSnapshotUnavailable) {
                                    val now = SystemClock.elapsedRealtime()
                                    if (!missing.failure.transientAfterAction || !environment() || buyerDispatchedAt < 0 || now - buyerDispatchedAt !in 0 until 500L) throw missing
                                    SystemClock.sleep(minOf(50L, buyerDispatchedAt + 500 - now))
                                    continue
                                }
                                effectFrame.use { after ->
                                    check(after.windowId == before.windowId)
                                    val latest = reviewConfirmation(after.snapshot, DamaiPageParser().parse(after.snapshot), plan, target)
                                    check(latest is ConfirmationReview.Verified) { "Order review changed after buyer action" }
                                    check(latest.buyers.map { DamaiPageParser.normalized(it.label) } == identities) { "Saved attendee list changed" }
                                    check(latest.buyers.drop(1).none { it.selected }) { "Unexpected attendee became selected" }
                                    verified = latest.change == null && latest.buyers.first().selected && latest.buyers.count { it.selected } == 1
                                    evidence.put("selectedBuyerCount", latest.buyers.count { it.selected })
                                        .put("defaultFirstSelected", latest.buyers.first().selected)
                                        .put("buyerChangeRequired", latest.change != null).put("selectionObservationPhase", "after_action")
                                }
                                if (!verified) SystemClock.sleep(80)
                            }
                            evidence.put("completed", verified).put("reason", if (verified) "assisted_native_order_review_verified" else "buyer_action_effect_unconfirmed")
                        }
                    }
                }
            }
            if (selectDefaultBuyer) check(evidence.optBoolean("completed")) { "Default attendee selection was not verified" }
            if (returnToPicker) check(evidence.optBoolean("navigationConfirmed")) { "Authorized picker return was not verified" }
        } finally {
            active = false
            automation.serviceInfo = original
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nASSISTED_NATIVE_ORDER_REVIEW=$evidence\n") })
        }
    }

    @Test fun continueAlreadySelectedAuthorizedPickerWithoutSubmission() = continueSelectedPicker(explicitFingerNext = false)

    @Test fun continueSelectedPickerWithExplicitFingerWithoutSubmission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        continueSelectedPicker(explicitFingerNext = true)
    }

    private fun continueSelectedPicker(explicitFingerNext: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitSelectedContinuation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        var completed = false
        var actions = 0
        var reason = "not_started"
        fun stage(value: String) {
            reason = value
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nASSISTED_STAGE=$value\n") })
        }
        try {
            val display = instrumentation.targetContext.getSystemService(WindowManager::class.java).defaultDisplay
            val size = Point().also(display::getRealSize)
            check(display.displayId == 0 && display.rotation == 0)
            displayShape = listOf(display.displayId, display.rotation, size.x, size.y)
            deadline = SystemClock.elapsedRealtime() + 35_000
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val wall = System.currentTimeMillis()
            val engine = LocalExecution(LocalPlan(event, listOf(TicketTarget("2026-11-07 19:00", "内场880元", 88000)),
                1, 88000, wall, wall + 35_000, 0, false), wall, SystemClock.elapsedRealtime())
            deadline = minOf(deadline, engine.deadline())
            val gate = Any()
            stage("reading_selected_picker")
            read().use { before ->
                stage("reading_first_public_visual")
                val visual = pickerVisual(before)
                stage("first_public_visual_ready")
                val page = DamaiPageParser().parse(before.snapshot, visual)
                check(page.kind == PageKind.PICKER && page.blockers.isEmpty())
                check(page.tiers.filter { it.selection.selected() }.singleOrNull()?.let {
                    DamaiPageParser.tierKey(it.label) == "内场880元" && it.unitPriceCents == 88000L
                } == true && (page.quantity as? Observed.Known)?.value == 1)
                val decision = engine.tick(SystemClock.elapsedRealtime(), before.snapshot, visual)
                check(decision is ExecutionDecision.Click && decision.action.kind == ActionKind.NEXT && decision.action.touchNodeIndex == null)
                stage("reading_second_public_visual")
                val second = pickerVisual(before)
                stage("second_public_visual_ready")
                if (!VisualDispatchEvidence.agrees(visual, second, SystemClock.elapsedRealtime(), before.snapshot)) {
                    reason = "preclick_visual_changed"
                    fun key(word: VisualWord) = DamaiPageParser.normalized(word.text) to word.bounds
                    val beforeKeys = visual.words.map(::key).toSet()
                    val afterKeys = second.words.map(::key).toSet()
                    val changed = visual.words.filter { key(it) !in afterKeys } + second.words.filter { key(it) !in beforeKeys }
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nASSISTED_VISUAL_DIFFERENCE=" +
                        JSONObject().put("selectionSame", visual.selectedCards == second.selectedCards)
                            .put("ageMs", SystemClock.elapsedRealtime() - second.capturedAtElapsedMs)
                            .put("changed", org.json.JSONArray(changed.take(12).map { word ->
                                JSONObject().put("length", word.text.length).put("bounds", word.bounds.toString())
                            })) + "\n") })
                    error("Preclick visual evidence changed; no action dispatched")
                }
                stage("visual_guard_passed")
                read().use { fresh ->
                    check(before.samePage(fresh))
                    val fingerBounds = if (explicitFingerNext) checkNotNull(ExecutionCapturePolicy.pickerNextBounds(fresh.snapshot)) else null
                    val sent = LocalDispatchGuard.dispatch(gate, { fresh.prepareClick(decision.action.nodeIndex) }, { active },
                        { windowMatches(fresh.windowId) && VisualDispatchEvidence.agrees(visual, second, SystemClock.elapsedRealtime(), before.snapshot) },
                        SystemClock::elapsedRealtime, deadline, fresh.startedAt) {
                        check(engine.actionDispatched(decision.action, SystemClock.elapsedRealtime()))
                        nextSent = true // Disable every screenshot before entering Android's native click call.
                        if (fingerBounds != null) automation.tapVerifiedPublicControlAsFinger(fingerBounds)
                        else fresh.performClick(decision.action.nodeIndex)
                    }
                    check(sent == LocalDispatchGuard.Result.SENT) { "Verified NEXT rejected" }
                    actions++
                    stage("waiting_native_confirmation")
                }
            }
            val nextDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 10_000)
            var entered = false
            while (environment()) {
                val effectFrame = try { read() } catch (missing: NativeSnapshotUnavailable) {
                    val settling = engine.nativeReadUnavailable(SystemClock.elapsedRealtime(), missing.failure)
                    if (settling !is ExecutionDecision.Wait) {
                        reason = (settling as? ExecutionDecision.Stopped)?.reason ?: "native_effect_read_stopped"
                        error("Native effect observation stopped: $reason")
                    }
                    SystemClock.sleep((settling.untilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    continue
                }
                effectFrame.use { frame ->
                    val observation = DamaiPageParser().parse(frame.snapshot)
                    val kind = observation.kind
                    if (kind !in setOf(PageKind.PICKER, PageKind.CONFIRMATION, PageKind.LOADING, PageKind.QUEUE)) {
                        reason = "unexpected_after_next:${kind.name.lowercase()}"
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPOST_NEXT_NATIVE_CATEGORIES=" + JSONObject()
                            .put("kind", kind.name).put("blockers", org.json.JSONArray(observation.blockers.map { it.name }))
                            .put("nodes", frame.snapshot.nodes.size)
                            .put("visibleTextNodes", frame.snapshot.nodes.count { it.visible && (it.text.isNotBlank() || it.description.isNotBlank()) })
                            .put("resourceIds", org.json.JSONArray(frame.snapshot.nodes.map { it.resourceId }.filter { it.isNotEmpty() }.distinct().take(30))) + "\n") })
                    }
                    if (!entered && kind == PageKind.PICKER) {
                        check(SystemClock.elapsedRealtime() < nextDeadline) { "NEXT effect unconfirmed" }
                        return@use
                    }
                    if (!entered && kind in setOf(PageKind.LOADING, PageKind.QUEUE)) {
                        val waiting = engine.tick(SystemClock.elapsedRealtime(), frame.snapshot)
                        check(waiting is ExecutionDecision.Wait) { "Loading/queue transition was not accepted" }
                        return@use
                    }
                    check(kind == PageKind.CONFIRMATION) { "Unexpected page after NEXT; no further action" }
                    entered = true
                    when (val decision = engine.tick(SystemClock.elapsedRealtime(), frame.snapshot)) {
                        is ExecutionDecision.Finished -> {
                            reason = decision.reason
                            check(reason == "confirmation_verified_manual_submit")
                            completed = true
                            return
                        }
                        is ExecutionDecision.Stopped -> { reason = decision.reason; error("Native confirmation review stopped: $reason") }
                        is ExecutionDecision.Wait -> Unit
                        is ExecutionDecision.Click -> {
                            check(decision.action.kind == ActionKind.BUYER && decision.action.touchNodeIndex == null && actions < 10)
                            read().use { fresh ->
                                check(frame.samePage(fresh)) { "Native review changed before buyer action" }
                                val sent = LocalDispatchGuard.dispatch(gate, { fresh.prepareClick(decision.action.nodeIndex) }, { active },
                                    { windowMatches(fresh.windowId) }, SystemClock::elapsedRealtime, deadline, fresh.startedAt) {
                                    check(engine.actionDispatched(decision.action, SystemClock.elapsedRealtime()))
                                    fresh.performClick(decision.action.nodeIndex)
                                }
                                check(sent == LocalDispatchGuard.Result.SENT) { "Verified native buyer action rejected" }
                                actions++
                            }
                        }
                    }
                }
                SystemClock.sleep(80)
            }
            error("Assisted continuation deadline or environment stopped")
        } finally {
            active = false
            automation.serviceInfo = original
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nASSISTED_CONTINUATION_EVIDENCE=" +
                JSONObject().put("completed", completed).put("reason", reason).put("actions", actions)
                    .put("nativeActions", if (explicitFingerNext && nextSent) (actions - 1).coerceAtLeast(0) else actions)
                    .put("nextAttempted", nextSent).put("nextDispatch", if (explicitFingerNext) "standard_finger" else "native")
                    .put("screenshotsAfterNext", 0)
                    .put("submissionPerformed", false).put("paymentPerformed", false)
                    .put("normalAppFullAutomationAccepted", false) + "\n") })
        }
    }
}
