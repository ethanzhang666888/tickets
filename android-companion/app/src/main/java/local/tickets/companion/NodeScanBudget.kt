package local.tickets.companion

/** Bounds synchronous traversal; individual Android node calls are not interruptible. */
object NodeScanBudget {
    const val MAX_NODES = 1200
    // Target-device transition: 5 nodes already took 114 ms; a stable 86-node
    // scan took 39 ms. Allow bounded traversal delay without accepting partial trees.
    const val MAX_DURATION_MS = 500L

    fun exceeded(elapsedMs: Long, visitedNodes: Int): Boolean =
        elapsedMs < 0 || visitedNodes < 0 || elapsedMs > MAX_DURATION_MS || visitedNodes >= MAX_NODES
}
