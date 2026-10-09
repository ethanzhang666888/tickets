package local.tickets.companion.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import local.tickets.companion.MainActivity
import local.tickets.companion.Safety
import local.tickets.companion.Session
import local.tickets.companion.pages.DamaiPageParser
import local.tickets.companion.pages.PageKind
import local.tickets.companion.storage.AndroidDirectorySync
import local.tickets.companion.storage.SubmissionFence
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Single local task. Never persisted/resumed; remote commands cannot create or modify a plan. */
object LocalExecutionRuntime {
    private val gate = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "local-ticket-execution").apply { isDaemon = true } }
    private var future: ScheduledFuture<*>? = null
    private var generation = 0L
    private var captureId = 0L
    @Volatile private var active = false
    @Volatile private var phase = "idle"
    @Volatile private var reason = "none"
    @Volatile private var readMs = 0L
    @Volatile private var lastNativeReadFailure = "none"
    @Volatile private var nativeReadRetries = 0
    @Volatile private var visualMs = 0L
    @Volatile private var visualMismatch: JSONObject? = null
    @Volatile private var pageKind = "unread"
    @Volatile private var pageBlockers: List<String> = emptyList()
    @Volatile private var dispatchMs = 0L
    @Volatile private var actions = 0
    @Volatile private var lastAction = "none"
    @Volatile private var lastDispatch = "none"
    @Volatile private var gestureResult = "none"
    @Volatile private var startedAt = 0L
    @Volatile private var firstActionMs: Long? = null
    private var awaitingConfirmation = false
    private var foregroundGuard: LocalForegroundGuard? = null
    private var context: Context? = null
    private var displaySignature: List<Int> = emptyList()

    @Suppress("DEPRECATION")
    private fun displayShape(app: Context): List<Int> {
        val display = app.getSystemService(android.view.WindowManager::class.java).defaultDisplay
        val size = android.graphics.Point().also(display::getRealSize)
        return listOf(display.displayId, display.rotation, size.x, size.y)
    }

    fun isActive() = active
    fun status(): JSONObject = JSONObject().put("active", active).put("phase", phase).put("reason", reason)
        .put("touchBackend", "accessibility_native_or_scoped_touch").put("readMs", readMs)
        .put("lastNativeReadFailure", lastNativeReadFailure).put("nativeReadRetries", nativeReadRetries)
        .put("visualMs", visualMs).put("visualMismatch", visualMismatch ?: JSONObject.NULL).put("pageKind", pageKind).put("pageBlockers", org.json.JSONArray(pageBlockers))
        .put("dispatchMs", dispatchMs).put("actions", actions).put("lastAction", lastAction).put("lastDispatch", lastDispatch).put("gestureResult", gestureResult).put("firstActionMs", firstActionMs ?: JSONObject.NULL)
        .put("automaticSubmissionSupported", false).put("inventoryRefreshSupported", false)
        .put("usbDisconnectPolicy", "continue_locally_authorized_task")

    fun start(appContext: Context, plan: LocalPlan) {
        val now = SystemClock.elapsedRealtime()
        val engine = LocalExecution(plan, System.currentTimeMillis(), now)
        synchronized(gate) {
            check(!active) { "已有手机任务，请先停止" }
            check(Session.accessibility != null && !Session.locked(appContext)) { "请先解锁并开启无障碍" }
            context = appContext.applicationContext
            displaySignature = displayShape(appContext)
            awaitingConfirmation = false
            active = true; phase = "launching"; reason = "none"; actions = 0
            readMs = 0; lastNativeReadFailure = "none"; nativeReadRetries = 0
            visualMs = 0; visualMismatch = null; pageKind = "unread"; pageBlockers = emptyList()
            dispatchMs = 0; lastAction = "none"; lastDispatch = "none"; gestureResult = "none"; firstActionMs = null; startedAt = now
            foregroundGuard = LocalForegroundGuard(Safety.DAMAI, now + 1500)
            val epoch = ++generation
            notifyStatus("任务已在手机授权 · 仅到确认页")
            schedule(epoch, engine, 1500)
        }
    }

    fun stop(stopReason: String = "stopped_by_user") = synchronized(gate) {
        generation++
        future?.cancel(false); future = null
        if (active) { active = false; phase = "stopped"; reason = stopReason; notifyStatus("任务已停止：$stopReason") }
    }

    fun captureStopped(stopReason: String) = synchronized(gate) {
        if (active && LocalCaptureGuard.revokes(stopReason, awaitingConfirmation)) stop("screen_reading_stopped")
    }

    /** Window-state events carry only package metadata, so a departure followed by return is still revoked. */
    @Suppress("DEPRECATION")
    fun foregroundChanged(windowPackage: String? = null) {
        val epoch = synchronized(gate) {
            if (!active) return
            if (foregroundGuard?.departed(windowPackage, SystemClock.elapsedRealtime()) == true) {
                stop("foreground_changed"); return
            }
            generation
        }
        try {
            val service = Session.accessibility ?: run { fail(epoch, "accessibility_lost"); return }
            if (Session.locked(service)) { fail(epoch, "screen_locked"); return }
            val root = service.rootInActiveWindow ?: return // Bounded read loop handles missing transition roots.
            val pkg = try { root.packageName?.toString() } finally { root.recycle() }
            synchronized(gate) {
                if (current(epoch) && foregroundGuard?.departed(pkg, SystemClock.elapsedRealtime()) == true) stop("foreground_changed")
            }
        } catch (_: RuntimeException) { fail(epoch, "foreground_unavailable") }
    }

    private fun current(epoch: Long) = synchronized(gate) { active && epoch == generation }
    private fun schedule(epoch: Long, engine: LocalExecution, delay: Long) {
        synchronized(gate) {
            if (!current(epoch)) return
            future = worker.schedule({ cycle(epoch, engine) }, delay.coerceIn(20, 30_000), TimeUnit.MILLISECONDS)
        }
    }
    private fun fail(epoch: Long, message: String) = synchronized(gate) { if (current(epoch)) stop(message) }
    private fun cycle(epoch: Long, engine: LocalExecution) {
        if (!current(epoch)) return
        try {
            val service = Session.accessibility ?: run { fail(epoch, "accessibility_lost"); return }
            if (Session.locked(service)) { fail(epoch, "screen_locked"); return }
            if (displayShape(service) != displaySignature) { fail(epoch, "display_changed"); return }
            val now = SystemClock.elapsedRealtime()
            if (now >= engine.deadline()) { fail(epoch, "deadline"); return }
            // Execution stays on the phone; visual reads use only separately granted projection.
            var readFailure = NativeReadFailure.PLATFORM_ERROR
            val frame = NativePageAccess.read(service, ++captureId, onFailure = { readFailure = it }) { current(epoch) }
            if (frame == null) {
                synchronized(gate) { if (current(epoch)) lastNativeReadFailure = readFailure.name.lowercase() }
                // No stale tree is reused and no click is repeated. Unsafe classifications stop immediately.
                when (val settling = engine.nativeReadUnavailable(SystemClock.elapsedRealtime(), readFailure)) {
                    is ExecutionDecision.Wait -> {
                        synchronized(gate) { if (current(epoch)) { nativeReadRetries++; phase = settling.reason } }
                        schedule(epoch, engine, settling.untilElapsedMs - SystemClock.elapsedRealtime())
                    }
                    is ExecutionDecision.Stopped -> fail(epoch, settling.reason)
                    else -> fail(epoch, "native_page_unavailable")
                }
                return
            }
            frame.use {
                if (!current(epoch)) return
                synchronized(gate) {
                    if (current(epoch)) {
                        readMs = SystemClock.elapsedRealtime() - frame.startedAt
                        foregroundGuard?.departed(Safety.DAMAI, SystemClock.elapsedRealtime())
                    }
                }
                // Phone storage errors or a prior unresolved phone submission block all execution.
                // Desktop auto-submit remains a separate controller and must not run concurrently.
                val appContext = context ?: return
                val fence = SubmissionFence(File(appContext.noBackupFilesDir, "submissions"), AndroidDirectorySync)
                if (fence.readState().blocked) { fail(epoch, "unresolved_submission"); return }
                val observation = DamaiPageParser().parse(frame.snapshot)
                synchronized(gate) { if (current(epoch)) {
                    pageKind = observation.kind.name
                    pageBlockers = observation.blockers.map { it.name }
                } }
                val kind = observation.kind
                val needsVisual = kind == PageKind.PICKER || kind == PageKind.DETAIL
                val captureOwner = Session.capture
                val visual = if (needsVisual) {
                    if (captureOwner == null) { fail(epoch, "screen_reading_consent_required"); return }
                    val visualStarted = SystemClock.elapsedRealtime()
                    val evidence = ExecutionVisualCapture.capture(frame.snapshot) { current(epoch) && SystemClock.elapsedRealtime() < engine.deadline() }
                        ?: run { fail(epoch, "visual_evidence_unavailable:${ExecutionVisualCapture.lastFailure}"); return }
                    synchronized(gate) { if (current(epoch)) visualMs = SystemClock.elapsedRealtime() - visualStarted }
                    evidence
                } else null // Confirmation/buyer data must never enter OCR or screenshot processing.
                if (visual != null) {
                    val visualObservation = DamaiPageParser().parse(frame.snapshot, visual)
                    // Fixed enum diagnostics only: no page text, images or attendee identities.
                    synchronized(gate) { if (current(epoch)) {
                        pageKind = visualObservation.kind.name
                        pageBlockers = visualObservation.blockers.map { it.name }
                    } }
                }
                when (val decision = engine.tick(SystemClock.elapsedRealtime(), frame.snapshot, visual)) {
                    is ExecutionDecision.Stopped -> fail(epoch, decision.reason)
                    is ExecutionDecision.Finished -> synchronized(gate) {
                        if (current(epoch)) {
                            active = false; phase = "confirmation"; reason = decision.reason
                            generation++; notifyStatus("已核对选票和观演人，操作已停止。请人工复核并提交；尚未生成订单。")
                        }
                    }
                    is ExecutionDecision.Wait -> {
                        synchronized(gate) { if (current(epoch)) { phase = decision.reason; reason = "none" } }
                        schedule(epoch, engine, decision.untilElapsedMs - SystemClock.elapsedRealtime())
                    }
                    is ExecutionDecision.Click -> {
                        // Self-drawn state can change without any native-tree change.
                        val visualCheck = if (visual != null) {
                            ExecutionVisualCapture.capture(frame.snapshot) { current(epoch) && SystemClock.elapsedRealtime() < engine.deadline() }
                                ?: run { fail(epoch, "preclick_visual_unavailable"); return }
                        } else null
                        if (!VisualDispatchEvidence.agrees(visual, visualCheck, SystemClock.elapsedRealtime(), frame.snapshot)) {
                            if (visual != null && visualCheck != null) {
                                fun key(word: local.tickets.companion.pages.VisualWord) = DamaiPageParser.normalized(word.text) to word.bounds
                                val beforeKeys = visual.words.map(::key).toSet()
                                val afterKeys = visualCheck.words.map(::key).toSet()
                                val changed = (visual.words.filter { key(it) !in afterKeys } + visualCheck.words.filter { key(it) !in beforeKeys })
                                visualMismatch = JSONObject().put("ageMs", SystemClock.elapsedRealtime() - visualCheck.capturedAtElapsedMs)
                                    .put("pulseAvailable", ExecutionCapturePolicy.detailPulseBounds(frame.snapshot) != null)
                                    .put("wordsBefore", visual.words.size).put("wordsAfter", visualCheck.words.size)
                                    .put("changed", org.json.JSONArray(changed.take(12).map { word -> JSONObject()
                                        .put("length", word.text.length).put("bounds", word.bounds.toString())
                                        .put("sameTextInBoth", visual.words.any { DamaiPageParser.normalized(it.text) == DamaiPageParser.normalized(word.text) } &&
                                            visualCheck.words.any { DamaiPageParser.normalized(it.text) == DamaiPageParser.normalized(word.text) })
                                        .put("inPulse", ExecutionCapturePolicy.detailPulseBounds(frame.snapshot)?.contains(word.bounds) == true)
                                    }))
                            }
                            fail(epoch, "preclick_visual_changed"); return
                        }
                        // Read after OCR so the native frame still satisfies the 500 ms dispatch bound.
                        val fresh = NativePageAccess.read(service, ++captureId) { current(epoch) }
                            ?: run { fail(epoch, "preclick_page_unavailable"); return }
                        fresh.use {
                            if (!frame.samePage(fresh)) { fail(epoch, "preclick_page_changed"); return }
                            val result = LocalDispatchGuard.dispatch(
                                gate = gate,
                                prepare = {
                                    val touchIndex = decision.action.touchNodeIndex
                                    fresh.prepareClick(decision.action.nodeIndex, allowNonClickable = touchIndex == decision.action.nodeIndex) &&
                                        (touchIndex == null || (visual != null &&
                                            ScopedTouchTarget.nodeIndex(fresh.snapshot, decision.action.nodeIndex, decision.action.kind) == touchIndex &&
                                            fresh.prepareClick(touchIndex, allowNonClickable = true)))
                                },
                                current = { current(epoch) },
                                safe = {
                                    NativePageAccess.foregroundMatches(service, fresh.windowId) &&
                                        displayShape(service) == displaySignature && !Session.locked(service) &&
                                        Session.accessibility === service && (visual == null || Session.capture === captureOwner) &&
                                        VisualDispatchEvidence.agrees(visual, visualCheck, SystemClock.elapsedRealtime(), frame.snapshot)
                                },
                                now = SystemClock::elapsedRealtime,
                                deadline = engine.deadline(),
                                capturedAt = fresh.startedAt,
                                perform = {
                                    val dispatchAt = SystemClock.elapsedRealtime()
                                    if (!engine.actionDispatched(decision.action, dispatchAt)) return@dispatch false
                                    val touchIndex = decision.action.touchNodeIndex
                                    lastAction = decision.action.kind.name
                                    lastDispatch = if (touchIndex == null) "native" else "scoped_touch"
                                    gestureResult = if (touchIndex == null) "not_used" else "pending"
                                    val sent = if (touchIndex == null) fresh.performClick(decision.action.nodeIndex) else {
                                        val bounds = fresh.snapshot.nodes[touchIndex].bounds
                                        val path = android.graphics.Path().apply {
                                            moveTo((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
                                        }
                                        val gesture = android.accessibilityservice.GestureDescription.Builder()
                                            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                                            .build()
                                        service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                                synchronized(gate) { if (current(epoch)) gestureResult = "completed" }
                                            }
                                            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                                synchronized(gate) { if (current(epoch)) gestureResult = "cancelled" }
                                                fail(epoch, "scoped_touch_cancelled")
                                            }
                                        }, Session.main)
                                    }
                                    dispatchMs = SystemClock.elapsedRealtime() - dispatchAt
                                    if (sent) {
                                        if (decision.action.kind == ActionKind.NEXT) awaitingConfirmation = true
                                        if (firstActionMs == null) firstActionMs = dispatchAt - startedAt
                                        actions++; phase = "waiting_action_effect"; reason = "none"
                                    }
                                    sent
                                },
                            )
                            when (result) {
                                LocalDispatchGuard.Result.CANCELLED -> return
                                LocalDispatchGuard.Result.EXPIRED_OR_UNSAFE -> { fail(epoch, "preclick_expired_or_locked"); return }
                                LocalDispatchGuard.Result.PREPARE_REJECTED, LocalDispatchGuard.Result.REJECTED -> {
                                    fail(epoch, "native_click_rejected"); return
                                }
                                LocalDispatchGuard.Result.SENT -> Unit
                            }
                        }
                        schedule(epoch, engine, 40)
                    }
                }
            }
        } catch (_: Exception) { fail(epoch, "local_execution_failed") }
    }

    private fun notifyStatus(message: String) {
        val app = context ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("local_execution", "手机本地任务", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, 61, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getBroadcast(app, 62, Intent(app, ExecutionStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(18766, Notification.Builder(app, "local_execution").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("手机本地任务 · 不提交订单").setContentText(message).setContentIntent(open).setOngoing(active)
                .addAction(Notification.Action.Builder(null, "停止任务", stop).build()).build())
        } catch (_: RuntimeException) { /* Notification permission does not grant execution or suppress local Stop. */ }
    }
}

class ExecutionStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Manifest signature permission restricts the USB handoff to our signed applications.
        // This endpoint can only revoke execution/capture; it cannot create plans or perform input.
        if (intent?.action == "local.tickets.companion.USB_HANDOFF") {
            LocalExecutionRuntime.stop("usb_handoff")
            Session.revokeNativeAudit()
            Session.capture?.stopSafely("usb_handoff")
            resultCode = android.app.Activity.RESULT_OK
        } else LocalExecutionRuntime.stop()
    }
}
