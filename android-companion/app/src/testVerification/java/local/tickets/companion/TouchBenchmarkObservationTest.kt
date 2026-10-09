package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class TouchBenchmarkObservationTest {
    @Test fun recordsFirstEffectAndAcceptsExactlyOneWithinDeadline() {
        val observation = TouchBenchmarkObservation()
        val latch = observation.begin(0)
        observation.record(120)
        assertEquals(0L, latch.count)
        assertEquals(120L, observation.finish(1, 100, 200))
        observation.requireCount(1)
    }

    @Test fun duplicateDoesNotOverwriteFirstTimestampAndFailsSample() {
        val observation = TouchBenchmarkObservation()
        observation.begin(0)
        observation.record(120)
        observation.record(130)
        assertEquals(120L, observation.effectAtNanos)
        assertThrows(IllegalStateException::class.java) { observation.finish(1, 100, 200) }
    }

    @Test fun lateClickBetweenSamplesCannotBecomeNextBaseline() {
        val observation = TouchBenchmarkObservation()
        observation.begin(0)
        observation.record(120)
        observation.finish(1, 100, 200)
        observation.record(220)
        assertThrows(IllegalStateException::class.java) { observation.begin(1) }
    }

    @Test fun rejectsEffectBeforeStartOrAfterDeadlineAndMissingEffect() {
        for (effect in listOf(90L, 201L, null)) {
            val observation = TouchBenchmarkObservation()
            observation.begin(0)
            effect?.let(observation::record)
            assertThrows(IllegalStateException::class.java) { observation.finish(1, 100, 200) }
            assertThrows(IllegalStateException::class.java) { observation.begin(observation.count) }
        }
    }

    @Test fun invalidationIsStickyEvenIfFocusReturns() {
        val observation = TouchBenchmarkObservation()
        observation.begin(0)
        observation.invalidate("Synthetic activity lost focus")
        observation.record(120)
        assertThrows(IllegalStateException::class.java) { observation.finish(1, 100, 200) }
        assertThrows(IllegalStateException::class.java) { observation.begin(1) }
    }

    @Test fun unexpectedInitialClickAndWrongBaselineAreRejected() {
        val observation = TouchBenchmarkObservation()
        observation.record(50)
        assertThrows(IllegalStateException::class.java) { observation.begin(1) }
        assertThrows(IllegalStateException::class.java) { TouchBenchmarkObservation().begin(1) }
    }
}
