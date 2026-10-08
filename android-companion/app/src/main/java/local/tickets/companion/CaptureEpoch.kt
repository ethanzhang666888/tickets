package local.tickets.companion

/** Identifies the reader created after one explicit capture request, not a device clock domain. */
class CaptureEpoch {
    private var sequence = 0L
    private var active: Long? = null
    fun begin(): Long {
        check(sequence < Long.MAX_VALUE)
        return (++sequence).also { active = it }
    }
    fun accepts(epoch: Long): Boolean = active == epoch
    fun invalidate() { active = null }
}
