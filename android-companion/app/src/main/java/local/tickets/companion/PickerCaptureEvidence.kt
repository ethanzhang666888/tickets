package local.tickets.companion

/** Recognizes a measured native picker layout for READ-ONLY capture, never for clicking.
 * A generic reference to payment on this exact layout is not itself a cashier.
 * Strong payment/confirmation markers always retain priority, including OCR results.
 */
class PickerCaptureEvidence {
    private data class Node(val parent: Int?, val id: String?, val visible: Boolean, val hasContent: Boolean)
    private val nodes = mutableListOf<Node>()
    private var strongPayment = false
    private var invalid = false

    fun observe(parent: Int?, id: String?, visible: Boolean, text: CharSequence?, description: CharSequence?) {
        if (nodes.size >= NodeScanBudget.MAX_NODES || (parent != null && parent !in nodes.indices)) {
            invalid = true
            return
        }
        nodes += Node(parent, id, visible, !text.isNullOrEmpty() || !description.isNullOrEmpty())
        if (visible && (isStrongPayment(text) || isStrongPayment(description))) strongPayment = true
    }

    fun recognized(complete: Boolean): Boolean {
        if (!complete || invalid || strongPayment || nodes.isEmpty()) return false
        if (nodes.any { it.id in setOf("cn.damai:id/order_activity_title", "cn.damai:id/damai_theme_dialog_confirm_btn") }) return false
        val hasLegacyContainers = nodes.any { it.id in setOf("cn.damai:id/project_detail_perform_flowlayout", "cn.damai:id/project_detail_perform_price_flowlayout") }
        if (!hasLegacyContainers) return originalLayout()
        val measuredShell = nodes.any { it.id in setOf("cn.damai:id/layout_sku", "cn.damai:id/sku_contanier",
            "cn.damai:id/action_bar_root", "cn.damai:id/water_mark_view_id") }
        return if (measuredShell) measuredLegacyLayout() else expandedLegacyLayout()
    }

    private fun originalLayout(): Boolean {
        fun unique(id: String): Int? = nodes.indices.filter { nodes[it].id == "cn.damai:id/$id" }.singleOrNull()
        fun visible(id: String): Int? = unique(id)?.takeIf { nodes[it].visible }
        val scroll = visible("preform_scrollview") ?: return false
        val title = visible("tv_title") ?: return false
        val session = visible("tv_perform_name") ?: return false
        val tier = visible("tv_price_name") ?: return false
        val bottom = visible("bottom_layout") ?: return false
        val buy = visible("btn_buy_view") ?: return false
        val quantity = visible("tv_num") ?: return false
        val back = visible("title_back_btn") ?: return false
        val hiddenTitle = unique("title_tv") ?: return false
        val body = nodes[scroll].parent ?: return false
        val root = nodes[bottom].parent ?: return false
        if (nodes[root].parent != null || nodes.count { it.parent == null } != 1) return false
        if (nodes[body].parent != root || nodes[title].parent != body
            || nodes[session].parent != scroll || nodes[tier].parent != scroll
            || nodes[buy].parent != bottom || nodes[quantity].parent != bottom
            || nodes[back].parent != root || nodes[hiddenTitle].parent != root) return false
        // Reject extra root-level overlays and known dialogs; do not broadly exempt unknown pages.
        if (nodes.indices.filter { nodes[it].parent == root }.toSet() != setOf(back, hiddenTitle, body, bottom)) return false
        return nodes.none { it.id in setOf("cn.damai:id/order_activity_title", "cn.damai:id/damai_theme_dialog_confirm_btn") }
    }

