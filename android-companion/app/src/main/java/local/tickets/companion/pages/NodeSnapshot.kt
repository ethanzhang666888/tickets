package local.tickets.companion.pages

import java.util.Collections

internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))

data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val hasArea: Boolean get() = right > left && bottom > top
    fun contains(other: NodeBounds): Boolean = hasArea && other.hasArea && other.left >= left &&
        other.top >= top && other.right <= right && other.bottom <= bottom
}

/** Adapter input only; never serialize or log. Parent indices must precede their children. */
data class NodeSpec(
    val parentIndex: Int? = null,
    val packageName: String = "cn.damai",
    val resourceId: String = "",
    val text: String = "",
    val description: String = "",
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val bounds: NodeBounds = NodeBounds(0, 0, 0, 0),
    val scrollable: Boolean = false,
) {
    override fun toString() = "NodeSpec(redacted)"
}

/** Immutable node; children are derived, not accepted as independently inconsistent input. */
class SnapshotNode internal constructor(val index: Int, private val spec: NodeSpec, children: List<Int>) {
    val parentIndex get() = spec.parentIndex
    val packageName get() = spec.packageName
    val resourceId get() = spec.resourceId
    val text get() = spec.text
    val description get() = spec.description
    val visible get() = spec.visible
    val enabled get() = spec.enabled
    val clickable get() = spec.clickable
    val checked get() = spec.checked
    val selected get() = spec.selected
    val bounds get() = spec.bounds
    val scrollable get() = spec.scrollable
    val children = frozenList(children)
    override fun toString() = "SnapshotNode(redacted)"
}

/**
 * One transient native tree, with no Android dependencies, OCR cache, storage, or logging.
 * captureId is a caller-owned correlation ID, NOT proof of freshness or inventory revision.
 * The adapter must set complete=false after timeout, node/depth budget, or traversal failure.
 * It must also enforce budgets WHILE collecting; this factory cannot bound an upstream scan.
 */
class NodeSnapshot private constructor(val captureId: Long, nodes: List<SnapshotNode>, val complete: Boolean) {
    val nodes = frozenList(nodes)
    internal fun ancestors(node: SnapshotNode): List<SnapshotNode> {
        val result = ArrayList<SnapshotNode>()
        var parent = node.parentIndex
        while (parent != null) {
            val item = nodes[parent]
            result += item
            parent = item.parentIndex
        }
        return result
    }
    internal fun subtree(node: SnapshotNode): List<SnapshotNode> {
        val result = ArrayList<SnapshotNode>()
        fun visit(current: SnapshotNode) {
            result += current
            current.children.forEach { visit(nodes[it]) }
        }
        visit(node)
        return result
    }
    internal fun visible(node: SnapshotNode) = node.visible && node.bounds.hasArea &&
        ancestors(node).all { it.visible && it.bounds.hasArea }
    internal fun enabled(node: SnapshotNode) = node.enabled && ancestors(node).all { it.enabled }
    override fun toString() = "NodeSnapshot(redacted)"

    companion object {
        const val MAX_NODES = 512
        const val MAX_DEPTH = 64
        const val MAX_TEXT_LENGTH = 1024
        const val MAX_TOTAL_CHARACTERS = 65_536

        fun create(captureId: Long, specs: List<NodeSpec>, complete: Boolean = false): NodeSnapshot {
            require(captureId >= 0 && specs.size <= MAX_NODES) { "Invalid snapshot bounds." }
            val copy = specs.toList()
            val children = List(copy.size) { ArrayList<Int>() }
            val depth = IntArray(copy.size)
            var characters = 0
            copy.forEachIndexed { index, node ->
                val fields = listOf(node.packageName, node.resourceId, node.text, node.description)
                require(fields.all { it.length <= MAX_TEXT_LENGTH }) { "Snapshot field exceeds limit." }
                characters += fields.sumOf { it.length }
                require(characters <= MAX_TOTAL_CHARACTERS) { "Snapshot text exceeds limit." }
                require(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)
                    .all { it in -100_000..100_000 }) { "Invalid node bounds." }
                val parent = node.parentIndex
                require(parent == null || parent in 0 until index) { "Invalid snapshot parent." }
                depth[index] = if (parent == null) 1 else depth[parent] + 1
                require(depth[index] <= MAX_DEPTH) { "Snapshot depth exceeds limit." }
                if (parent != null) children[parent] += index
            }
            return NodeSnapshot(captureId, copy.mapIndexed { index, node -> SnapshotNode(index, node, children[index]) }, complete)
        }
    }
}
