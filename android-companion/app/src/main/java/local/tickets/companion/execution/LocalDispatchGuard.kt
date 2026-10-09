package local.tickets.companion.execution

/** Serializes the final action with cancellation; Android node ownership remains with the caller. */
internal object LocalDispatchGuard {
    enum class Result { CANCELLED, EXPIRED_OR_UNSAFE, PREPARE_REJECTED, SENT, REJECTED }

    fun dispatch(
        gate: Any,
        prepare: () -> Boolean,
        current: () -> Boolean,
        safe: () -> Boolean,
        now: () -> Long,
        deadline: Long,
        capturedAt: Long,
        perform: () -> Boolean,
    ): Result {
        // refresh() may block in another process. Stop must be able to revoke this generation meanwhile.
        if (!prepare()) return Result.PREPARE_REJECTED
        // Foreground/display/lock queries may also cross a process boundary. Keep Stop responsive
        // during those checks too; the final epoch and clock checks below bind them to this dispatch.
        val environmentSafe = safe()
        return synchronized(gate) {
            if (!current()) return@synchronized Result.CANCELLED
            if (!environmentSafe || now().let { it >= deadline || it < capturedAt || it - capturedAt > 500 }) {
                return@synchronized Result.EXPIRED_OR_UNSAFE
            }
            // An action already handed to Android cannot be recalled by a later Stop.
            if (perform()) Result.SENT else Result.REJECTED
        }
    }
}
