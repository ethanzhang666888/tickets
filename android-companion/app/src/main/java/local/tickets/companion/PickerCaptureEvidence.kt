package local.tickets.companion

/** Recognizes a measured native picker layout for READ-ONLY capture, never for clicking.
 * A generic reference to payment on this exact layout is not itself a cashier.
 * Strong payment/confirmation markers always retain priority, including OCR results.
 */
class PickerCaptureEvidence {
    private data class Node(val parent: Int?, val id: String?, val visible: Boolean)
    private val nodes = mutableListOf<Node>()
    private var strongPayment = false
    private var invalid = false

    fun observe(parent: Int?, id: String?, visible: Boolean, text: CharSequence?, description: CharSequence?) {
        if (nodes.size >= NodeScanBudget.MAX_NODES || (parent != null && parent !in nodes.indices)) {
            invalid = true
            return
        }
        nodes += Node(parent, id, visible)
        if (visible && (isStrongPayment(text) || isStrongPayment(description))) strongPayment = true
    }

    fun recognized(complete: Boolean): Boolean {
        if (!complete || invalid || strongPayment || nodes.isEmpty()) return false
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

    companion object {
        fun isStrongPayment(value: CharSequence?): Boolean = value != null && STRONG.any { value.contains(it, ignoreCase = true) }
        private val STRONG = listOf("确认支付", "收银台", "支付密码", "立即付款", "立即支付", "付款码",
            "提交订单", "确认订单", "订单确认", "确认购买", "购买确认", "pay now", "checkout")
    }
}
