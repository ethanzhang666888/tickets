package local.tickets.companion

import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class EnrollmentTest {
    private var now = 1_000L
    private class FixedRandom(vararg values: Int) : SecureRandom() {
        private val sequence = values.iterator()
        override fun nextInt(bound: Int): Int = sequence.nextInt().also { require(it in 0 until bound) }
    }
    private fun enrollment(vararg codes: Int) = Enrollment({ now }, FixedRandom(*codes))

    @Test fun codeIsEightAsciiDigitsIncludingLeadingZeroAndSingleUse() {
        val enrollment = enrollment(1234567)
        assertNull(enrollment.activeCode())
        assertEquals("01234567", enrollment.issue())
        assertTrue(enrollment.consume("01234567", true))
        assertNull(enrollment.activeCode())
        assertFalse(enrollment.consume("01234567", true))
    }
    @Test fun expirationIsExclusiveAtExactly120Seconds() {
        val before = enrollment(12345678)
        before.issue()
        now += 119_999
        assertTrue(before.consume("12345678", true))
        val boundary = enrollment(12345678)
        boundary.issue()
        now += 120_000
        assertNull(boundary.activeCode())
        assertFalse(boundary.consume("12345678", true))
    }
    @Test fun fiveValidFormatFailuresExhaustCodeButFourLeaveOneAttempt() {
        val five = enrollment(12345678)
        five.issue()
        repeat(5) { assertFalse(five.consume("00000000", true)) }
        assertNull(five.activeCode())
        assertFalse(five.consume("12345678", true))
        val four = enrollment(12345678)
        four.issue()
        repeat(4) { assertFalse(four.consume("00000000", true)) }
        assertTrue(four.consume("12345678", true))
    }
    @Test fun wrongEnvelopeStillConsumesValidCandidateAttemptAndCannotSucceed() {
        val enrollment = enrollment(12345678)
        enrollment.issue()
        repeat(5) { assertFalse(enrollment.consume("12345678", false)) }
        assertFalse(enrollment.consume("12345678", true))
    }
    @Test fun malformedCandidatesNeverCompareOrConsumeAValidFormatAttempt() {
        val enrollment = enrollment(12345678)
        enrollment.issue()
        for (candidate in listOf(null, "", "1234567", "123456789", "１２３４５６７８", "1234567 ")) {
            assertFalse(enrollment.consume(candidate, true))
        }
        assertTrue(enrollment.consume("12345678", true))
    }
    @Test fun renewalInvalidatesPreviousAndRetriesImmediateRandomCollision() {
        val enrollment = enrollment(12345678, 12345678, 87654321)
        enrollment.issue()
        assertEquals("87654321", enrollment.issue())
        assertFalse(enrollment.consume("12345678", true))
        assertTrue(enrollment.consume("87654321", true))
    }
    @Test fun revocationAndNewInstanceHaveNoOutstandingCode() {
        val enrollment = enrollment(12345678)
        enrollment.issue()
        enrollment.revoke()
        assertFalse(enrollment.consume("12345678", true))
        assertFalse(enrollment(87654321).consume("12345678", true))
    }
    @Test fun monotonicClockRollbackRevokesRatherThanExtendsValidity() {
        val enrollment = enrollment(12345678)
        enrollment.issue()
        now += 100
        assertNotNull(enrollment.activeCode())
        now--
        assertFalse(enrollment.consume("12345678", true))
        now += 101
        assertNull(enrollment.activeCode())
    }
}
