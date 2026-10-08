package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class CaptureDiagnosticsTest {
    @Test fun idleAndRequestResetHaveFixedSafeDefaults() {
        val diagnostics = CaptureDiagnostics()
        assertEquals("idle", diagnostics.json().getString("phase"))
        diagnostics.reset()
        diagnostics.frameReceived()
        diagnostics.compareFrameClock(100, 200)
        diagnostics.frameRejected()
        diagnostics.ocrStarted()
        diagnostics.reset()
        val json = diagnostics.json()
        assertEquals("awaiting_frame", json.getString("phase"))
        assertEquals(0, json.getInt("receivedFrames"))
        assertEquals(0, json.getInt("rejectedFrames"))
        assertEquals(0L, json.getLong("maxFrameClockBehindMs"))
        assertFalse(json.getBoolean("ocrStarted"))
    }

    @Test fun framesAbsentAndTimestampRejectedAreDistinguishable() {
        val diagnostics = CaptureDiagnostics()
        diagnostics.reset()
        assertEquals("awaiting_frame", diagnostics.json().getString("phase"))
        repeat(3) {
            diagnostics.frameReceived()
            diagnostics.compareFrameClock(100, 300)
            diagnostics.frameRejected()
        }
        val json = diagnostics.json()
        assertEquals("frame_rejected_timestamp", json.getString("phase"))
        assertEquals(3, json.getInt("receivedFrames"))
        assertEquals(3, json.getInt("rejectedFrames"))
        assertEquals(200L, json.getLong("maxFrameClockBehindMs"))
        assertFalse(json.getBoolean("ocrStarted"))
    }

    @Test fun clockDifferenceIsPositiveMaximumNotAnAbsoluteTimestamp() {
        val diagnostics = CaptureDiagnostics()
        diagnostics.reset()
        diagnostics.compareFrameClock(1_000_000, 1_000_500)
        diagnostics.compareFrameClock(1_000_000, 1_000_100)
        diagnostics.compareFrameClock(2_000_000, 1_000_500)
        diagnostics.compareFrameClock(1_000_500, 1_000_500)
        assertEquals(500L, diagnostics.json().getLong("maxFrameClockBehindMs"))
        // Merely observing a clock difference must not itself reject a frame.
        assertEquals(0, diagnostics.json().getInt("rejectedFrames"))
        diagnostics.compareFrameClock(Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(CaptureDiagnostics.MAX_CLOCK_BEHIND_MS, diagnostics.json().getLong("maxFrameClockBehindMs"))
    }

    @Test fun ocrProgressAndStopPreserveTheLastEvidence() {
        val diagnostics = CaptureDiagnostics()
        diagnostics.reset()
        diagnostics.frameReceived()
        diagnostics.frameAccepted()
        assertEquals("processing_frame", diagnostics.json().getString("phase"))
        diagnostics.ocrStarted()
        val beforeStop = diagnostics.json().toString()
        assertEquals("ocr_running", diagnostics.json().getString("phase"))
        assertTrue(diagnostics.json().getBoolean("ocrStarted"))
        diagnostics.freeze()
        diagnostics.frameReceived()
        diagnostics.compareFrameClock(0, 1_000)
        diagnostics.frameRejected()
        diagnostics.ocrCompleted()
        diagnostics.ocrFailed()
        diagnostics.captureFailed()
        assertEquals(beforeStop, diagnostics.json().toString())
    }

    @Test fun completionAndFailureHaveOnlyFixedPhases() {
        val diagnostics = CaptureDiagnostics()
        diagnostics.reset()
        diagnostics.ocrStarted()
        diagnostics.ocrCompleted()
        assertEquals("complete", diagnostics.json().getString("phase"))
        diagnostics.reset()
        diagnostics.ocrStarted()
        diagnostics.ocrFailed()
        assertEquals("ocr_failed", diagnostics.json().getString("phase"))
        diagnostics.reset()
        diagnostics.captureFailed()
        assertEquals("capture_failed", diagnostics.json().getString("phase"))
        assertFalse(diagnostics.json().getBoolean("ocrStarted"))
    }

    @Test fun countersSaturateAndJsonContainsOnlyFiveSafeFields() {
        val diagnostics = CaptureDiagnostics()
        diagnostics.reset()
        repeat(CaptureDiagnostics.MAX_FRAMES + 10) {
            diagnostics.frameReceived()
            diagnostics.frameRejected()
        }
        val json = diagnostics.json()
        assertEquals(CaptureDiagnostics.MAX_FRAMES, json.getInt("receivedFrames"))
        assertEquals(CaptureDiagnostics.MAX_FRAMES, json.getInt("rejectedFrames"))
        assertEquals(setOf("phase", "receivedFrames", "rejectedFrames", "maxFrameClockBehindMs", "ocrStarted"), json.keys().asSequence().toSet())
        for (key in listOf("text", "image", "name", "identity", "token", "timestamp", "requestedAt")) assertFalse(json.has(key))
    }
}
