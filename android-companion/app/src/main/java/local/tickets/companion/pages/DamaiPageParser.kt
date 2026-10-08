package local.tickets.companion.pages

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDateTime

/** Stateless, JVM-only observations of the specific native IDs used by tools/android/damai.py. */
class DamaiPageParser {
    fun parse(snapshot: NodeSnapshot): PageObservation = Reader(snapshot).parse()

    private class Reader(private val snapshot: NodeSnapshot) {
        private val visible = snapshot.nodes.filter { snapshot.visible(it) }
        private val native = visible.filter { it.packageName == PACKAGE }
        private val blockers = linkedSetOf<PageBlocker>()
        private fun byId(id: String) = native.filter { it.resourceId == PREFIX + id }
        private fun words(nodes: List<SnapshotNode>): List<String> = nodes.flatMap { node ->
            listOf(node.text, node.description).filter { it.isNotBlank() }.map { it.trim() }.distinct()
        }
        private fun descendants(node: SnapshotNode) = snapshot.subtree(node).filter { snapshot.visible(it) && it.packageName == PACKAGE }
        private fun unique(id: String): SnapshotNode? {
            val nodes = byId(id)
            if (nodes.size > 1) blockers += PageBlocker.AMBIGUOUS_STRUCTURE
            return nodes.singleOrNull()
        }
        private fun title(vararg ids: String): Observed<String> {
            val nodes = ids.flatMap { byId(it) }
            if (nodes.size > 1) return Observed.Ambiguous.also { blockers += PageBlocker.AMBIGUOUS_STRUCTURE }
            val labels = words(nodes)
            return when {
                labels.size > 1 -> Observed.Ambiguous
                labels.size == 1 && identityLabel(labels.single()) -> Observed.Known(labels.single())
                else -> Observed.Missing
            }
        }
        private fun result(kind: PageKind, event: Observed<String> = Observed.Missing,
                           sessions: List<TicketOption> = emptyList(), tiers: List<TicketOption> = emptyList(),
                           quantity: Observed<Int> = Observed.Missing, availability: Availability = Availability.UNKNOWN,
                           confirmation: ConfirmationFields? = null) = PageObservation(
            snapshot.captureId, kind, event, sessions, tiers, quantity, availability, confirmation, blockers, snapshot.nodes.size)

        fun parse(): PageObservation {
            if (!snapshot.complete) blockers += PageBlocker.INCOMPLETE_SNAPSHOT
            val allWords = words(visible)
            // A verification overlay wins even when underlying native picker/confirmation IDs remain visible.
            if (allWords.any { word -> RISK.any { it in word } }) {
                blockers += PageBlocker.RISK
                return result(PageKind.RISK)
            }
            if (visible.any { it.packageName != PACKAGE }) {
                blockers += PageBlocker.FOREIGN_PACKAGE
                return result(PageKind.UNKNOWN)
            }
            if (byId("damai_theme_dialog_confirm_btn").isNotEmpty()) blockers += PageBlocker.DIALOG
            if (allWords.any { word -> QUEUE.any { it in word } }) {
                blockers += PageBlocker.QUEUE
                return result(PageKind.QUEUE)
            }
            if (allWords.any { normalized(it) in LOADING }) {
                blockers += PageBlocker.LOADING
                return result(PageKind.LOADING)
            }
            if (allWords.any { word -> RESERVATION.any { it in word } }) {
                blockers += PageBlocker.RESERVATION
                return result(PageKind.RESERVATION, title("title_tv", "tv_title"))
            }
            if (visible.any { it.scrollable }) blockers += PageBlocker.POTENTIALLY_HIDDEN_CONTENT
            val orderTitles = byId("order_activity_title")
            if (words(orderTitles).any { it in CONFIRMATION_TITLES }) {
                if (orderTitles.size != 1 || words(orderTitles).size != 1) blockers += PageBlocker.AMBIGUOUS_STRUCTURE
                if (byId(SESSION_LIST).isNotEmpty() || byId(TIER_LIST).isNotEmpty()) blockers += PageBlocker.AMBIGUOUS_STRUCTURE
                return confirmation()
            }
            if (byId(SESSION_LIST).isNotEmpty() || byId(TIER_LIST).isNotEmpty()) {
                if (byId(DETAIL_TITLE).isNotEmpty() || orderTitles.isNotEmpty()) blockers += PageBlocker.AMBIGUOUS_STRUCTURE
                val event = title("tv_title", "tv_sku_state_view_base_info_title", "title_tv")
                if (event !is Observed.Known) blockers += PageBlocker.MISSING_IDENTITY
                val sessions = options(SESSION_LIST, session = true)
                val tiers = options(TIER_LIST, session = false)
                val quantity = quantity()
                if (quantity !is Observed.Known) blockers += PageBlocker.MISSING_QUANTITY
                return result(PageKind.PICKER, event, sessions, tiers, quantity)
            }
            if (byId(DETAIL_TITLE).isNotEmpty()) {
                val event = title(DETAIL_TITLE)
                if (event !is Observed.Known) blockers += PageBlocker.MISSING_IDENTITY
                val button = unique(DETAIL_BUTTON)
                val labels = button?.let { words(descendants(it)) }.orEmpty()
                var state = availability(labels, button != null && snapshot.enabled(button))
                if (state == Availability.AVAILABLE && labels.none { normalized(it) in BUY_LABELS }) state = Availability.UNKNOWN
                return result(PageKind.DETAIL, event, availability = state)
            }
            blockers += PageBlocker.UNKNOWN_PAGE
            return result(PageKind.UNKNOWN)
        }

