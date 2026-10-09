package local.tickets.companion.execution

/** Entering the expected confirmation stops screenshots, not its native-only review. All revocations stop execution. */
internal object LocalCaptureGuard {
    fun revokes(reason: String, awaitingConfirmation: Boolean) = reason != "payment_foreground" || !awaitingConfirmation
}
