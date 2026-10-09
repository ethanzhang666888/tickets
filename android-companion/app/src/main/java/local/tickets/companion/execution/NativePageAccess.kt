package local.tickets.companion.execution

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import local.tickets.companion.InspectorAccessibilityService
import local.tickets.companion.Safety
import local.tickets.companion.Session
import local.tickets.companion.pages.*

internal object NativePageAccess {
    @Suppress("DEPRECATION")
    fun read(service: InspectorAccessibilityService, captureId: Long,
             onFailure: (NativeReadFailure) -> Unit = {}, stillActive: () -> Boolean): NativeFrame? {
        fun rejected(reason: NativeReadFailure): NativeFrame? { onFailure(reason); return null }
        if (!stillActive()) return rejected(NativeReadFailure.CANCELLED)
        if (Session.locked(service)) return rejected(NativeReadFailure.LOCKED)
        if (Session.accessibility !== service) return rejected(NativeReadFailure.SERVICE_LOST)
        val started = SystemClock.elapsedRealtime()
        val root = try { service.rootInActiveWindow } catch (_: RuntimeException) { return rejected(NativeReadFailure.PLATFORM_ERROR) }
            ?: return rejected(NativeReadFailure.ROOT_UNAVAILABLE)
        val windowId = root.windowId
        val owned = ArrayList<AccessibilityNodeInfo>()
        val specs = ArrayList<NodeSpec>()
        var characters = 0
        var failure: NativeReadFailure? = null
        var transferred = false
        fun invalidate(reason: NativeReadFailure) { if (failure == null) failure = reason }
        fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            owned += node
            if (failure != null) return
            if (!stillActive()) { invalidate(NativeReadFailure.CANCELLED); return }
            if (node.packageName?.toString() != Safety.DAMAI) { invalidate(NativeReadFailure.FOREIGN_PACKAGE); return }
            if (node.isPassword) { invalidate(NativeReadFailure.PASSWORD_FIELD); return }
            if (depth > NodeSnapshot.MAX_DEPTH || specs.size >= NodeSnapshot.MAX_NODES) { invalidate(NativeReadFailure.SNAPSHOT_LIMIT); return }
            if (SystemClock.elapsedRealtime() - started > 500) { invalidate(NativeReadFailure.READ_TIMEOUT); return }
            val rect = Rect().also(node::getBoundsInScreen)
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if (listOf(text, description, id).any { it.length > NodeSnapshot.MAX_TEXT_LENGTH }) { invalidate(NativeReadFailure.SNAPSHOT_LIMIT); return }
            characters += text.length + description.length + id.length + Safety.DAMAI.length
            if (characters > NodeSnapshot.MAX_TOTAL_CHARACTERS) { invalidate(NativeReadFailure.SNAPSHOT_LIMIT); return }
            val index = specs.size
            specs += NodeSpec(parent, Safety.DAMAI, id, text, description, node.isVisibleToUser,
                node.isEnabled, node.isClickable, if (node.isCheckable) node.isChecked else null,
                node.isSelected, NodeBounds(rect.left, rect.top, rect.right, rect.bottom), node.isScrollable)
            if (node.childCount > NodeSnapshot.MAX_NODES) { invalidate(NativeReadFailure.SNAPSHOT_LIMIT); return }
            for (i in 0 until node.childCount) {
                if (failure != null) break
                val child = node.getChild(i)
                if (child == null) { invalidate(NativeReadFailure.TREE_CHANGED); break }
                visit(child, index, depth + 1)
            }
        }
        try {
            visit(root, null, 1)
            if (failure != null) return null
            if (!stillActive()) { invalidate(NativeReadFailure.CANCELLED); return null }
            if (Session.accessibility !== service) { invalidate(NativeReadFailure.SERVICE_LOST); return null }
            if (Session.locked(service)) { invalidate(NativeReadFailure.LOCKED); return null }
            if (SystemClock.elapsedRealtime() - started > 500) { invalidate(NativeReadFailure.READ_TIMEOUT); return null }
            val current = service.rootInActiveWindow ?: run { invalidate(NativeReadFailure.ROOT_UNAVAILABLE); return null }
            try {
                if (current.packageName?.toString() != Safety.DAMAI) { invalidate(NativeReadFailure.FOREIGN_PACKAGE); return null }
                if (current.windowId != windowId) { invalidate(NativeReadFailure.WINDOW_UNAVAILABLE); return null }
            } finally { current.recycle() }
            if (!windowsMatch(service, windowId)) { invalidate(NativeReadFailure.WINDOW_UNAVAILABLE); return null }
            if (!stillActive()) { invalidate(NativeReadFailure.CANCELLED); return null }
            if (SystemClock.elapsedRealtime() - started > 500) { invalidate(NativeReadFailure.READ_TIMEOUT); return null }
            val snapshot = try { NodeSnapshot.create(captureId, specs, true) } catch (_: IllegalArgumentException) {
                invalidate(NativeReadFailure.INVALID_SNAPSHOT); return null
            }
            val frame = NativeFrame(snapshot, specs, windowId, started, owned)
            transferred = true
            return frame
        } catch (_: RuntimeException) { invalidate(NativeReadFailure.PLATFORM_ERROR); return null }
        finally {
            if (!transferred) {
                owned.forEach { it.recycle() }
                onFailure(failure ?: NativeReadFailure.PLATFORM_ERROR)
            }
        }
    }

    /** Foreground/window metadata only; no traversal or text access for a different package. */
    @Suppress("DEPRECATION")
    fun foregroundMatches(service: InspectorAccessibilityService, windowId: Int): Boolean {
        if (Session.accessibility !== service) return false
        val root = service.rootInActiveWindow ?: return false
        val matches = try { root.packageName?.toString() == Safety.DAMAI && root.windowId == windowId }
            finally { root.recycle() }
        return matches && windowsMatch(service, windowId)
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
