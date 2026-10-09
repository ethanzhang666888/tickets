package local.tickets.companion.execution

import local.tickets.companion.PickerCaptureEvidence
import local.tickets.companion.Safety
import local.tickets.companion.pages.*

/** Pure counterpart of tools/android/damai.py selected_border; observations, never click targets. */
internal object ExecutionVisualSelection {
    fun selected(width: Int, height: Int, box: NodeBounds, pixel: (Int, Int) -> Int): Boolean {
        if (box.left < 0 || box.top < 0 || box.right > width || box.bottom > height ||
            box.right - box.left < 40 || box.bottom - box.top < 25) return false
        var samples = 0
        var orange = 0
        fun sample(x: Int, y: Int) {
            val rgb = pixel(x, y)
            val r = (rgb ushr 16) and 255
            val g = (rgb ushr 8) and 255
            val b = rgb and 255
            samples++
            if (r > 200 && g > 30 && g < 180 && b < 125) orange++
        }
        for (inset in 1..4) {
            for (x in box.left + 20 until box.right - 20 step 3) {
                sample(x, box.top + inset); sample(x, box.bottom - 1 - inset)
            }
            for (y in box.top + 20 until box.bottom - 20 step 3) {
                sample(box.left + inset, y); sample(box.right - 1 - inset, y)
            }
        }
        return samples > 0 && orange.toDouble() / samples > 0.16
    }
}

/** Strict local allowlist and correlation, independent of Android or OCR availability. */
internal object ExecutionCapturePolicy {
    private const val PREFIX = "cn.damai:id/"
    private val deniedIds = setOf("order_activity_title", "damai_theme_dialog_confirm_btn")
    private val deniedWords = listOf("登录", "登陆", "验证码", "密码", "身份证", "实名验证", "访问异常", "操作过于频繁",
        "安全验证", "滑动验证", "拖动滑块", "账号存在风险", "正在排队", "排队中", "pay now", "checkout")

    fun unsafeWords(words: Iterable<String>, picker: Boolean): Boolean = words.any { word ->
        val compact = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
        deniedWords.any { compact.contains(it.filterNot(Char::isWhitespace), ignoreCase = true) } ||
            PickerCaptureEvidence.isStrongPayment(word) || PickerCaptureEvidence.isStrongPayment(compact) ||
            (!picker && (Safety.isPayment(word) || Safety.isPayment(compact)))
    }

    fun picker(snapshot: NodeSnapshot): Boolean = PickerCaptureEvidence().also { evidence ->
        snapshot.nodes.forEach { evidence.observe(it.parentIndex, it.resourceId, it.visible, it.text, it.description) }
    }.recognized(snapshot.complete)

    fun allowed(snapshot: NodeSnapshot): Boolean {
        if (!snapshot.complete || snapshot.nodes.isEmpty() || snapshot.nodes.count { it.parentIndex == null } != 1 ||
            snapshot.nodes.any { it.packageName != Safety.DAMAI || it.resourceId.removePrefix(PREFIX) in deniedIds }) return false
        val picker = picker(snapshot)
        if (unsafeWords(snapshot.nodes.filter { it.visible }.flatMap { listOf(it.text, it.description) }, picker)) return false
        if (picker) return cards(snapshot) != null
        fun unique(id: String) = snapshot.nodes.filter { it.resourceId == PREFIX + id }.singleOrNull()?.takeIf { snapshot.visible(it) }
        if (snapshot.nodes.any { it.resourceId in setOf(PREFIX + DamaiPageParser.SESSION_LIST, PREFIX + DamaiPageParser.TIER_LIST,
                PREFIX + "preform_scrollview", PREFIX + "btn_buy_view") }) return false
        val title = unique(DamaiPageParser.DETAIL_TITLE) ?: return false
        val buy = unique(DamaiPageParser.DETAIL_BUTTON) ?: return false
        return title.index != buy.index && title.text.isNotBlank() &&
            snapshot.ancestors(title).none { it.index == buy.index } && snapshot.ancestors(buy).none { it.index == title.index }
    }

    /** Every native card is classified, including unselected/disabled cards. No arbitrary child fallback. */
    fun cards(snapshot: NodeSnapshot): List<SnapshotNode>? {
        val result = ArrayList<SnapshotNode>()
        for (id in listOf(DamaiPageParser.SESSION_LIST, DamaiPageParser.TIER_LIST)) {
            val container = snapshot.nodes.filter { it.resourceId == PREFIX + id }.singleOrNull() ?: return null
            if (!snapshot.visible(container) || container.children.isEmpty()) return null
            for (index in container.children) {
                val branch = snapshot.subtree(snapshot.nodes[index])
                val card = branch.filter { it.resourceId == PREFIX + "ll_perform_item" }.singleOrNull() ?: return null
                if (!snapshot.visible(card) || !container.bounds.contains(card.bounds) ||
                    snapshot.ancestors(card).any { !it.bounds.contains(card.bounds) } ||
                    card.bounds.right - card.bounds.left < 40 || card.bounds.bottom - card.bounds.top < 25) return null
                result += card
            }
        }
        if (snapshot.nodes.filter { it.resourceId == PREFIX + "ll_perform_item" }.map { it.index }.toSet() != result.map { it.index }.toSet()) return null
        return result
    }

    /** IDs alone are insufficient. Only the two measured detail pulse leaves may resize,
     * inside their unchanged parent and wholly outside the title and purchase target.
     * Text, actions, ancestry, target geometry and every other node remain exact.
     */
    fun sameSnapshot(expected: NodeSnapshot, actual: NodeSnapshot): Boolean =
        expected.captureId == actual.captureId && samePageContent(expected, actual)