        private fun options(id: String, session: Boolean): List<TicketOption> {
            val container = unique(id)
            if (container == null) {
                blockers += PageBlocker.UNSUPPORTED_OPTIONS
                return emptyList()
            }
            if (snapshot.subtree(container).any { !snapshot.visible(it) } ||
                snapshot.ancestors(container).any { !it.bounds.contains(container.bounds) }) {
                blockers += PageBlocker.POTENTIALLY_HIDDEN_CONTENT
            }
            val options = ArrayList<TicketOption>()
            container.children.forEach { childIndex ->
                val child = snapshot.nodes[childIndex]
                val branch = descendants(child)
                val cards = branch.filter { it.resourceId == PREFIX + "ll_perform_item" }
                // No desktop fallback to an arbitrary child: self-drawn or unverified card layouts stay unsupported.
                val card = cards.singleOrNull()
                if (card == null || !container.bounds.contains(card.bounds)) {
                    blockers += PageBlocker.UNSUPPORTED_OPTIONS
                    return@forEach
                }
                val cardNodes = descendants(card)
                if (branch.any { it !in cardNodes && words(listOf(it)).isNotEmpty() }) {
                    blockers += PageBlocker.UNSUPPORTED_OPTIONS
                    return@forEach
                }
                if (cardNodes.any { !card.bounds.contains(it.bounds) }) blockers += PageBlocker.POTENTIALLY_HIDDEN_CONTENT
                val labels = words(cardNodes)
                val candidates = labels.filter { label ->
                    if (session) DATE_LIKE.containsMatchIn(normalized(label)) else '元' in label
                }
                val label = candidates.singleOrNull()
                val valid = label != null && identityLabel(label) && if (session) sessionKey(label) != null else tierMoney(label) != null
                if (!valid) {
                    blockers += PageBlocker.UNSUPPORTED_OPTIONS
                    return@forEach
                }
                val selection = when {
                    cardNodes.any { it.selected == true || it.checked == true } -> SelectionEvidence.NATIVE_SELECTED
                    card.selected == false || card.checked == false -> SelectionEvidence.NATIVE_UNSELECTED
                    else -> SelectionEvidence.UNKNOWN
                }
                options += TicketOption(card.index, label!!, if (session) null else tierMoney(label), selection,
                    availability(labels, snapshot.enabled(card)), card.bounds)
            }
            if (options.isEmpty()) blockers += PageBlocker.UNSUPPORTED_OPTIONS
            val keys = options.map { if (session) sessionKey(it.label).toString() else normalized(it.label) }
            if (keys.distinct().size != keys.size) blockers += PageBlocker.AMBIGUOUS_STRUCTURE
            return options
        }

        private fun quantity(): Observed<Int> {
            val containers = byId("layout_sku_num_ll")
            if (containers.size > 1) return Observed.Ambiguous
            val row = containers.singleOrNull() ?: return Observed.Missing
            val counts = row.children.map { snapshot.nodes[it] }
                .filter { snapshot.visible(it) && it.packageName == PACKAGE && it.resourceId == PREFIX + "tv_num" }
            if (counts.size > 1) return Observed.Ambiguous
            return field(words(counts)) { label ->
                Regex("([1-9][0-9]?)张").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= 20 }
            }
        }

