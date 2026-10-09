package local.tickets.companion

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

/** No real input, screenshots or application switching: fault tests use an in-memory fake sink. */
class PublicFingerTouchDeviceTest {
    @Test fun acceptedSequenceEmitsExactlyOneDownAndUp() {
        val sent = mutableListOf<Int>()
        var pauses = 0
        assertTrue(publicTouchSequence({ sent += it; true }, { pauses++ }))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), sent)
        assertEquals(1, pauses)
    }

    @Test fun rejectedDownCancelsWithoutFinishingClick() {
        val sent = mutableListOf<Int>()
        assertFalse(publicTouchSequence({ sent += it; it != MotionEvent.ACTION_DOWN }, { error("Rejected down must not pause") }))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), sent)
    }

    @Test fun ambiguousDownExceptionCancelsAndPreservesFailure() {
        val sent = mutableListOf<Int>()
        val failure = IllegalStateException("simulated down failure")
        try {
            publicTouchSequence({ sent += it; if (it == MotionEvent.ACTION_DOWN) throw failure; true }, {})
            fail("Expected down failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), sent)
    }

    @Test fun interruptedContactCancelsWithoutUp() {
        val sent = mutableListOf<Int>()
        val failure = IllegalStateException("simulated cancellation")
        try {
            publicTouchSequence({ sent += it; true }, { throw failure })
            fail("Expected cancellation")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), sent)
    }

    @Test fun rejectedUpIsNotRepeatedAndCancelFailureDoesNotHideOriginalFailure() {
        val sent = mutableListOf<Int>()
        assertFalse(publicTouchSequence({ sent += it; it != MotionEvent.ACTION_UP }, {}))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL), sent)
        val failure = IllegalStateException("simulated original failure")
        try {
            publicTouchSequence({ if (it == MotionEvent.ACTION_DOWN) throw failure; error("cancel also failed") }, {})
            fail("Expected original failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }
}
