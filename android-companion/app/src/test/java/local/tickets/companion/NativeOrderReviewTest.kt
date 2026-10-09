package local.tickets.companion

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import local.tickets.companion.execution.*
import local.tickets.companion.pages.*
import local.tickets.companion.policy.TicketTarget
import org.junit.Assert.*
import org.junit.Test

class NativeOrderReviewTest {
    private val target = TicketTarget("2026-10-05 19:00", "内场980元", 98000)
    private val plan = LocalPlan("北京·2026周传雄「念念不忘•再遇见plus」演唱会-北京站", listOf(target), 1, 98000, 1, 100000)
    private fun fixture(edit: (MutableList<NodeSpec>) -> Unit = {}): NodeSnapshot {
        val file = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "tools/android/fixtures/confirm.xml") }.first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val specs = mutableListOf<NodeSpec>()
        fun visit(element: Element, parent: Int?) {
            val box = Regex("-?[0-9]+").findAll(element.getAttribute("bounds")).map { it.value.toInt() }.toList()
            val isNode = element.tagName == "node"
            val index = if (isNode) specs.size else parent
            if (isNode) specs += NodeSpec(parent, element.getAttribute("package"), element.getAttribute("resource-id"), element.getAttribute("text"),
                element.getAttribute("content-desc"), element.getAttribute("visible-to-user") == "true", element.getAttribute("enabled") == "true",
                element.getAttribute("clickable") == "true", if (element.getAttribute("checkable") == "true") element.getAttribute("checked") == "true" else null,
                element.getAttribute("selected") == "true", NodeBounds(box[0], box[1], box[2], box[3]), element.getAttribute("scrollable") == "true")
            for (i in 0 until element.childNodes.length) (element.childNodes.item(i) as? Element)?.let { visit(it, index) }
        }
        // UIAutomator fixture contains several windows; runtime reads only the active Damai root.
        val damaiRoot = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            .single { it.getAttribute("package") == DamaiPageParser.PACKAGE }
        visit(damaiRoot, null)
        edit(specs)
        return NodeSnapshot.create(1, specs, true)
    }
    private fun review(snapshot: NodeSnapshot, custom: List<String> = emptyList()) =
        reviewConfirmation(snapshot, DamaiPageParser().parse(snapshot), plan.copy(buyerLabels = custom), target)

    @Test fun measuredCompleteRowsSupportDefaultAndExactCustomChoice() {
        val s = fixture()
        val result = review(s)
        assertTrue("${(result as? ConfirmationReview.Rejected)?.reason}: ${DamaiPageParser().parse(s).blockers}", result is ConfirmationReview.Verified)
        val default = result as ConfirmationReview.Verified
        assertEquals(3, default.buyers.size)
        assertNull(default.change)
        val custom = review(s, listOf("测试观演人乙")) as ConfirmationReview.Verified
        assertEquals(default.buyers.first().label, custom.change!!.first.label)
        assertFalse(custom.change.second) // Deselect extra before selecting another person.
    }
    @Test fun unknownFullIdentityClippedOrChangingOrderDetailsCannotAllowBuyerActions() {
        for (mutation in listOf<(MutableList<NodeSpec>) -> Unit>(
            { nodes -> val i = nodes.indexOfFirst { it.resourceId.endsWith(":id/text_num") }; nodes[i] = nodes[i].copy(text = "110101199001011234") },
            { nodes -> val i = nodes.indexOfFirst { it.resourceId.endsWith(":id/recycler_main") }; nodes[i] = nodes[i].copy(scrollable = true) },
            { nodes -> val i = nodes.indexOfFirst { it.text == "¥ 980.00" }; nodes[i] = nodes[i].copy(text = "¥981") },
            { nodes -> val i = nodes.indexOfFirst { it.resourceId.endsWith(":id/checkbox") }; nodes[i] = nodes[i].copy(checked = null) }
        )) assertTrue(review(fixture(mutation)) is ConfirmationReview.Rejected)
        assertTrue(review(fixture(), listOf("不存在")) is ConfirmationReview.Rejected)
    }
    @Test fun partialConfirmationWithoutRowsNeverBecomesBusinessComplete() {
        val s = NodeSnapshot.create(1, listOf(NodeSpec(resourceId = DamaiPageParser.PREFIX + "order_activity_title", text = "确认购买", bounds = NodeBounds(0, 0, 600, 900))), true)
        assertTrue(review(s) is ConfirmationReview.Rejected)
    }
}
