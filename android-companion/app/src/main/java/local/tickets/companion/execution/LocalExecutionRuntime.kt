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
    @Volatile private var dispatchMs = 0L
    @Volatile private var actions = 0
    @Volatile private var startedAt = 0L
    @Volatile private var firstActionMs: Long? = null
    @Volatile private var launchUntil = 0L
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
        .put("touchBackend", "accessibility_native_action").put("readMs", readMs)
        .put("dispatchMs", dispatchMs).put("actions", actions).put("firstActionMs", firstActionMs ?: JSONObject.NULL)
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
            active = true; phase = "launching"; reason = "none"; actions = 0
            readMs = 0; dispatchMs = 0; firstActionMs = null; startedAt = now
            launchUntil = now + 1500
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

    /** Called from accessibility/screen lifecycle events. No raw event text is retained. */
    @Suppress("DEPRECATION")
    fun foregroundChanged() {
        if (!active) return
        val service = Session.accessibility ?: run { stop("accessibility_lost"); return }
        if (Session.locked(service)) { stop("screen_locked"); return }
        if (SystemClock.elapsedRealtime() < launchUntil) return
        val root = service.rootInActiveWindow ?: return // Bounded read loop decides whether a transition recovered.
        val allowed = try { root.packageName?.toString() == Safety.DAMAI } finally { root.recycle() }
        if (!allowed) stop("foreground_changed")
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
            // Native-only executor does not rely on projection, desktop polling or a USB heartbeat.
            val frame = NativePageAccess.read(service, ++captureId) { current(epoch) }
                ?: run { fail(epoch, "native_page_unavailable"); return }
            frame.use {
                if (!current(epoch)) return
                synchronized(gate) { if (current(epoch)) readMs = SystemClock.elapsedRealtime() - frame.startedAt }
                // Phone storage errors or a prior unresolved phone submission block all execution.
                // Desktop auto-submit remains a separate controller and must not run concurrently.
                val appContext = context ?: return
                val fence = SubmissionFence(File(appContext.noBackupFilesDir, "submissions"), AndroidDirectorySync)
                if (fence.readState().blocked) { fail(epoch, "unresolved_submission"); return }
                when (val decision = engine.tick(SystemClock.elapsedRealtime(), frame.snapshot)) {
                    is ExecutionDecision.Stopped -> fail(epoch, decision.reason)
                    is ExecutionDecision.Finished -> synchronized(gate) {
                        if (current(epoch)) {
                            active = false; phase = "confirmation"; reason = decision.reason
                            generation++; notifyStatus("已到确认页，自动操作已停止。请人工核对；尚未提交订单。")
                        }
                    }
                    is ExecutionDecision.Wait -> {
                        synchronized(gate) { if (current(epoch)) { phase = decision.reason; reason = "none" } }
                        schedule(epoch, engine, decision.untilElapsedMs - SystemClock.elapsedRealtime())
                    }
                    is ExecutionDecision.Click -> {
                        // Re-read immediately before dispatch. Coordinates/handles from an old page never authorize a click.
                        val fresh = NativePageAccess.read(service, ++captureId) { current(epoch) }
                            ?: run { fail(epoch, "preclick_page_unavailable"); return }
                        fresh.use {
                            if (!frame.samePage(fresh)) { fail(epoch, "preclick_page_changed"); return }
                            synchronized(gate) {
                                if (!current(epoch)) return
                                val dispatchAt = SystemClock.elapsedRealtime()
                                if (dispatchAt >= engine.deadline() || dispatchAt - fresh.startedAt > 500 || Session.locked(service) ||
                                    displayShape(service) != displaySignature) {
                                    stop("preclick_expired_or_locked"); return
                                }
                                val sent = fresh.click(decision.action.nodeIndex)
                                dispatchMs = SystemClock.elapsedRealtime() - dispatchAt
                                if (!sent) { stop("native_click_rejected"); return }
                                if (firstActionMs == null) firstActionMs = dispatchAt - startedAt
                                actions++; phase = "waiting_action_effect"; reason = "none"
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
    override fun onReceive(context: Context?, intent: Intent?) { LocalExecutionRuntime.stop() }
}
