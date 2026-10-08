package local.tickets.companion.execution

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import local.tickets.companion.InspectorAccessibilityService
import local.tickets.companion.Safety
import local.tickets.companion.Session
import local.tickets.companion.pages.*

/** Owns/recycles Android handles. Private text never leaves the process or enters telemetry. */
@Suppress("DEPRECATION")
internal class NativeFrame(
    val snapshot: NodeSnapshot,
    val specs: List<NodeSpec>,
    val windowId: Int,
    val startedAt: Long,
    private val nodes: List<AccessibilityNodeInfo>,
) : AutoCloseable {
    fun samePage(other: NativeFrame) = windowId == other.windowId && specs == other.specs
    fun click(index: Int): Boolean {
        val node = nodes.getOrNull(index) ?: return false
        if (!node.refresh() || node.packageName?.toString() != Safety.DAMAI || !node.isVisibleToUser || !node.isEnabled || !node.isClickable) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        val expected = specs[index]
        if (node.viewIdResourceName.orEmpty() != expected.resourceId || node.text?.toString().orEmpty() != expected.text ||
            NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom) != expected.bounds) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    override fun close() { nodes.forEach { it.recycle() } }
}

internal object NativePageAccess {
    @Suppress("DEPRECATION")
    fun read(service: InspectorAccessibilityService, captureId: Long, stillActive: () -> Boolean): NativeFrame? {
        if (!stillActive() || Session.locked(service) || Session.accessibility !== service) return null
        val started = SystemClock.elapsedRealtime()
        val root = service.rootInActiveWindow ?: return null
        val windowId = root.windowId
        val owned = ArrayList<AccessibilityNodeInfo>()
        val specs = ArrayList<NodeSpec>()
        var characters = 0
        var complete = true
        var transferred = false
        fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            owned += node
            if (!complete || !stillActive() || depth > NodeSnapshot.MAX_DEPTH || specs.size >= NodeSnapshot.MAX_NODES ||
                SystemClock.elapsedRealtime() - started > 500 || node.isPassword) { complete = false; return }
            if (node.packageName?.toString() != Safety.DAMAI) { complete = false; return }
            val rect = Rect().also(node::getBoundsInScreen)
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if (listOf(text, description, id).any { it.length > NodeSnapshot.MAX_TEXT_LENGTH }) { complete = false; return }
            characters += text.length + description.length + id.length + Safety.DAMAI.length
            if (characters > NodeSnapshot.MAX_TOTAL_CHARACTERS) { complete = false; return }
            val index = specs.size
            specs += NodeSpec(parent, Safety.DAMAI, id, text, description, node.isVisibleToUser,
                node.isEnabled, node.isClickable, if (node.isCheckable) node.isChecked else null,
                node.isSelected, NodeBounds(rect.left, rect.top, rect.right, rect.bottom), node.isScrollable)
            if (node.childCount > NodeSnapshot.MAX_NODES) { complete = false; return }
            for (i in 0 until node.childCount) {
                if (!complete) break
                val child = node.getChild(i)
                if (child == null) { complete = false; break }
                visit(child, index, depth + 1)
            }
        }
        try {
            visit(root, null, 1)
            if (!complete || !stillActive() || Session.locked(service) || SystemClock.elapsedRealtime() - started > 500) return null
            val current = service.rootInActiveWindow ?: return null
            val matches = try { current.packageName?.toString() == Safety.DAMAI && current.windowId == windowId } finally { current.recycle() }
            if (!matches || !windowsMatch(service, windowId)) return null
            val frame = NativeFrame(NodeSnapshot.create(captureId, specs, true), specs, windowId, started, owned)
            transferred = true
            return frame
        } catch (_: RuntimeException) { return null }
        finally { if (!transferred) owned.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun windowsMatch(service: InspectorAccessibilityService, windowId: Int): Boolean {
        val windows = service.windows
        try {
            if (windows.isEmpty() || windows.size > 16) return false
            return windows.filter { it.isActive || it.isFocused }.all { it.id == windowId }
                && windows.any { it.id == windowId && it.isActive }
        } finally { windows.forEach { it.recycle() } }
    }
}
