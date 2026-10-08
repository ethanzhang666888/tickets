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
            if ((pending != null || processing) && SystemClock.elapsedRealtime() - requestedAt > 12_000) {
                stopSafely("capture_or_ocr_timeout"); return
            }
            Session.main.postDelayed(this, 400)
        }
    }

    /** WAIT keeps only the consent resource alive; pending/processing frames never get this grace. */
    fun checkForeground(): Boolean {
        if (stopped) return false
        val error = Session.accessibility?.guardError() ?: if (Session.accessibility == null) "accessibility_lost" else null
        return when (foregroundGuard.evaluate(error, pending == null && !processing, SystemClock.elapsedRealtime())) {
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
            .setContentTitle("仅可行性验证 · 屏幕读取已授权")
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

    fun requestCapture(summary: SafeSummary) {
        if (stopped || projection == null) return
        if (pending != null || processing) { Session.lastError = "capture_busy"; return }
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
            // This surface and its buffers did not exist before this request. Old
            // reader callbacks are excluded by both object identity and generation.
            display?.surface = null
            frameEpoch.invalidate()
            val previous = reader
            reader = null
            previous?.setOnImageAvailableListener(null, null)
            previous?.close()
            reader = createReader(frameEpoch.begin())
            display?.surface = reader?.surface
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
            val plane = image.planes[0]
            check(plane.pixelStride == 4)
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            if (bitmap !== padded) padded.recycle()
            // Protected windows commonly return a black frame. Do not claim this proves FLAG_SECURE.
            if (likelyBlank(bitmap)) {
                bitmap.recycle()
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
        frameEpoch.invalidate()
        // Preserve the last phase/counters after summary reset, including timeout evidence.
        diagnostics.freeze()
        pending = null
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
    }
    override fun onDestroy() {
        stopSafely("capture_service_stopped")
        try { unregisterReceiver(lockReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}
