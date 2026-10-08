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
    companion object {
        @Volatile var count = 0
        @Volatile var effectAtNanos = 0L
        @Volatile var effectLatch = CountDownLatch(1)
        @Volatile var visible = false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 40) }
        layout.addView(TextView(this).apply { text = "隔离触摸基准 · 无真实订单"; textSize = 22f })
        val counter = TextView(this).apply { id = R.id.benchmark_counter; text = "0" }
        layout.addView(Button(this).apply {
            id = R.id.benchmark_target; text = "唯一基准按钮"
            setOnClickListener {
                effectAtNanos = SystemClock.elapsedRealtimeNanos()
                count++
                counter.text = count.toString()
                effectLatch.countDown()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 200))
        layout.addView(counter)
        setContentView(layout)
    }
    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { visible = false; super.onPause() }
}
