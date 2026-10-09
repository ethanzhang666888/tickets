package local.tickets.companion.execution

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import local.tickets.companion.Safety
import local.tickets.companion.pages.*

/** Owns/recycles Android handles. Shared by local accessibility and USB execution. */
@Suppress("DEPRECATION")
internal class NativeFrame(
    val snapshot: NodeSnapshot,
    val specs: List<NodeSpec>,
    val windowId: Int,
    val startedAt: Long,
    private val nodes: List<AccessibilityNodeInfo>,
) : AutoCloseable {
    fun samePage(other: NativeFrame) = windowId == other.windowId && ExecutionCapturePolicy.samePageContent(snapshot, other.snapshot)
    /** Refresh/validate outside the dispatch lock; this call may block in Android. */
    fun prepareClick(index: Int, allowNonClickable: Boolean = false): Boolean {
        val node = nodes.getOrNull(index) ?: return false
        if (!node.refresh() || node.packageName?.toString() != Safety.DAMAI || node.windowId != windowId ||
            !node.isVisibleToUser || !node.isEnabled || (!node.isClickable && !allowNonClickable)) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        val expected = specs[index]
        return node.isClickable == expected.clickable && node.viewIdResourceName.orEmpty() == expected.resourceId && node.text?.toString().orEmpty() == expected.text &&
            (if (node.isCheckable) node.isChecked else null) == expected.checked && node.isSelected == expected.selected &&
            node.isScrollable == expected.scrollable &&
            node.contentDescription?.toString().orEmpty() == expected.description &&
            NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom) == expected.bounds
    }
    /** Only called after preparation and the serialized final dispatch guard. Never refresh here. */
    fun performClick(index: Int): Boolean = nodes.getOrNull(index)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    override fun close() { nodes.forEach { it.recycle() } }
}