    /** The include-unimportant flag reveals wrappers and the two legacy flow layouts.
     * Only this anchored ancestry is accepted; arbitrary selection text is insufficient.
     */
    private fun expandedLegacyLayout(): Boolean {
        fun unique(id: String, visible: Boolean = true): Int? = nodes.indices
            .filter { nodes[it].id == "cn.damai:id/$id" }.singleOrNull()
            ?.takeIf { !visible || nodes[it].visible }
        fun ancestors(index: Int): List<Int> {
            val result = ArrayList<Int>()
            var parent = nodes[index].parent
            while (parent != null) { result += parent; parent = nodes[parent].parent }
            return result
        }
        fun beneath(child: Int, parent: Int) = parent in ancestors(child)
        fun branch(child: Int, parent: Int): Int? = (listOf(child) + ancestors(child)).firstOrNull { nodes[it].parent == parent }
        val root = nodes.indices.filter { nodes[it].parent == null }.singleOrNull() ?: return false
        val title = unique("tv_title") ?: return false
        val scroll = unique("preform_scrollview") ?: return false
        val sessionName = unique("tv_perform_name") ?: return false
        val tierName = unique("tv_price_name") ?: return false
        val sessions = unique("project_detail_perform_flowlayout") ?: return false
        val tiers = unique("project_detail_perform_price_flowlayout") ?: return false
        val bottom = unique("bottom_layout") ?: return false
        val buy = unique("btn_buy_view") ?: return false
        val quantity = unique("tv_num") ?: return false
        val back = unique("title_back_btn") ?: return false
        val hiddenTitle = unique("title_tv", false) ?: return false
        if (listOf(sessionName, tierName, sessions, tiers).any { !beneath(it, scroll) } ||
            beneath(sessions, tiers) || beneath(tiers, sessions) || beneath(title, scroll) ||
            !beneath(buy, bottom) || !beneath(quantity, bottom)) return false
        val body = ancestors(scroll).firstOrNull { beneath(title, it) } ?: return false
        if (beneath(bottom, body) || beneath(body, bottom)) return false
        val screen = ancestors(bottom).firstOrNull { beneath(body, it) && beneath(back, it) && beneath(hiddenTitle, it) }
            ?: return false
        if (branch(body, screen) == branch(bottom, screen) || branch(back, screen) in setOf(branch(body, screen), branch(bottom, screen)) ||
            branch(hiddenTitle, screen) in setOf(branch(body, screen), branch(bottom, screen))) return false
        val branches = listOf(body, bottom, back, hiddenTitle).map { branch(it, screen) }.toSet()
        if (nodes.indices.filter { nodes[it].parent == screen }.toSet() != branches) return false
        // Wrapper ancestors may add no sibling overlay at all, including unnamed overlays.
        var child = screen
        while (child != root) {
            val parent = nodes[child].parent ?: return false
            if (nodes.count { it.parent == parent } != 1) return false
            child = parent
        }
        val anchors = listOf(title, scroll, sessionName, tierName, sessions, tiers, bottom, buy, quantity, back)
        if (anchors.any { index -> ancestors(index).any { !nodes[it].visible } }) return false
        return nodes.all { it.id.isNullOrEmpty() || it.id.startsWith("cn.damai:id/") || it.id == "android:id/content" }
    }

