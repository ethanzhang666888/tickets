package local.tickets.companion.execution

import local.tickets.companion.pages.*
import org.junit.Assert.*
import org.junit.Test

class ScopedTouchTargetTest {
    private fun picker(mutate: (MutableList<NodeSpec>) -> Unit = {}): NodeSnapshot {
        val nodes = mutableListOf(
            NodeSpec(bounds = NodeBounds(0, 0, 1080, 2340)),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.TIER_LIST}", bounds = NodeBounds(40, 800, 1000, 1300)),
            NodeSpec(parentIndex = 1, clickable = true, bounds = NodeBounds(450, 970, 800, 1120)),
            NodeSpec(parentIndex = 2, resourceId = "cn.damai:id/ll_perform_item", bounds = NodeBounds(459, 978, 767, 1100)),
            NodeSpec(parentIndex = 3, bounds = NodeBounds(470, 990, 750, 1090))
        )
        mutate(nodes)
        return NodeSnapshot.create(1, nodes, true)
    }
    @Test fun onlyMeasuredSingleCardWrapperGetsCardGeometry() {
        assertEquals(3, ScopedTouchTarget.nodeIndex(picker(), 2, ActionKind.TIER))
        for (kind in ActionKind.entries.filter { it != ActionKind.TIER })
            assertNull(kind.name, ScopedTouchTarget.nodeIndex(picker(), 2, kind))
    }
    @Test fun rejectsAmbiguousClippedHiddenDisabledOrClickableCardTargets() {
        for (fault in listOf("foreign", "incomplete", "id", "text", "disabled", "hidden", "scroll", "clipped", "shared", "child_click", "card_click", "overlap")) {
            val snapshot = picker { n ->
                when (fault) {
                    "foreign" -> n[3] = n[3].copy(packageName = "other")
                    "id" -> n[2] = n[2].copy(resourceId = "cn.damai:id/unknown")
                    "text" -> n[2] = n[2].copy(text = "other action")
                    "disabled" -> n[2] = n[2].copy(enabled = false)
                    "hidden" -> n[3] = n[3].copy(visible = false)
                    "scroll" -> n[2] = n[2].copy(scrollable = true)
                    "clipped" -> n[3] = n[3].copy(bounds = NodeBounds(440, 978, 767, 1100))
                    "shared" -> n += NodeSpec(parentIndex = 2, bounds = n[3].bounds)
                    "child_click" -> n[4] = n[4].copy(clickable = true)
                    "card_click" -> n[3] = n[3].copy(clickable = true)
                    "overlap" -> n += NodeSpec(parentIndex = 1, resourceId = "cn.damai:id/ll_perform_item", bounds = n[3].bounds)
                }
            }
            val candidate = if (fault == "incomplete") NodeSnapshot.create(1, emptyList(), false) else snapshot
            assertNull(fault, ScopedTouchTarget.nodeIndex(candidate, 2, ActionKind.TIER))
        }
    }
    @Test fun rejectsUnrelatedActionableOverlapForCardsAndDetail() {
        for (id in listOf("cn.damai:id/btn_buy_view", "cn.damai:id/unknown_action")) {
            assertNull(ScopedTouchTarget.nodeIndex(picker { n ->
                n += NodeSpec(parentIndex = 0, resourceId = id, clickable = true, bounds = n[3].bounds)
            }, 2, ActionKind.TIER))
            val detail = NodeSnapshot.create(1, listOf(
                NodeSpec(bounds = NodeBounds(0, 0, 1080, 2340)),
                NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_BUTTON}", bounds = NodeBounds(100, 2100, 950, 2250)),
                NodeSpec(parentIndex = 0, resourceId = id, clickable = true, bounds = NodeBounds(400, 2120, 700, 2230))
            ), true)
            assertNull(ScopedTouchTarget.nodeIndex(detail, 1, ActionKind.OPEN_PICKER))
        }
    }

    @Test fun selfDrawnDetailButtonIsAllowedOnlyForOpenPicker() {
        val bounds = NodeBounds(100, 2100, 950, 2250)
        fun detail(click: Boolean = false) = NodeSnapshot.create(1, listOf(
            NodeSpec(bounds = NodeBounds(0, 0, 1080, 2340)),
            NodeSpec(parentIndex = 0, resourceId = "cn.damai:id/${DamaiPageParser.DETAIL_BUTTON}", bounds = bounds, clickable = click)
        ), true)
        assertEquals(1, ScopedTouchTarget.nodeIndex(detail(), 1, ActionKind.OPEN_PICKER))
        assertNull(ScopedTouchTarget.nodeIndex(detail(true), 1, ActionKind.OPEN_PICKER))
        for (kind in ActionKind.entries.filter { it != ActionKind.OPEN_PICKER })
            assertNull(ScopedTouchTarget.nodeIndex(detail(), 1, kind))
    }
}
