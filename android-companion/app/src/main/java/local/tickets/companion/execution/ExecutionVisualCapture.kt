package local.tickets.companion.execution

import android.os.Looper
import android.os.SystemClock
import local.tickets.companion.CaptureService
import local.tickets.companion.Session
import local.tickets.companion.pages.NodeSnapshot
import local.tickets.companion.pages.VisualEvidence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Worker-only bridge. Consent must already exist; this never launches a permission UI. */
internal object ExecutionVisualCapture {
    private val inFlight = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    @Volatile var lastFailure: String = "none"
        private set

    fun capture(snapshot: NodeSnapshot, stillActive: () -> Boolean): VisualEvidence? {
        lastFailure = "capture_request_unavailable"
        if (Looper.myLooper() == Looper.getMainLooper() || !stillActive()) return null
        if (!ExecutionCapturePolicy.allowed(snapshot)) { lastFailure = "capture_page_not_allowed"; return null }
        if (!inFlight.compareAndSet(false, true)) { lastFailure = "capture_busy"; return null }
        val failure = AtomicReference("capture_timeout_or_cancelled")
        val nonce = generation.incrementAndGet()
        val deadline = SystemClock.elapsedRealtime() + 9_000
        val done = CountDownLatch(1)
        val result = AtomicReference<VisualEvidence?>(null)
        val owner = AtomicReference<CaptureService?>(null)
        val callback: (VisualEvidence?, String?) -> Unit = { evidence, error ->
            if (generation.get() == nonce && SystemClock.elapsedRealtime() < deadline && stillActive()) {
                result.set(evidence)
                failure.set(error ?: if (evidence == null) "capture_evidence_missing" else "none")
            }
            done.countDown()
        }
        val request = Runnable {
            if (generation.get() != nonce || !stillActive() || SystemClock.elapsedRealtime() >= deadline) {
                done.countDown()
            } else {
                val service = Session.capture
                owner.set(service)
                if (service == null) done.countDown() else service.requestExecutionCapture(snapshot, callback)
            }
        }
        try {
            Session.main.post(request)
            while (stillActive() && generation.get() == nonce && SystemClock.elapsedRealtime() < deadline) {
                if (done.await(50, TimeUnit.MILLISECONDS)) return result.get()?.takeIf { stillActive() && SystemClock.elapsedRealtime() < deadline }
            }
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            generation.compareAndSet(nonce, nonce + 1) // No late callback can publish into a later request.
            Session.main.removeCallbacks(request)
            Session.main.post { owner.get()?.cancelExecutionCapture(callback) }
            lastFailure = failure.get() // Published by the single worker, never by a late callback.
            inFlight.set(false)
        }
    }
}
