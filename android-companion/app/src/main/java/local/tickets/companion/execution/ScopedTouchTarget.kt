package local.tickets.companion.execution

import local.tickets.companion.pages.*

/** No guessed coordinates or click-then-touch retry. Only measured public self-drawn controls.
 * Caller must already verify plan identity/labels, two visual reads, current window and consent.
 * Confirmation, attendee, submission and payment controls never receive a touch target.
 */
internal object ScopedTouchTarget {
    fun nodeIndex(snapshot: NodeSnapshot, actionIndex: Int, kind: ActionKind): Int? {
        if (!snapshot.complete || snapshot.nodes.any { it.packageName != DamaiPageParser.PACKAGE }) return null
        val owner = snapshot.nodes.getOrNull(actionIndex) ?: return null
        fun safe(node: SnapshotNode) = snapshot.visible(node) && snapshot.enabled(node) && !node.scrollable &&
            node.bounds.right - node.bounds.left >= 40 && node.bounds.bottom - node.bounds.top >= 25 &&
            snapshot.ancestors(node).all { it.bounds.contains(node.bounds) }
        if (!safe(owner)) return null
        fun overlaps(a: NodeBounds, b: NodeBounds) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        fun overlapsOtherAction(target: SnapshotNode): Boolean {
            val related = (snapshot.ancestors(owner) + snapshot.subtree(owner)).map { it.index }.toSet()
            return snapshot.nodes.any { it.index !in related && it.clickable && snapshot.visible(it) && overlaps(it.bounds, target.bounds) }
        }
        if (kind == ActionKind.OPEN_PICKER) {
            if (owner.resourceId != DamaiPageParser.PREFIX + DamaiPageParser.DETAIL_BUTTON ||
                snapshot.nodes.count { it.resourceId == owner.resourceId } != 1 ||
                snapshot.subtree(owner).any { it.clickable } || overlapsOtherAction(owner)) return null
            return owner.index
        }
        val containerId = when (kind) {
            ActionKind.SESSION -> DamaiPageParser.SESSION_LIST
            ActionKind.TIER -> DamaiPageParser.TIER_LIST
            else -> return null
        }
        if (!owner.clickable || owner.resourceId.isNotEmpty() || owner.text.isNotBlank() || owner.description.isNotBlank()) return null
        val container = owner.parentIndex?.let { snapshot.nodes[it] } ?: return null
        if (container.resourceId != DamaiPageParser.PREFIX + containerId || owner.children.size != 1) return null
        val card = snapshot.nodes[owner.children.single()]
        if (card.resourceId != DamaiPageParser.PREFIX + "ll_perform_item" || !safe(card) ||
            snapshot.subtree(card).any { it.clickable } || !owner.bounds.contains(card.bounds)) return null
        if (overlapsOtherAction(card)) return null
        if (snapshot.nodes.any { it.index != card.index && it.resourceId == card.resourceId &&
                snapshot.visible(it) && overlaps(it.bounds, card.bounds) }) return null
        return card.index
    }
}
