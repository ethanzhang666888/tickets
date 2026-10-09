package local.tickets.companion.execution

/** Fixed diagnostic categories, never page text. Only an already-dispatched action may get
 * a short read-only settling window; known unsafe/invalid content is never retried. */
internal enum class NativeReadFailure(val transientAfterAction: Boolean = false) {
    ROOT_UNAVAILABLE(true), TREE_CHANGED(true), WINDOW_UNAVAILABLE(true),
    READ_TIMEOUT, // A full 500ms read has already exhausted the post-dispatch settling budget.
    CANCELLED, LOCKED, SERVICE_LOST, FOREIGN_PACKAGE, PASSWORD_FIELD, SNAPSHOT_LIMIT,
    INVALID_SNAPSHOT, PLATFORM_ERROR,
}
