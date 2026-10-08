package local.tickets.companion

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Interleaved device test. ONLY an isolated synthetic button; no Damai UI or permission changes. */
class TouchBenchmarkDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun compareDispatchAndConfirmedEffectOnIsolatedButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("local.tickets.companion.verification", context.packageName)
        assertFalse("Unlock manually before benchmarking", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(context, TouchBenchmarkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val results = mutableListOf<JSONObject>()
        val includeMinitouch = InstrumentationRegistry.getArguments().getString("minitouch") == "true"
        val methods = listOf("accessibility_action_click", "uiautomation_inject_tap", "shell_input_tap") +
            if (includeMinitouch) listOf("minitouch_stf_socket") else emptyList()
        var minitouch: android.net.LocalSocket? = null
        try {
            instrumentation.waitForIdleSync()
            if (includeMinitouch) {
                assertEquals(0, context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation)
                val size = android.graphics.Point().also { context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealSize(it) }
                minitouch = android.net.LocalSocket().apply {
                    connect(android.net.LocalSocketAddress("tickets-minitouch-benchmark", android.net.LocalSocketAddress.Namespace.ABSTRACT))
                    soTimeout = 2000
                }
                val banner = minitouch!!.inputStream.bufferedReader()
                assertEquals("v 1", banner.readLine())
                val capabilities = banner.readLine().split(' ')
                assertEquals("^", capabilities[0])
                assertEquals(size.x, capabilities[2].toInt())
                assertEquals(size.y, capabilities[3].toInt())
                assertEquals("0", capabilities[4]) // This run explicitly measures the STF fallback, not evdev.
                assertTrue(banner.readLine().matches(Regex("\\$ [0-9]+")))
            }
            repeat(36) { round ->
                val order = methods.indices.map { methods[(it + round) % methods.size] }
                for (method in order) {
                    assertTrue(TouchBenchmarkActivity.visible)
                    assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                    val root = automation.rootInActiveWindow ?: error("Synthetic window unavailable")
                    val handles = mutableListOf<AccessibilityNodeInfo>()
                    try {
                        assertEquals(context.packageName, root.packageName?.toString())
                        handles += root.findAccessibilityNodeInfosByViewId("${context.packageName}:id/benchmark_target")
                        val button = handles.single()
                        assertTrue(button.isEnabled && button.isVisibleToUser && button.isClickable)
                        assertEquals("android.widget.Button", button.className?.toString())
                        val bounds = Rect().also(button::getBoundsInScreen)
                        assertTrue(bounds.width() > 0 && bounds.height() > 0)
                        val before = TouchBenchmarkActivity.count
                        instrumentation.runOnMainSync {
                            TouchBenchmarkActivity.effectAtNanos = 0
                            TouchBenchmarkActivity.effectLatch = CountDownLatch(1)
                        }
                        val display = context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
                        assertEquals("Benchmark requires portrait rotation", 0, display.rotation)
                        val displaySize = android.graphics.Point().also(display::getRealSize)
                        assertTrue(Rect(0, 0, displaySize.x, displaySize.y).contains(bounds))
                        val started = SystemClock.elapsedRealtimeNanos()
                        val accepted = when (method) {
                            "accessibility_action_click" -> button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            "uiautomation_inject_tap" -> {
                                val downTime = SystemClock.uptimeMillis()
                                fun event(action: Int) = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                                val down = event(MotionEvent.ACTION_DOWN)
                                val downAccepted = try { automation.injectInputEvent(down, true) } finally { down.recycle() }
                                val up = event(MotionEvent.ACTION_UP)
                                val upAccepted = try { automation.injectInputEvent(up, true) } finally { up.recycle() }
                                downAccepted && upAccepted
                            }
                            "minitouch_stf_socket" -> {
                                val socket = checkNotNull(minitouch)
                                val commands = "d 0 ${bounds.centerX()} ${bounds.centerY()} 0\nc\nu 0\nc\n"
                                socket.outputStream.write(commands.toByteArray(Charsets.US_ASCII))
                                socket.outputStream.flush()
                                true // Socket write is dispatch only; the button handler must confirm the effect.
                            }
                            "shell_input_tap" -> {
                                automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { descriptor ->
                                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                                        val buffer = ByteArray(1024)
                                        assertTrue("Unexpected shell output", stream.read(buffer) <= 0)
                                    }
                                }
                                true // Shell completion alone is NOT evidence that the UI received the click.
                            }
                            else -> error("Unknown benchmark method: $method")
                        }
                        val dispatched = SystemClock.elapsedRealtimeNanos()
                        val observed = TouchBenchmarkActivity.effectLatch.await(2, TimeUnit.SECONDS)
                        val exactlyOnce = TouchBenchmarkActivity.count == before + 1
                        val result = JSONObject().put("method", method).put("round", round)
                            .put("warmup", round < 6).put("accepted", accepted).put("effectObserved", observed)
                            .put("exactlyOnce", exactlyOnce)
                            .put("dispatchMs", (dispatched - started) / 1_000_000.0)
                            .put("effectMs", if (observed) (TouchBenchmarkActivity.effectAtNanos - started) / 1_000_000.0 else JSONObject.NULL)
                        results += result
                        // Stop on uncertainty; never repeat a touch to make a bad sample look successful.
                        assertTrue("Unconfirmed synthetic touch: $result", accepted && observed && exactlyOnce)
                    } finally { handles.forEach { it.recycle() }; root.recycle() }
                }
            }
        } finally {
            try { minitouch?.close() } finally { instrumentation.runOnMainSync { activity.finish() } }
            val summaries = JSONArray()
            for (method in methods) {
                val group = results.filter { it.getString("method") == method && !it.getBoolean("warmup") }
                val ok = group.filter { it.getBoolean("effectObserved") && it.getBoolean("exactlyOnce") }
                fun percentile(key: String, p: Double): Any {
                    val values = ok.map { it.getDouble(key) }.sorted()
                    if (values.isEmpty()) return JSONObject.NULL
                    if (p == .5 && values.size % 2 == 0) return (values[values.size / 2 - 1] + values[values.size / 2]) / 2
                    return values[(ceil(values.size * p).toInt() - 1).coerceAtLeast(0)]
                }
                summaries.put(JSONObject().put("method", method).put("samples", group.size).put("successes", ok.size)
                    .put("dispatchMedianMs", percentile("dispatchMs", .5)).put("dispatchP95Ms", percentile("dispatchMs", .95))
                    .put("effectMedianMs", percentile("effectMs", .5)).put("effectP95Ms", percentile("effectMs", .95)))
            }
            val output = JSONObject().put("scope", "isolated_synthetic_only").put("uiAutomator2EndToEndMeasured", false)
                .put("minitouchMeasured", results.any { it.getString("method") == "minitouch_stf_socket" && !it.getBoolean("warmup") })
                .put("minitouchBackend", if (includeMinitouch) "STF_InputManager_shell_agent" else JSONObject.NULL)
                .put("warmupRounds", 6).put("plannedMeasuredRounds", 30)
                .put("summary", summaries).put("samples", JSONArray(results))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nTOUCH_BENCHMARK_RESULT=$output\n") })
        }
    }
}
