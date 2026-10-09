package local.tickets.companion

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import local.tickets.companion.pages.NodeBounds

/** Pure sequencing seam for isolated fault tests; never retries a down/up action. */
internal fun publicTouchSequence(inject: (Int) -> Boolean, pause: () -> Unit): Boolean {
    var completed = false
    try {
        if (!inject(MotionEvent.ACTION_DOWN)) return false
        pause()
        completed = inject(MotionEvent.ACTION_UP)
        return completed
    } finally {
        if (!completed) runCatching { inject(MotionEvent.ACTION_CANCEL) }
    }
}

/** Isolated operator control only. Caller must prove a fresh authorized public target before calling.
 * No device-identity spoofing, reflection, system-setting changes or submission/payment use.
 * Android 10 accessibility gestures use TOOL_TYPE_UNKNOWN; this tests an explicit finger descriptor.
 */
internal fun UiAutomation.tapVerifiedPublicControlAsFinger(bounds: NodeBounds, toolType: Int = MotionEvent.TOOL_TYPE_FINGER): Boolean {
    require(toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_UNKNOWN)
    val properties = MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
    val coordinates = MotionEvent.PointerCoords().apply {
        x = (bounds.left + bounds.right) / 2f; y = (bounds.top + bounds.bottom) / 2f
        pressure = 1f; size = 1f
    }
    val downAt = SystemClock.uptimeMillis()
    return publicTouchSequence(inject = { kind ->
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), kind, 1,
            arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { injectInputEvent(event, true) } finally { event.recycle() }
    }, pause = { SystemClock.sleep(60) })
}
