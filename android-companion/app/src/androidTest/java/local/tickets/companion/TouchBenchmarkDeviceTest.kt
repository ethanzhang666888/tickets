package local.tickets.companion

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Interleaved device test. ONLY an isolated synthetic button; no Damai UI or permission changes. */
class TouchBenchmarkDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun compareDispatchAndConfirmedEffectOnIsolatedButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("local.tickets.companion.verification", context.packageName)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertFalse("Unlock manually before benchmarking", keyguard.isKeyguardLocked)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(context, TouchBenchmarkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TouchBenchmarkActivity
        val results = mutableListOf<JSONObject>()
        val includeMinitouch = InstrumentationRegistry.getArguments().getString("minitouch") == "true"
        val methods = listOf("accessibility_action_click", "uiautomation_inject_tap", "shell_input_tap") +
            if (includeMinitouch) listOf("minitouch_stf_socket") else emptyList()
        var minitouch: LocalSocket? = null
        var completed = false
        var failure: String? = null
        var expectedCount = 0
        try {
            instrumentation.waitForIdleSync()
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            val size = Point().also(display::getRealSize)
            var stableBounds: Rect? = null
            fun withTarget(action: (AccessibilityNodeInfo, Rect) -> Unit) {
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
                    assertTrue(Rect(0, 0, size.x, size.y).contains(bounds))
                    if (stableBounds == null) stableBounds = Rect(bounds)
                    assertEquals("Synthetic target moved", stableBounds, bounds)
                    instrumentation.runOnMainSync {
                        assertTrue(activity.visible && activity.hasWindowFocus() && !activity.isFinishing && !activity.isDestroyed)
                        assertFalse(keyguard.isKeyguardLocked)
                        assertEquals(Display.DEFAULT_DISPLAY, activity.window.decorView.display.displayId)
                        assertEquals("Benchmark requires portrait rotation", 0, display.rotation)
                        assertEquals("Display size changed", size, Point().also(display::getRealSize))
                        val ownButton = activity.findViewById<Button>(R.id.benchmark_target)
                        assertTrue(ownButton.isShown && ownButton.isEnabled && ownButton.isClickable)
                        val location = IntArray(2).also(ownButton::getLocationOnScreen)
                        assertEquals(bounds, Rect(location[0], location[1], location[0] + ownButton.width, location[1] + ownButton.height))
                        val visibleRect = Rect()
                        assertTrue(ownButton.getLocalVisibleRect(visibleRect))
                        assertEquals(Rect(0, 0, ownButton.width, ownButton.height), visibleRect)
                    }
                    action(button, bounds)
                } finally { handles.forEach { it.recycle() }; root.recycle() }
            }
            withTarget { _, _ -> activity.observation.requireCount(0) }
            if (includeMinitouch) {
                // Pinned minitouch 1.3.2 still calls start_server(sockname) after connecting to
                // STF's minitouchagent. Its named socket emits THREE lines, including native PID.
                // Direct STF emits only TWO lines and is not the path measured by this test.
                // Use a fresh native process for each run: its fallback reuses one STF connection,
                // so a preliminary client would consume the one-time upstream banner.
                val socket = LocalSocket()
                minitouch = socket // Own it before connect/banner operations that may throw.
                socket.connect(LocalSocketAddress("tickets-minitouch-benchmark", LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 2000 // Android creates the FD in connect, not in the constructor.
                val banner = socket.inputStream.bufferedReader(Charsets.US_ASCII)
                assertEquals("v 1", banner.readLine())
                val capabilities = (banner.readLine() ?: error("Missing minitouch capabilities")).split(' ')
                assertEquals(5, capabilities.size)
                assertEquals("^", capabilities[0])
                assertEquals("10", capabilities[1])
                assertEquals(size.x, capabilities[2].toInt())
                assertEquals(size.y, capabilities[3].toInt())
                assertEquals("0", capabilities[4])
                assertTrue("Missing native minitouch PID (direct STF is not this benchmark)",
                    banner.readLine()?.matches(Regex("\\$ [0-9]+")) == true)
            }
            repeat(36) { round ->
                val order = methods.indices.map { methods[(it + round) % methods.size] }
                for (method in order) {
                    withTarget { button, bounds ->
                        val latch = activity.observation.begin(expectedCount)
                        val result = JSONObject().put("method", method).put("round", round)
                            .put("warmup", round < 6).put("accepted", false).put("effectObserved", false)
                            .put("exactlyOnce", false).put("success", false)
                            .put("countBefore", expectedCount)
                            .put("dispatchMs", JSONObject.NULL).put("effectMs", JSONObject.NULL)
                        results += result // Retain a failed/throwing attempt, not just successful samples.
                        val started = SystemClock.elapsedRealtimeNanos()
                        val deadline = started + TimeUnit.SECONDS.toNanos(2)
                        try {
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
                                    true // Dispatch only: the protocol has no per-command acknowledgement.
                                }
                                "shell_input_tap" -> shellTap(automation, bounds, deadline)
                                else -> error("Unknown benchmark method: $method")
                            }
                            val dispatched = SystemClock.elapsedRealtimeNanos()
                            result.put("accepted", accepted).put("dispatchMs", (dispatched - started) / 1_000_000.0)
                            val remaining = (deadline - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0)
                            val observed = latch.await(remaining, TimeUnit.NANOSECONDS)
                            result.put("effectObserved", observed)
                                .put("exactlyOnce", activity.observation.count == expectedCount + 1)
                            assertTrue("Dispatch rejected or exceeded deadline", accepted && dispatched <= deadline)
                            assertTrue("No synthetic handler effect before deadline", observed)
                            val effect = activity.observation.finish(expectedCount + 1, started, deadline)
                            if (method in setOf("uiautomation_inject_tap", "shell_input_tap")) {
                                val point = checkNotNull(activity.lastTouchUp)
                                result.put("observedRawX", point.first).put("observedRawY", point.second)
                                    .put("expectedX", bounds.centerX()).put("expectedY", bounds.centerY())
                                assertEquals("Injected X must reach the requested screen coordinate", bounds.exactCenterX(), point.first, 1f)
                                assertEquals("Injected Y must reach the requested screen coordinate", bounds.exactCenterY(), point.second, 1f)
                            }
                            result.put("effectMs", (effect - started) / 1_000_000.0)
                            expectedCount++
                            // Outside latency metrics: detect queued duplicate/late clicks, including the last trial.
                            // This finite quiet period cannot prove that no arbitrarily delayed global input exists.
                            SystemClock.sleep(50)
                            withTarget { _, _ -> activity.observation.requireCount(expectedCount) }
                            result.put("success", true)
                        } catch (error: Throwable) {
                            result.put("error", error.toString())
                            activity.observation.invalidate("Benchmark aborted")
                            throw error // No retries and no subsequent injections on uncertainty.
                        } finally {
                            val countAfter = activity.observation.count
                            result.put("countAfter", countAfter)
                                .put("exactlyOnce", countAfter == result.getInt("countBefore") + 1)
                        }
                    }
                }
            }
            completed = true
        } catch (error: Throwable) {
            failure = error.toString()
            throw error
        } finally {
            try {
                minitouch?.close()
            } finally {
                try {
                    // On failure, leave our synthetic surface in place rather than reveal another
                    // app while a shell/STF input may still be queued. The supervising caller must
                    // stop its owned input processes before dismissing this activity. Process death
                    // or a focus switch can still remove the surface; this is not input revocation.
                    if (completed) instrumentation.runOnMainSync { activity.finish() }
                } finally {
                    val summaries = JSONArray()
                    for (method in methods) {
                        val group = results.filter { it.getString("method") == method && !it.getBoolean("warmup") }
                        val ok = group.filter { it.getBoolean("success") }
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
                        .put("completed", completed).put("failure", failure ?: JSONObject.NULL)
                        .put("minitouchMeasured", results.any { it.getString("method") == "minitouch_stf_socket" && !it.getBoolean("warmup") && it.getBoolean("success") })
                        .put("minitouchBackend", if (includeMinitouch) "native_minitouch_named_socket_to_STF_InputManager_shell_agent" else JSONObject.NULL)
                        .put("warmupRounds", 6).put("plannedMeasuredRounds", 30).put("postSampleQuietMs", 50)
                        .put("summary", summaries).put("samples", JSONArray(results))
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nTOUCH_BENCHMARK_RESULT=$output\n") })
                }
            }
        }
    }

    private fun shellTap(automation: UiAutomation, bounds: Rect, deadline: Long): Boolean {
        // Reading until EOF without poll could hang forever. Closing this descriptor on timeout
        // does NOT revoke a shell input already submitted; abort the entire run, never retry.
        val descriptor = automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val output = StringBuilder()
            val buffer = ByteArray(128)
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtimeNanos()
                check(remaining > 0) { "Shell dispatch deadline exceeded" }
                check(Os.poll(arrayOf(poll), ceil(remaining / 1_000_000.0).toInt()) > 0) { "Shell dispatch timed out" }
                check(poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) == 0) { "Shell output pipe failed" }
                val read = stream.read(buffer)
                if (read < 0) break
                output.append(String(buffer, 0, read, Charsets.US_ASCII))
                check(output.length <= 128) { "Unexpected shell output" }
            }
            check(output.isEmpty()) { "Unexpected shell input output: $output" }
            return true // Pipe completion is dispatch only, not exit-code or handler-effect proof.
        }
    }
}
