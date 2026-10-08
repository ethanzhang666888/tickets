package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class NodeScanBudgetTest {
    @Test fun observedTransitionDelayGetsRoomToComplete() {
        assertFalse(NodeScanBudget.exceeded(114, 5))
        assertFalse(NodeScanBudget.exceeded(39, 86))
        assertFalse(NodeScanBudget.exceeded(499, 1199))
    }

    @Test fun timeAndNodeBoundsStillStopIncompleteScans() {
        assertFalse(NodeScanBudget.exceeded(500, 1199))
        assertTrue(NodeScanBudget.exceeded(501, 5))
        assertTrue(NodeScanBudget.exceeded(39, 1200))
        assertTrue(NodeScanBudget.exceeded(Long.MAX_VALUE, 0))
    }

    @Test fun invalidCountersOrClockRollbackFailClosed() {
        assertTrue(NodeScanBudget.exceeded(-1, 5))
        assertTrue(NodeScanBudget.exceeded(0, -1))
        assertEquals(1200, NodeScanBudget.MAX_NODES)
        assertEquals(500L, NodeScanBudget.MAX_DURATION_MS)
    }
}