        private fun confirmation(): PageObservation {
            blockers += PageBlocker.UNSUPPORTED_BUYER_STRUCTURE
            blockers += PageBlocker.UNSUPPORTED_FEE_STRUCTURE
            blockers += PageBlocker.UNSUPPORTED_TIER_IDENTITY
            val content = unique("recycler_view")
            val bottom = unique("bottom_layout")
            // Only the first native child is the known desktop order header. No broad text search for identity.
            val header = content?.children?.firstOrNull()?.let { snapshot.nodes[it] }
            val labels = header?.let { words(descendants(it)) }.orEmpty()
            val event = labels.firstOrNull()?.takeIf { identityLabel(it) && !DATE_LIKE.containsMatchIn(it) &&
                !it.startsWith("¥") && !it.startsWith("￥") && !it.startsWith("×") && !it.startsWith("x") }
                ?.let { Observed.Known(it) } ?: Observed.Missing
            val session = field(labels.filter { DATE_LIKE.containsMatchIn(normalized(it)) }) { label ->
                label.takeIf { CONFIRMATION_SESSION.matches(it) && sessionKey(it) != null }
            }
            val unit = field(labels.filter { "票档" in it }) { label ->
                CONFIRMATION_TIER.matchEntire(label)?.groupValues?.get(1)?.let { moneyCents(it) }
            }
            val quantity = field(labels.filter { it.startsWith("×") || it.startsWith("x") }) { label ->
                Regex("[×x]\\s*([1-9][0-9]?)张").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= 20 }
            }
            val totals = bottom?.let { words(descendants(it)) }.orEmpty().filter { it.startsWith("¥") || it.startsWith("￥") }
            val total = field(totals) { label ->
                Regex("[￥¥]\\s*([0-9]+(?:\\.[0-9]+)?)").matchEntire(label)?.groupValues?.get(1)?.let { moneyCents(it) }
            }
            if (content == null || bottom == null || header == null ||
                listOf(event, session, unit, quantity, total).any { it !is Observed.Known }) blockers += PageBlocker.INCOMPLETE_CONFIRMATION
            val fields = ConfirmationFields(event, session, unit, quantity, total)
            return result(PageKind.CONFIRMATION, event, quantity = quantity, confirmation = fields)
        }
    }

    companion object {
        const val PACKAGE = "cn.damai"
        const val PREFIX = "$PACKAGE:id/"
        const val DETAIL_TITLE = "info_v2_title_tv1"
        const val DETAIL_BUTTON = "trade_project_detail_purchase_status_bar_container_fl"
        const val SESSION_LIST = "project_detail_perform_flowlayout"
        const val TIER_LIST = "project_detail_perform_price_flowlayout"
        private val SOLD_OUT = listOf("缺货登记", "缺货", "无票", "售罄", "售完")
        private val UPCOMING = listOf("未开售", "待开售", "开售提醒", "预约抢票", "预约想看", "立即预约", "已预约")
        private val RISK = listOf("访问异常", "操作过于频繁", "请完成安全验证", "滑动验证", "拖动滑块", "账号存在风险")
        private val QUEUE = listOf("正在排队", "排队中，请稍候", "前方排队人数")
        private val RESERVATION = listOf("预约想看场次", "预约想看票档", "提前预约想看的场次")
        // Exact lexical loading observations only; no unverified native loading resource IDs are invented.
        private val LOADING = setOf("加载中", "正在加载", "加载中...", "正在加载...", "加载中…", "正在加载…")
        private val BUY_LABELS = setOf("立即购票", "立即购买", "立即预订", "选票购买", "立即抢票")
        private val CONFIRMATION_TITLES = setOf("确认购买", "确认订单", "订单确认")
        private val DATE_LIKE = Regex("(?:20[0-9]{2}[-./])?[0-9]{1,2}[-./][0-9]{1,2}")
        private val SESSION = Regex("[休班]?(20[0-9]{2})[-./]([0-9]{2})[-./]([0-9]{2})(?:周[一二三四五六日天]|星期[一二三四五六日天])?([0-9]{1,2}):([0-9]{2})")
        private val CONFIRMATION_SESSION = Regex("20[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}\\s+[0-9]{2}:[0-9]{2}")
        private val CONFIRMATION_TIER = Regex("[￥¥]\\s*([0-9]+(?:\\.[0-9]+)?)票档")
        private fun identityLabel(value: String) = value.isNotBlank() && value.length <= 300 && value.none { it.isISOControl() }
        internal fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
        internal fun eventKey(value: String) = normalized(value).replace('•', '·').replace(Regex("^【([^】]+)】"), "$1·")
        internal fun sessionKey(value: String): LocalDateTime? {
            val match = SESSION.matchEntire(normalized(value)) ?: return null
            return try {
                val n = match.groupValues.drop(1).map { it.toInt() }
                LocalDateTime.of(n[0], n[1], n[2], n[3], n[4])
            } catch (_: DateTimeException) { null }
        }
        private fun tierMoney(value: String): Long? {
            // One amount only, with an optional nonnumeric name; never prices embedded in prose or promotions.
            val match = Regex("[^0-9.￥¥+\\-]*?((?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?)元").matchEntire(normalized(value)) ?: return null
            return moneyCents(match.groupValues[1])
        }
        /** Exact base-ten conversion. No float, exponent, grouping, rounding or 3-decimal truncation. */
        fun moneyCents(value: String, allowZero: Boolean = false): Long? {
            if (!Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?").matches(value)) return null
            val parts = value.split('.')
            val whole = parts[0].toLongOrNull() ?: return null
            val fraction = if (parts.size == 1) 0L else parts[1].padEnd(2, '0').toLong()
            if (whole > (Long.MAX_VALUE - fraction) / 100) return null
            return (whole * 100 + fraction).takeIf { it > 0 || (allowZero && it == 0L) }
        }
        private fun availability(words: List<String>, enabled: Boolean): Availability = when {
            words.any { word -> SOLD_OUT.any { it in word } } -> Availability.SOLD_OUT
            words.any { word -> UPCOMING.any { it in word } } -> Availability.UPCOMING
            !enabled || words.any { "暂不可售" in it } -> Availability.UNAVAILABLE
            else -> Availability.AVAILABLE
        }
        private fun <T> field(candidates: List<String>, parse: (String) -> T?): Observed<T> = when {
            candidates.size > 1 -> Observed.Ambiguous
            candidates.isEmpty() -> Observed.Missing
            else -> parse(candidates.single())?.let { Observed.Known(it) } ?: Observed.Unsupported
        }
    }
}
