package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class CaptureEpochTest {
    @Test fun onlyCurrentReaderGenerationCanDeliverAFrame() {
        val gate = CaptureEpoch()
        assertFalse(gate.accepts(0))
        val first = gate.begin()
        assertTrue(gate.accepts(first))
        val second = gate.begin()
        assertNotEquals(first, second)
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }
    @Test fun stopAndCompletionInvalidateEveryPreviouslyIssuedReader() {
        val gate = CaptureEpoch()
        val first = gate.begin()
        gate.invalidate()
        assertFalse(gate.accepts(first))
        val second = gate.begin()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
        gate.invalidate()
        assertFalse(gate.accepts(second))
    }
    @Test fun clockDifferenceIsDiagnosticOnlyForANewReader() {
        val diagnostics = CaptureDiagnostics()
        val gate = CaptureEpoch()
        val current = gate.begin()
        diagnostics.reset()
        diagnostics.frameReceived()
        diagnostics.compareFrameClock(1000, 1070068)
        assertTrue(gate.accepts(current))
        diagnostics.frameAccepted()
        assertEquals(0, diagnostics.json().getInt("rejectedFrames"))
        assertEquals(1069068L, diagnostics.json().getLong("maxFrameClockBehindMs"))
        assertEquals("processing_frame", diagnostics.json().getString("phase"))
    }
}
