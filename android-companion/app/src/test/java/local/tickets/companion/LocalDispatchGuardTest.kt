package local.tickets.companion

import local.tickets.companion.execution.LocalDispatchGuard
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LocalDispatchGuardTest {
    @Test fun stopCompletesDuringBlockingRefreshOrSafetyCheckAndOldGenerationNeverClicks() {
        for (blockRefresh in listOf(true, false)) {
            val gate = Any()
            var generation = 1L
            var clicks = 0
            val preparing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val threads = Executors.newFixedThreadPool(2)
            val blockingCheck = { preparing.countDown(); check(release.await(5, TimeUnit.SECONDS)); true }
            try {
                val dispatch = threads.submit<LocalDispatchGuard.Result> {
                    LocalDispatchGuard.dispatch(gate,
                        prepare = { if (blockRefresh) blockingCheck() else true },
                        current = { generation == 1L },
                        safe = { if (blockRefresh) true else blockingCheck() }, now = { 100L },
                        deadline = 1000, capturedAt = 0, perform = { clicks++; true })
                }
                assertTrue(preparing.await(2, TimeUnit.SECONDS))
                val stopped = CountDownLatch(1)
                threads.submit { synchronized(gate) { generation++; stopped.countDown() } }
                assertTrue("Stop must not wait for preparation or safety queries", stopped.await(1, TimeUnit.SECONDS))
                release.countDown()
                assertEquals(LocalDispatchGuard.Result.CANCELLED, dispatch.get(2, TimeUnit.SECONDS))
                assertEquals(0, clicks)
            } finally { release.countDown(); threads.shutdownNow() }
        }
    }

    @Test fun deadlineAndFreshnessAreRecheckedAfterBlockingRefresh() {
        for ((deadline, expiredAt) in listOf(400L to 400L, 2000L to 501L)) {
            val clock = AtomicLong(100)
            val preparing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val thread = Executors.newSingleThreadExecutor()
            var clicks = 0
            try {
                val dispatch = thread.submit<LocalDispatchGuard.Result> {
                    LocalDispatchGuard.dispatch(Any(),
                        prepare = { preparing.countDown(); check(release.await(5, TimeUnit.SECONDS)); true },
                        current = { true }, safe = { true }, now = clock::get,
                        deadline = deadline, capturedAt = 0, perform = { clicks++; true })
                }
                assertTrue(preparing.await(2, TimeUnit.SECONDS))
                clock.set(expiredAt)
                release.countDown()
                assertEquals(LocalDispatchGuard.Result.EXPIRED_OR_UNSAFE, dispatch.get(2, TimeUnit.SECONDS))
                assertEquals(0, clicks)
            } finally { release.countDown(); thread.shutdownNow() }
        }
    }

    @Test fun safetyAndClockAreCheckedAfterPreparationAndBeforePerform() {
        var safe = true
        var clicks = 0
        assertEquals(LocalDispatchGuard.Result.EXPIRED_OR_UNSAFE,
            LocalDispatchGuard.dispatch(Any(), { safe = false; true }, { true }, { safe }, { 100 }, 1000, 0,
                { clicks++; true }))
        var now = 100L
        assertEquals(LocalDispatchGuard.Result.EXPIRED_OR_UNSAFE,
            LocalDispatchGuard.dispatch(Any(), { true }, { true }, { now = 1000; true }, { now }, 1000, 0,
                { clicks++; true }))
        assertEquals(0, clicks)
    }

    @Test fun preparationIsOutsideGateAndPerformIsInsideGate() {
        val gate = Any()
        val calls = mutableListOf<String>()
        val result = LocalDispatchGuard.dispatch(gate,
            prepare = { assertFalse(Thread.holdsLock(gate)); calls += "prepare"; true },
            current = { true }, safe = { assertFalse(Thread.holdsLock(gate)); calls += "safe"; true }, now = { 100 },
            deadline = 1000, capturedAt = 0,
            perform = { assertTrue(Thread.holdsLock(gate)); calls += "perform"; true })
        assertEquals(LocalDispatchGuard.Result.SENT, result)
        assertEquals(listOf("prepare", "safe", "perform"), calls)
    }

    @Test fun failedPreparationAndRejectedActionRemainDistinct() {
        assertEquals(LocalDispatchGuard.Result.PREPARE_REJECTED,
            LocalDispatchGuard.dispatch(Any(), { false }, { true }, { true }, { 100 }, 1000, 0,
                { fail("failed refresh must not click"); true }))
        assertEquals(LocalDispatchGuard.Result.REJECTED,
            LocalDispatchGuard.dispatch(Any(), { true }, { true }, { true }, { 100 }, 1000, 0, { false }))
    }
}
