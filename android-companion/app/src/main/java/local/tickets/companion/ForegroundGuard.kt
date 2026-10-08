package local.tickets.companion

/** Missing/incomplete windows may be transient while idle. No frame may be read while waiting. */
class ForegroundGuard {
    enum class Decision { ALLOW, WAIT, STOP }
    private var missingSince: Long? = null
    private var lastNow = -1L
    private var stopped = false

    fun evaluate(error: String?, idle: Boolean, nowMs: Long): Decision {
        fun stop(): Decision { stopped = true; return Decision.STOP }
        if (stopped || nowMs < 0 || nowMs < lastNow) return stop()
        lastNow = nowMs
        if (error == null) { missingSince = null; return Decision.ALLOW }
        if (!idle || error !in setOf("foreground_unavailable", "node_scan_incomplete")) return stop()
        val since = missingSince ?: nowMs.also { missingSince = it }
        return if (nowMs - since < 1000) Decision.WAIT else stop()
    }
}
