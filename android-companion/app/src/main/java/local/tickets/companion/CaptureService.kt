package local.tickets.companion

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import local.tickets.companion.execution.ExecutionCapturePolicy
import local.tickets.companion.execution.ExecutionVisualSelection
import local.tickets.companion.execution.NativePageAccess
import local.tickets.companion.pages.NodeBounds
import local.tickets.companion.pages.NodeSnapshot
import local.tickets.companion.pages.VisualEvidence
import local.tickets.companion.pages.VisualWord

/** Frames are transient memory only. No files, bitmap export, raw OCR export, or telemetry. */
class CaptureService : Service() {
    // Each service owns its evidence; late callbacks from an old service cannot change a new request.
    private val diagnostics = CaptureDiagnostics()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameEpoch = CaptureEpoch()
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var recognizerUsed = false
    private var pending: SafeSummary? = null
    private class ExecutionRequest(val snapshot: NodeSnapshot, val windowId: Int,
        val generation: Long, val callback: (VisualEvidence?, String?) -> Unit)
    private var execution: ExecutionRequest? = null
    private var executionGeneration = 0L
    private val executionTimeout = Runnable { if (execution != null) stopSafely("capture_or_ocr_timeout") }
    private var requestedAt = 0L
    private var expectedPageIsSynthetic = false
    private var processing = false
    private var stopped = false
    private var width = 0
    private var height = 0
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { Session.revokeNativeAudit(); stopSafely("screen_locked") }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSafely("capture_permission_lost") }
    }
    private val foregroundGuard = ForegroundGuard()
    private val guard = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!checkForeground()) {
                if (!stopped) Session.main.postDelayed(this, 100)
                return
            }
            val metrics = screenMetrics()
            if (metrics.widthPixels != width || metrics.heightPixels != height) { stopSafely("display_changed_regrant_required"); return }
            if ((pending != null || execution != null || processing) && SystemClock.elapsedRealtime() - requestedAt > 12_000) {
                stopSafely("capture_or_ocr_timeout"); return
            }
            Session.main.postDelayed(this, 400)
        }
    }

    /** WAIT keeps only the consent resource alive; pending/processing frames never get this grace. */
    fun checkForeground(): Boolean {
        if (stopped) return false
        val error = Session.accessibility?.guardError() ?: if (Session.accessibility == null) "accessibility_lost" else null
        return when (foregroundGuard.evaluate(error, pending == null && execution == null && !processing, SystemClock.elapsedRealtime())) {
            ForegroundGuard.Decision.ALLOW -> {
                if (Session.captureState == "waiting_foreground") Session.captureState = "ready"
                true
            }
            ForegroundGuard.Decision.WAIT -> { Session.captureState = "waiting_foreground"; false }
            ForegroundGuard.Decision.STOP -> { stopSafely(error ?: "foreground_unavailable"); false }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { Session.revokePairingCode(); Session.revokeNativeAudit(); stopSafely("stopped_by_user"); return START_NOT_STICKY }
        if (projection != null || stopped) return START_NOT_STICKY
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel("capture", "用户授权屏幕验证", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, CaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "capture")
            .setContentTitle("屏幕读取已授权 · 仅手机本地处理")
            .setContentText("不下单不支付。锁屏、离开允许页面或权限失效即停止。")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止读取", stop).build()).build()
        startForeground(18765, notification)
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("consent", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("consent")
        if (intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) != Activity.RESULT_OK || data == null || Session.accessibility == null || Session.locked(this)) {
            stopSafely("invalid_capture_consent"); return START_NOT_STICKY
        }
        try {
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
            projection?.registerCallback(projectionCallback, Session.main)
            val metrics = screenMetrics()
            width = metrics.widthPixels
            height = metrics.heightPixels
            require(width in 1..4096 && height in 1..4096)
            reader = createReader(0) // Drained but never accepted before an explicit request.
            display = projection?.createVirtualDisplay("feasibility-memory-only", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, Session.main)
            check(display != null)
            Session.lastIncompleteScan = SafeSummary()
            Session.capture = this
            Session.captureState = "ready"
            Session.lastError = "none"
            Session.main.postDelayed(guard, 600)
        } catch (_: Exception) { stopSafely("capture_start_failed_or_secure"); }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics = DisplayMetrics().also {
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
    }

    private fun createReader(epoch: Long): ImageReader {
        val fresh = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        try {
            fresh.setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: Exception) {
                    if (!stopped && source === reader && frameEpoch.accepts(epoch)) diagnostics.captureFailed()
                    null
                }
                image?.use { if (source === reader) consume(it, epoch) }
            }, Session.main)
            return fresh
        } catch (error: Exception) {
            fresh.close()
            throw error
        }
    }

    /** Main-thread only. Results remain phone-local; feasibility/status APIs never receive them. */
    fun requestExecutionCapture(snapshot: NodeSnapshot, callback: (VisualEvidence?, String?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (stopped || projection == null || Session.capture !== this) { callback(null, "capture_not_authorized"); return }
        if (pending != null || execution != null || processing) { callback(null, "capture_busy"); return }
        if (!ExecutionCapturePolicy.allowed(snapshot)) { callback(null, "capture_page_not_allowed"); return }
        val service = Session.accessibility
        if (service == null || !checkForeground()) { callback(null, "foreground_unavailable"); return }
        val baseline = NativePageAccess.read(service, snapshot.captureId) { !stopped && Session.capture === this }
        val windowId = baseline?.use {
            if (ExecutionCapturePolicy.sameSnapshot(snapshot, it.snapshot)) it.windowId else null
        }
        if (windowId == null) { callback(null, "native_page_changed"); return }
        requestedAt = SystemClock.elapsedRealtime()
        execution = ExecutionRequest(snapshot, windowId, ++executionGeneration, callback)
        diagnostics.reset()
        Session.captureDiagnostics = diagnostics
        expectedPageIsSynthetic = false
        Session.captureState = "awaiting_frame"
        Session.main.postDelayed(executionTimeout, 8_000)
        try { freshSurface() } catch (_: Exception) { diagnostics.captureFailed(); stopSafely("capture_failed_or_secure") }
    }

    /** Cancellation never recycles an ML Kit input until its task completes. */
    internal fun cancelExecutionCapture(callback: (VisualEvidence?, String?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val request = execution ?: return
        if (request.callback !== callback) return
        execution = null
        executionGeneration++
        frameEpoch.invalidate()
        Session.main.removeCallbacks(executionTimeout)
        try { display?.surface = null } catch (_: Exception) { stopSafely("capture_failed_or_secure") }
        if (!stopped) Session.captureState = if (processing) "ocr_running" else "ready"
    }

    private fun freshSurface() {
        // A fresh reader/surface, not timestamp comparisons across incompatible Android clocks.
        display?.surface = null
        frameEpoch.invalidate()
        val previous = reader
        reader = null
        previous?.setOnImageAvailableListener(null, null)
        previous?.close()
        reader = createReader(frameEpoch.begin())
        display?.surface = reader?.surface
    }

    fun requestCapture(summary: SafeSummary) {
        if (stopped || projection == null) return
        if (pending != null || execution != null || processing) { Session.lastError = "capture_busy"; return }
        val error = Session.accessibility?.guardError() ?: if (Session.accessibility == null) "accessibility_lost" else null
        if (error != null || (summary.pageKind != "synthetic" && !isDamaiForeground())) { stopSafely(error ?: "foreground_not_allowed"); return }
        requestedAt = SystemClock.elapsedRealtime()
        diagnostics.reset()
        Session.captureDiagnostics = diagnostics
        expectedPageIsSynthetic = summary.pageKind == "synthetic"
        pending = summary
        Session.captureState = "awaiting_frame"
        Session.summary = summary.copy(captureState = "awaiting_frame")
        try {
            freshSurface()
        } catch (_: Exception) { diagnostics.captureFailed(); stopSafely("capture_failed_or_secure") }
    }

    @Suppress("DEPRECATION")
    private fun isDamaiForeground(): Boolean {
        val node = Session.accessibility?.rootInActiveWindow ?: return false
        return try { node.packageName?.toString() == Safety.DAMAI } finally { node.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun captureForegroundMatches(): Boolean {
        val node = Session.accessibility?.rootInActiveWindow ?: return false
        return try { Safety.captureMatches(node.packageName?.toString(), expectedPageIsSynthetic, Session.syntheticVisible) }
        finally { node.recycle() }
    }

    private fun consume(image: Image, epoch: Long) {
        if (!frameEpoch.accepts(epoch)) return
        execution?.let { consumeExecution(image, it); return }
        val native = pending ?: return
        if (stopped || processing) return
        diagnostics.frameReceived()
        val error = Session.accessibility?.guardError() ?: if (Session.accessibility == null) "accessibility_lost" else null
        if (error != null || !captureForegroundMatches()) { stopSafely(error ?: "foreground_changed"); return }
        // These clocks are observably incompatible on the target Huawei. Retain
        // the numeric difference for diagnosis only; freshness comes from the new reader.
        diagnostics.compareFrameClock(image.timestamp / 1_000_000, requestedAt)
        diagnostics.frameAccepted()
        frameEpoch.invalidate() // At most one frame may enter OCR for this request.
        pending = null
        processing = true
        var bitmap: Bitmap? = null
        var ocrInvoked = false
        try {
            display?.surface = null // Do not continuously produce frames during OCR/idle.
            bitmap = imageBitmap(image)
            // Protected windows commonly return a black frame. Do not claim this proves FLAG_SECURE.
            if (likelyBlank(bitmap)) {
                bitmap.recycle()
                processing = false
                diagnostics.captureFailed()
                stopSafely("secure_or_blank_frame")
                return
            }
            val screenshotMs = SystemClock.elapsedRealtime() - requestedAt
            val ocrStarted = SystemClock.elapsedRealtime()
            Session.captureState = "ocr_running"
            val ownedBitmap = bitmap
            recognizerUsed = true
            ocrInvoked = true
            diagnostics.ocrStarted()
            recognizer.process(InputImage.fromBitmap(ownedBitmap, 0))
                .addOnSuccessListener { text ->
                    if (stopped) return@addOnSuccessListener
                    diagnostics.ocrCompleted()
                    val currentError = Session.accessibility?.guardError() ?: if (Session.accessibility == null) "accessibility_lost" else null
                    if (currentError != null || !captureForegroundMatches()) { stopSafely(currentError ?: "foreground_changed"); return@addOnSuccessListener }
                    // Only fixed page categories and counts survive this callback.
                    val pickerStillRecognized = native.pickerLayoutRecognized && Session.accessibility?.pickerLayoutRecognized() == true
                    val kind = Safety.pageKind(text.textBlocks.map { it.text }, pickerStillRecognized)
                    if (kind == "payment") { stopSafely("payment_foreground"); return@addOnSuccessListener }
                    Session.summary = native.copy(
                        pageKind = if (native.pageKind == "unknown") kind else native.pageKind,
                        screenshotMs = screenshotMs, ocrMs = SystemClock.elapsedRealtime() - ocrStarted,
                        ocrBlockCount = text.textBlocks.size,
                        ocrCandidateCount = text.textBlocks.count { Safety.isCandidate(it.text) },
                        screenshotWidth = ownedBitmap.width, screenshotHeight = ownedBitmap.height,
                        captureState = "complete")
                    Session.captureState = "ready"
                    Session.lastError = "none"
                }
                .addOnFailureListener {
                    if (!stopped) { diagnostics.ocrFailed(); stopSafely("ocr_unavailable") }
                }
                .addOnCompleteListener {
                    ownedBitmap.recycle()
                    processing = false
                    if (stopped && recognizerUsed) recognizer.close()
                }
        } catch (_: Exception) {
            if (ocrInvoked) diagnostics.ocrFailed() else diagnostics.captureFailed()
            bitmap?.recycle()
            processing = false
            stopSafely("capture_failed_or_secure")
        }
    }

    private fun executionCurrent(request: ExecutionRequest): Boolean = !stopped && execution === request &&
        executionGeneration == request.generation && SystemClock.elapsedRealtime() - requestedAt < 8_000 && Session.capture === this

    private fun executionPageMatches(request: ExecutionRequest): Boolean {
        if (!executionCurrent(request)) return false
        val service = Session.accessibility ?: return false
        if (service.guardError() != null || !NativePageAccess.foregroundMatches(service, request.windowId)) return false
        return NativePageAccess.read(service, request.snapshot.captureId) { executionCurrent(request) }?.use {
            it.windowId == request.windowId && ExecutionCapturePolicy.sameSnapshot(request.snapshot, it.snapshot)
        } == true
    }

    private fun finishExecution(request: ExecutionRequest, evidence: VisualEvidence) {
        if (!executionCurrent(request)) return
        execution = null
        executionGeneration++
        Session.main.removeCallbacks(executionTimeout)
        Session.captureState = "ready"
        Session.lastError = "none"
        // A consumer failure must not escape into a framework callback or skip bitmap cleanup.
        runCatching { request.callback(evidence, null) }
    }

    private fun imageBitmap(image: Image): Bitmap {
        check(image.width == width && image.height == height)
        val plane = image.planes[0]
        check(plane.pixelStride == 4 && plane.rowStride % plane.pixelStride == 0)
        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        var result: Bitmap? = null
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            return result
        } finally { if (result !== padded) padded.recycle() }
    }

    private fun consumeExecution(image: Image, request: ExecutionRequest) {
        // Include all following native validation and OCR time in the visual freshness budget.
        val capturedAt = SystemClock.elapsedRealtime()
        if (!executionCurrent(request) || processing) return
        diagnostics.frameReceived()
        if (!executionPageMatches(request)) { stopSafely("native_page_changed"); return }
        diagnostics.compareFrameClock(image.timestamp / 1_000_000, requestedAt)
        diagnostics.frameAccepted()
        frameEpoch.invalidate()
        processing = true
        var bitmap: Bitmap? = null
        var taskOwnsBitmap = false
        try {
            display?.surface = null
            bitmap = imageBitmap(image)
            if (likelyBlank(bitmap)) { diagnostics.captureFailed(); stopSafely("secure_or_blank_frame"); return }
            val screen = NodeBounds(0, 0, width, height)
            val cards = if (ExecutionCapturePolicy.picker(request.snapshot)) ExecutionCapturePolicy.cards(request.snapshot)
                ?: throw IllegalStateException("Invalid native card layout") else emptyList()
            check(cards.all { screen.contains(it.bounds) })
            val selected = cards.associate { card ->
                card.index to ExecutionVisualSelection.selected(width, height, card.bounds, bitmap::getPixel)
            }
            val ownedBitmap = bitmap
            recognizerUsed = true
            diagnostics.ocrStarted()
            Session.captureState = "ocr_running"
            val task = recognizer.process(InputImage.fromBitmap(ownedBitmap, 0))
            taskOwnsBitmap = true
            task.addOnSuccessListener { text ->
                if (!executionCurrent(request)) return@addOnSuccessListener
                try {
                    diagnostics.ocrCompleted()
                    if (!executionPageMatches(request)) { stopSafely("native_page_changed"); return@addOnSuccessListener }
                    val picker = ExecutionCapturePolicy.picker(request.snapshot)
                    if (ExecutionCapturePolicy.unsafeWords(text.textBlocks.map { it.text } + text.text, picker)) {
                        stopSafely("capture_page_not_allowed"); return@addOnSuccessListener
                    }
                    // ML Kit Chinese exposes no calibrated confidence: never synthesize a probability.
                    val words = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val rect = line.boundingBox ?: return@mapNotNull null
                        val bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
                        if (line.text.isBlank() || !screen.contains(bounds) ||
                            !request.snapshot.nodes.first().bounds.contains(bounds)) null else VisualWord(line.text, bounds)
                    }
                    // Constructor enforces count/text budgets; overflow rejects the entire result.
                    finishExecution(request, VisualEvidence(request.snapshot.captureId, width, height, words, selected, capturedAt))
                } catch (_: Exception) { stopSafely("capture_evidence_invalid") }
            }.addOnFailureListener {
                if (executionCurrent(request)) { diagnostics.ocrFailed(); stopSafely("ocr_unavailable") }
            }.addOnCompleteListener {
                ownedBitmap.recycle()
                processing = false
                if (stopped && recognizerUsed) recognizer.close()
                else if (execution == null && pending == null) Session.captureState = "ready"
            }
        } catch (_: Exception) {
            diagnostics.captureFailed()
            stopSafely("capture_failed_or_secure")
        } finally {
            if (!taskOwnsBitmap) {
                bitmap?.recycle()
                processing = false
                if (stopped && recognizerUsed) recognizer.close()
            }
        }
    }

    private fun likelyBlank(bitmap: Bitmap): Boolean {
        var bright = 0
        var total = 0
        // Exclude status/navigation bars; those can remain visible around a secure black app.
        for (y in bitmap.height / 10 until bitmap.height * 9 / 10 step (bitmap.height / 32).coerceAtLeast(1)) {
            for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step (bitmap.width / 24).coerceAtLeast(1)) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 12 || Color.green(pixel) > 12 || Color.blue(pixel) > 12) bright++
                total++
            }
        }
        return total == 0 || bright.toDouble() / total < 0.01
    }

    fun stopSafely(reason: String) {
        if (stopped) return
        stopped = true
        local.tickets.companion.execution.LocalExecutionRuntime.captureStopped(reason)
        frameEpoch.invalidate()
        // Preserve the last phase/counters after summary reset, including timeout evidence.
        diagnostics.freeze()
        pending = null
        val abandoned = execution
        execution = null
        executionGeneration++
        Session.main.removeCallbacks(executionTimeout)
        Session.main.removeCallbacks(guard)
        Session.capture = null
        Session.captureState = reason
        Session.lastError = reason
        Session.summary = SafeSummary(captureState = reason)
        try { display?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        try { projection?.unregisterCallback(projectionCallback); projection?.stop() } catch (_: Exception) { }
        if (recognizerUsed && !processing) recognizer.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        abandoned?.let { runCatching { it.callback(null, reason) } }
    }
    override fun onDestroy() {
        stopSafely("capture_service_stopped")
        try { unregisterReceiver(lockReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}
