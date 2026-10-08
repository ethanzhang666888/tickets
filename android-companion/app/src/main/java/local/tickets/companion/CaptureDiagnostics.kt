package local.tickets.companion

import org.json.JSONObject

/** Bounded numeric diagnostics only; production access is serialized on the main thread. */
class CaptureDiagnostics {
    private enum class Phase(val wire: String) {
        IDLE("idle"), AWAITING_FRAME("awaiting_frame"),
        FRAME_REJECTED_TIMESTAMP("frame_rejected_timestamp"), PROCESSING_FRAME("processing_frame"),
        OCR_RUNNING("ocr_running"), COMPLETE("complete"), OCR_FAILED("ocr_failed"), CAPTURE_FAILED("capture_failed")
    }
    private var phase = Phase.IDLE
    private var receivedFrames = 0
    private var rejectedFrames = 0
    private var maxFrameClockBehindMs = 0L
    private var didStartOcr = false
    private var frozen = true

    fun reset() {
        phase = Phase.AWAITING_FRAME
        receivedFrames = 0
        rejectedFrames = 0
        maxFrameClockBehindMs = 0
        didStartOcr = false
        frozen = false
    }
    fun frameReceived() {
        if (!frozen) receivedFrames = (receivedFrames + 1).coerceAtMost(MAX_FRAMES)
    }
    fun compareFrameClock(frameTimestampMs: Long, requestedAtMs: Long) {
        if (frozen || frameTimestampMs >= requestedAtMs) return
        val difference = requestedAtMs - frameTimestampMs
        // With requestedAt > frameTimestamp, a negative subtraction means signed overflow.
        val bounded = if (difference < 0) MAX_CLOCK_BEHIND_MS else difference.coerceAtMost(MAX_CLOCK_BEHIND_MS)
        maxFrameClockBehindMs = maxOf(maxFrameClockBehindMs, bounded)
    }
    fun frameRejected() {
        if (frozen) return
        rejectedFrames = (rejectedFrames + 1).coerceAtMost(MAX_FRAMES)
        phase = Phase.FRAME_REJECTED_TIMESTAMP
    }
    fun frameAccepted() { if (!frozen) phase = Phase.PROCESSING_FRAME }
    fun ocrStarted() {
        if (frozen) return
        didStartOcr = true
        phase = Phase.OCR_RUNNING
    }
    fun ocrCompleted() { if (!frozen) phase = Phase.COMPLETE }
    fun ocrFailed() { if (!frozen) phase = Phase.OCR_FAILED }
    fun captureFailed() { if (!frozen) phase = Phase.CAPTURE_FAILED }
    fun freeze() { frozen = true }
    fun json(): JSONObject = JSONObject().put("phase", phase.wire).put("receivedFrames", receivedFrames)
        .put("rejectedFrames", rejectedFrames).put("maxFrameClockBehindMs", maxFrameClockBehindMs).put("ocrStarted", didStartOcr)
    companion object {
        const val MAX_FRAMES = 1_000_000
        const val MAX_CLOCK_BEHIND_MS = 2_147_483_647L
    }
}
