package local.tickets.companion.execution

/** Package metadata only. A return to the target never restores a revoked task. Caller serializes access. */
internal class LocalForegroundGuard(private val target: String, private val launchUntil: Long) {
    private var launched = false
    private var revoked = false

    fun departed(packageName: String?, now: Long): Boolean {
        if (revoked) return true
        // Missing transition metadata does not establish a departure; the bounded read loop checks it.
        if (packageName == null) return false
        if (packageName == target) launched = true
        else if (launched || now >= launchUntil) revoked = true
        return revoked
    }
}
