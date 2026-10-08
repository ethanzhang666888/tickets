package local.tickets.companion

import java.security.MessageDigest
import java.security.SecureRandom

/** Process-memory-only enrollment. All production calls are serialized on Android's main thread. */
class Enrollment(private val now: () -> Long, private val random: SecureRandom = SecureRandom()) {
    private var code: String? = null
    private var lastIssued: String? = null
    private var issuedAt = 0L
    private var lastObserved = 0L
    private var failures = 0

    fun issue(): String {
        revoke()
        // Avoid immediately reissuing the previous value, including after consumption/revocation.
        var fresh: String
        do { fresh = random.nextInt(100_000_000).toString().padStart(8, '0') } while (fresh == lastIssued)
        issuedAt = now()
        lastObserved = issuedAt
        lastIssued = fresh
        code = fresh
        return fresh
    }

    fun activeCode(): String? {
        if (code == null) return null
        val time = now()
        if (time < lastObserved || time - issuedAt >= VALIDITY_MS) {
            revoke()
            return null
        }
        lastObserved = time
        return code
    }

    fun consume(candidate: String?, validEnvelope: Boolean): Boolean {
        val expected = activeCode() ?: return false
        // Invalid format is never compared, so it cannot serve as a free guessing oracle.
        if (candidate == null || !CODE_FORMAT.matches(candidate)) return false
        failures++
        // A candidate in an invalid envelope still consumes a failure, even when it is correct.
        val accepted = validEnvelope && MessageDigest.isEqual(candidate.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))
        if (accepted || failures >= MAX_FAILURES) revoke()
        return accepted
    }

    fun revoke() {
        code = null
        failures = 0
        issuedAt = 0
        lastObserved = 0
    }

    companion object {
        const val VALIDITY_MS = 120_000L
        const val MAX_FAILURES = 5
        private val CODE_FORMAT = Regex("[0-9]{8}")
    }
}
