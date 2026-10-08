package local.tickets.companion

/** One explicit, local-only grant for native structure inspection. Never authorizes capture or clicks. */
class NativeAuditPermit(private val now: () -> Long) {
    private var issuedAt: Long? = null

    fun arm() { issuedAt = now().takeIf { it >= 0 } }
    fun revoke() { issuedAt = null }
    fun consume(): Boolean {
        val start = issuedAt ?: return false
        issuedAt = null
        val current = now()
        return current >= start && current - start < 120_000L
    }
}