    /** Exact measured 116-node initial and 128-node selected device topologies.
     * The initial state has no quantity row; the selected state has the measured native row.
     * Only the observed card repetition/tag variants vary.
     * Decorations must remain empty leaves/subtrees, not arbitrary overlay exemptions.
     * observe has no interactivity argument: this check cannot prove non-clickability.
     */
    private fun measuredLegacyLayout(): Boolean {
        data class Shape(val id: String = "", val visible: Boolean = true, val silent: Boolean = false,
            val children: List<Shape> = emptyList(), val repeated: Boolean = false, val choices: List<Shape> = emptyList())
        fun n(id: String = "", vararg children: Shape) = Shape(id, children = children.toList())
        fun h(id: String = "", vararg children: Shape) = Shape(id, visible = false, children = children.toList())
        fun d(id: String = "", visible: Boolean = true, vararg children: Shape) =
            Shape(id, visible, silent = true, children = children.toList())
        fun chain(vararg ids: String, leaf: Shape): Shape = ids.reversed().fold(leaf) { child, id -> n(id, child) }
        val tag = Shape(choices = listOf(n("layout_tag", n("", n("tv_tag"))), h("layout_tag")))
        val card = n("ll_perform_item", n("", d("layout_tag_promotion", false),
            n("item_flowlayout", d(visible = false), tag)))
        fun flow(id: String) = Shape(id, children = listOf(n("", card)), repeated = true)
        val perform = n("layout_perform_view", n("", n("tv_perform_name"), n("tv_perform_tip")),
            flow("project_detail_perform_flowlayout"))
        val price = n("layout_price", n("", d("layout_seat_img", false), n("tv_price_name")),
            flow("project_detail_perform_price_flowlayout"), d("view_scroll_location"))
        val scroll = chain("preform_scrollview", "id_sku_main_content_layout", leaf = n("", perform, price))
        val top = n("layout_top", n("", n("layout_top_normal", n("tv_title"), n("tv_desc")),
            d("header_image_platform_icon")), d("layout_start_bg", true, d()))
        val main = n("main_view", n("", chain("appbar", "layout_scroll", leaf = top), scroll))
        val title = n("discount_ticket_title_layout", d("title_bg_layout", false, d(visible = false)),
            n("", n("title_back_btn"), h("title_tv")))
        val content = n("", d("title_bar_space", false), title, main)
        val unselectedBottom = chain("bottom_layout", "id_sku_bottom_bar_container", leaf = n("id_sku_bottom_bar_action_content",
            d("btn_buy_bottom_div_line"), n("btn_buy_bottom_div",
                n("layout_left", n("", n("tv_sku_price_prefix"), n("tv_price"))),
                n("", d("view_translucent"), n("", n("btn_buy_view", d(visible = false)))))))
        val quantity = n("layout_num", n("", n("", n("tv_limit_num_title")), n("tv_limit_num")),
            n("layout_sku_num_ll", n("img_jian"), n("tv_num"), n("img_jia")))
        val selectedBottom = n("bottom_layout", d("layout_num_top"),
            n("id_sku_bottom_bar_container", n("id_sku_bottom_bar_action_content", quantity,
                d("btn_buy_bottom_div_line"), n("btn_buy_bottom_div",
                    n("layout_left", n("", n("tv_sku_price_prefix"), n("tv_price"),
                        n("layout_price_detail", n(), n("view_jiantou")))),
                    n("", n("", n("btn_buy_view", d(visible = false))))))))
        val bottom = Shape(choices = listOf(unselectedBottom, selectedBottom))
        val layout = n("layout_sku", d("id_splice_fragment_container"), d("header", true, d("header_image")), content, bottom)
        val actionBar = chain("action_bar_root", "android:id/content", "", "", "", "sku_contanier", "", "", leaf = layout)
        val expected = n("", n("", n("", d("water_mark_view_id"), actionBar)))
        val children = nodes.indices.groupBy { nodes[it].parent }
        fun matches(index: Int, shape: Shape): Boolean {
            if (shape.choices.isNotEmpty()) return shape.choices.any { matches(index, it) }
            val node = nodes[index]
            val id = if (shape.id.isEmpty() || ':' in shape.id) shape.id else "cn.damai:id/${shape.id}"
            if (node.id.orEmpty() != id || node.visible != shape.visible || (shape.silent && node.hasContent)) return false
            val actual = children[index].orEmpty()
            return if (shape.repeated) actual.isNotEmpty() && actual.all { matches(it, shape.children.single()) }
            else actual.size == shape.children.size && actual.zip(shape.children).all { (child, pattern) -> matches(child, pattern) }
        }
        val root = children[null]?.singleOrNull() ?: return false
        return matches(root, expected)
    }

    companion object {
        fun isStrongPayment(value: CharSequence?): Boolean = value != null && STRONG.any { value.contains(it, ignoreCase = true) }
        private val STRONG = listOf("确认支付", "收银台", "支付密码", "立即付款", "立即支付", "付款码",
            "提交订单", "确认订单", "订单确认", "确认购买", "购买确认", "pay now", "checkout")
    }
}