    /** Only the measured pulse region, never a general OCR geometry tolerance. */
    fun detailPulseBounds(snapshot: NodeSnapshot): NodeBounds? {
        if (!allowed(snapshot) || picker(snapshot)) return null
        val title = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + DamaiPageParser.DETAIL_TITLE } ?: return null
        val buy = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + DamaiPageParser.DETAIL_BUTTON } ?: return null
        val pulse = listOf("goto_setinfo_btn_bg", "goto_setinfo_btn_text").map { id ->
            snapshot.nodes.singleOrNull { it.resourceId == PREFIX + id } ?: return null
        }
        if (pulse.map { it.parentIndex }.distinct().size != 1) return null
        val parent = pulse.first().parentIndex?.let { snapshot.nodes[it] } ?: return null
        if (parent.resourceId.isNotEmpty() || parent.index == buy.index || parent.index == title.index) return null
        fun outside(a: NodeBounds, b: NodeBounds) = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
        if (!pulse.all { node -> node.children.isEmpty() && snapshot.visible(node) && snapshot.enabled(node) &&
                node.bounds.right > node.bounds.left && node.bounds.bottom > node.bounds.top &&
                snapshot.ancestors(node).all { it.bounds.contains(node.bounds) } &&
                outside(node.bounds, buy.bounds) && outside(node.bounds, title.bounds) }) return null
        return pulse.first().bounds.takeIf { it.contains(pulse.last().bounds) }
    }

    /** Reserved status-bar inset measured on the detail page; never an arbitrary top crop. */
    fun detailStatusBounds(snapshot: NodeSnapshot): NodeBounds? {
        if (!allowed(snapshot) || picker(snapshot)) return null
        val status = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + "project_detail_title_bar_status_space" } ?: return null
        val screen = snapshot.nodes.single { it.parentIndex == null }.bounds
        // This is an invisible reserved spacer, not a visible/clickable app control.
        if (status.text.isNotBlank() || status.description.isNotBlank() || status.clickable || status.scrollable || status.children.isNotEmpty() ||
            status.bounds.top != screen.top || status.bounds.left != screen.left || status.bounds.right != screen.right ||
            status.bounds.bottom - status.bounds.top !in 1..((screen.bottom - screen.top) / 10)) return null
        val targets = snapshot.nodes.filter { it.resourceId in setOf(PREFIX + DamaiPageParser.DETAIL_TITLE, PREFIX + DamaiPageParser.DETAIL_BUTTON) }
        return status.bounds.takeIf { bounds -> targets.size == 2 && targets.all { it.bounds.top >= bounds.bottom } }
    }

    /** Measured picker spacer (not a guessed top crop), outside all identity/card/buy content. */
    fun pickerStatusBounds(snapshot: NodeSnapshot): NodeBounds? {
        if (!allowed(snapshot) || !picker(snapshot)) return null
        val status = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + "title_bar_space" } ?: return null
        val screen = snapshot.nodes.single { it.parentIndex == null }.bounds
        if (status.visible || status.text.isNotBlank() || status.description.isNotBlank() || status.clickable || status.scrollable ||
            status.children.isNotEmpty() || status.bounds.top != screen.top || status.bounds.left != screen.left || status.bounds.right != screen.right ||
            status.bounds.bottom - status.bounds.top !in 1..((screen.bottom - screen.top) / 10) ||
            snapshot.ancestors(status).none { it.resourceId == PREFIX + "layout_sku" } ||
            snapshot.ancestors(status).any { !it.bounds.contains(status.bounds) }) return null
        val title = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + "tv_title" && snapshot.visible(it) } ?: return null
        val buy = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + "btn_buy_view" && snapshot.visible(it) } ?: return null
        val cards = cards(snapshot) ?: return null
        return status.bounds.takeIf { bounds -> (cards + title + buy).all { it.bounds.top >= bounds.bottom } }
    }

    /** Native NEXT geometry remains exact; only its same-label OCR box may jitter by four pixels. */
    fun pickerNextBounds(snapshot: NodeSnapshot): NodeBounds? {
        if (!allowed(snapshot) || !picker(snapshot)) return null
        val buy = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + "btn_buy_view" } ?: return null
        val ancestors = snapshot.ancestors(buy)
        if (!snapshot.visible(buy) || !snapshot.enabled(buy) || buy.scrollable ||
            buy.bounds.right <= buy.bounds.left || buy.bounds.bottom <= buy.bounds.top ||
            ancestors.none { it.resourceId == PREFIX + "bottom_layout" } ||
            ancestors.any { it.scrollable || !it.bounds.contains(buy.bounds) }) return null
        return buy.bounds
    }

    fun samePageContent(expected: NodeSnapshot, actual: NodeSnapshot): Boolean {
        if (!expected.complete || !actual.complete || expected.nodes.size != actual.nodes.size) return false
        val resized = expected.nodes.zip(actual.nodes).filter { (a, b) -> a.bounds != b.bounds }.map { it.first.index }.toSet()
        val pulseIds = setOf(PREFIX + "goto_setinfo_btn_bg", PREFIX + "goto_setinfo_btn_text")
        if (resized.isNotEmpty() && (resized.any { expected.nodes[it].resourceId !in pulseIds } ||
                detailPulseBounds(expected) == null || detailPulseBounds(actual) == null)) return false
        return expected.nodes.zip(actual.nodes).all { (a, b) ->
            a.parentIndex == b.parentIndex && a.packageName == b.packageName && a.resourceId == b.resourceId &&
                a.text == b.text && a.description == b.description && a.visible == b.visible && a.enabled == b.enabled &&
                a.clickable == b.clickable && a.checked == b.checked && a.selected == b.selected && a.scrollable == b.scrollable
        }
    }
}
