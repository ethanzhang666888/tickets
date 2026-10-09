package local.tickets.companion

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** Verification variant only: no bridge, accessibility service, account or order operations. */
class TouchBenchmarkActivity : Activity() {
    // Instance-owned evidence: recreation must never silently continue the old run.
    val observation = TouchBenchmarkObservation()
    @Volatile var lastTouchUp: Pair<Float, Float>? = null
        private set

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) lastTouchUp = event.rawX to event.rawY
        return super.dispatchTouchEvent(event)
    }
    @Volatile var visible = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 40) }
        layout.addView(TextView(this).apply { text = "隔离触摸基准 · 无真实订单"; textSize = 22f })
        val counter = TextView(this).apply { id = R.id.benchmark_counter; text = "0" }
        layout.addView(Button(this).apply {
            id = R.id.benchmark_target; text = "唯一基准按钮"
            setOnClickListener {
                observation.record(SystemClock.elapsedRealtimeNanos())
                counter.text = observation.count.toString()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 200))
        layout.addView(counter)
        setContentView(layout)
    }
    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() {
        visible = false
        observation.invalidate("Synthetic activity paused")
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) observation.invalidate("Synthetic activity lost window focus")
    }
}

/** Handler evidence only; no Android dependencies, injection or production use. */
class TouchBenchmarkObservation {
    @Volatile var count = 0
        private set
    @Volatile var effectAtNanos = 0L
        private set
    private var active = false
    private var started = false
    private var failure: String? = null
    private var latch = CountDownLatch(1)

    @Synchronized fun begin(expectedCount: Int): CountDownLatch {
        requireCount(expectedCount)
        check(!active) { "Previous sample still active" }
        started = true
        active = true
        effectAtNanos = 0
        latch = CountDownLatch(1)
        return latch
    }

    @Synchronized fun record(atNanos: Long) {
        count++
        if (!active || effectAtNanos != 0L) {
            failure = failure ?: "Unexpected or duplicate synthetic click"
        } else {
            effectAtNanos = atNanos // Never replace the first handler timestamp with a duplicate.
        }
        latch.countDown()
    }

    @Synchronized fun invalidate(reason: String) {
        if (started) failure = failure ?: reason
    }

    @Synchronized fun requireCount(expectedCount: Int) {
        check(failure == null) { failure!! }
        check(count == expectedCount) { "Synthetic counter changed: expected $expectedCount, got $count" }
    }

    @Synchronized fun finish(expectedCount: Int, startedAt: Long, deadline: Long): Long {
        active = false
        if (count != expectedCount || effectAtNanos !in startedAt..deadline || effectAtNanos == 0L) {
            failure = failure ?: "Missing, late or uncorrelated synthetic effect"
        }
        requireCount(expectedCount)
        return effectAtNanos
    }
}
