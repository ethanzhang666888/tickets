package local.tickets.companion.pages

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDateTime

/** Stateless, JVM-only observations of the specific native IDs used by tools/android/damai.py. */
class DamaiPageParser {
    fun parse(snapshot: NodeSnapshot, visual: VisualEvidence? = null): PageObservation = Reader(snapshot, visual).parse()

    private class Reader(private val snapshot: NodeSnapshot, private val visual: VisualEvidence?) {
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
            if (visual != null && visual.captureId != snapshot.captureId) {
                blockers += PageBlocker.INCOMPLETE_SNAPSHOT
                return result(PageKind.UNKNOWN)
            }
            val allWords = words(visible) + visual?.words.orEmpty().map { it.text }
            // A verification overlay wins even when underlying native picker/confirmation IDs remain visible.
            if (allWords.any { word -> RISK.any { it in word } }) {
                blockers += PageBlocker.RISK
                return result(PageKind.RISK)
            }
            // A cashier overlay must win over retained confirmation/buyer nodes.
            // Ordinary confirmation labels (确认购买/提交订单) are deliberately not cashier markers.
            if (allWords.any { word -> CASHIER.any { word.contains(it, ignoreCase = true) } }) {
                blockers += PageBlocker.WRONG_PAGE
                return result(PageKind.UNKNOWN)
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
            if (allWords.any { normalized(it) in LOADING } || emptyNativeLoadingShell()) {
                blockers += PageBlocker.LOADING
                return result(PageKind.LOADING)
            }
            if (allWords.any { word -> RESERVATION.any { it in word } }) {
                blockers += PageBlocker.RESERVATION
                return result(PageKind.RESERVATION, title("title_tv", "tv_title"))
            }
            // The measured outer picker/order scroll views may contain fully visible
            // bounded fields. Option lists themselves and unknown scrolling layouts remain blocked.
            if (visible.any { it.scrollable && it.resourceId !in setOf(PREFIX + "preform_scrollview", PREFIX + "recycler_view") &&
                    !boundedDetailScroll(it) })
                blockers += PageBlocker.POTENTIALLY_HIDDEN_CONTENT
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
                if (tiers.any { it.selection.selected() } && quantity !is Observed.Known) blockers += PageBlocker.MISSING_QUANTITY
                return result(PageKind.PICKER, event, sessions, tiers, quantity)
            }
            if (byId(DETAIL_TITLE).isNotEmpty()) {
                val event = title(DETAIL_TITLE)
                if (event !is Observed.Known) blockers += PageBlocker.MISSING_IDENTITY
                val button = unique(DETAIL_BUTTON)
                val labels = button?.let { words(descendants(it)) + visual?.wordsIn(it.bounds).orEmpty() }.orEmpty()
                var state = availability(labels, button != null && snapshot.enabled(button))
                if (state == Availability.AVAILABLE && labels.none { normalized(it) in BUY_LABELS }) state = Availability.UNKNOWN
                return result(PageKind.DETAIL, event, availability = state)
            }
            blockers += PageBlocker.UNKNOWN_PAGE
            return result(PageKind.UNKNOWN)
        }

        /** Measured five-node, textless native shell during order loading. Observation only:
         * never a purchasable page, never OCR, and never a general unknown-page exemption. */
        private fun emptyNativeLoadingShell(): Boolean {
            if (!snapshot.complete || snapshot.nodes.size != 5 || snapshot.nodes.any {
                    it.packageName != PACKAGE || it.text.isNotBlank() || it.description.isNotBlank() ||
                        it.clickable || it.scrollable || it.selected == true || it.checked == true
                }) return false
            val root = snapshot.nodes.singleOrNull { it.parentIndex == null } ?: return false
            val content = snapshot.nodes.singleOrNull { it.resourceId == "android:id/content" } ?: return false
            val arrow = snapshot.nodes.singleOrNull { it.resourceId == PREFIX + "classics_arrow" } ?: return false
            if (snapshot.nodes.any { it.resourceId !in setOf("", "android:id/content", PREFIX + "classics_arrow") }) return false
            return snapshot.ancestors(content).any { it.index == root.index } &&
                snapshot.ancestors(arrow).any { it.index == content.index }
        }

        /** Measured public detail galleries may scroll; identity must be fully visible and
         * the fixed purchase control must remain outside every scrolling subtree. */
        private fun boundedDetailScroll(node: SnapshotNode): Boolean {
            if (byId(SESSION_LIST).isNotEmpty() || byId(TIER_LIST).isNotEmpty() || byId("order_activity_title").isNotEmpty()) return false
            val event = byId(DETAIL_TITLE).singleOrNull() ?: return false
            val buy = byId(DETAIL_BUTTON).singleOrNull() ?: return false
            val content = byId("id_new_project_recycler").singleOrNull() ?: return false
            val parent = node.parentIndex?.let { snapshot.nodes[it] } ?: return false
            if (snapshot.ancestors(event).none { it.index == content.index } ||
                snapshot.ancestors(event).any { !it.bounds.contains(event.bounds) || (it.scrollable && it.index != content.index) } ||
                snapshot.ancestors(buy).any { it.scrollable || !it.bounds.contains(buy.bounds) } ||
                snapshot.ancestors(node).any { !it.bounds.contains(node.bounds) }) return false
            fun outside(a: NodeBounds, b: NodeBounds) = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
            if (!outside(node.bounds, buy.bounds)) return false
            if (node.index == content.index) return parent.resourceId == PREFIX + "id_new_project_refresh"
            if (snapshot.ancestors(node).none { it.index == content.index }) return false
            return when (node.resourceId) {
                PREFIX + "viewpager" -> parent.resourceId == PREFIX + "videoContentContainer" && byId("viewpager").size == 1
                PREFIX + "id_bricks_tour_container" -> parent.resourceId == PREFIX + "faqs_container" && byId("id_bricks_tour_container").size == 1
                "" -> parent.resourceId == PREFIX + "viewpager" && byId("viewpager").size == 1
                else -> false
            }
        }

        private fun options(id: String, session: Boolean): List<TicketOption> {
            val container = unique(id)
            if (container == null) {
                blockers += PageBlocker.UNSUPPORTED_OPTIONS
                return emptyList()
            }
            if (snapshot.ancestors(container).any { !it.bounds.contains(container.bounds) }) {
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
                val nativeLabels = words(cardNodes)
                val labels = (nativeLabels + visual?.wordsIn(card.bounds).orEmpty()).distinctBy(::normalized)
                val candidates = labels.map { raw ->
                    val withoutStatus = (SOLD_OUT + UPCOMING + listOf("暂不可售"))
                        .fold(raw) { value, status -> value.replace(status, "") }.trim()
                    // The measured native session badge is merged into ML Kit's date/time line.
                    // Remove only the trailing, independently corroborated badge, never arbitrary prose.
                    if (session && nativeLabels.any { normalized(it) == "预售" }) withoutStatus.removeSuffix("预售").trim()
                    else withoutStatus
                }.filter { label ->
                    if (session) DATE_LIKE.containsMatchIn(normalized(label)) else '元' in label
                }.distinctBy { label -> if (session) sessionKey(label)?.toString() ?: normalized(label) else tierKey(label) }
                val label = candidates.singleOrNull()
                val valid = label != null && identityLabel(label) && if (session) sessionKey(label) != null else tierMoney(label) != null
                if (!valid) {
                    blockers += PageBlocker.UNSUPPORTED_OPTIONS
                    return@forEach
                }
                val nativeSelected = cardNodes.any { it.selected == true || it.checked == true }
                val borderSelected = visual?.selectedCards?.get(card.index)
                val selection = when {
                    nativeSelected && borderSelected == false -> SelectionEvidence.UNKNOWN
                    nativeSelected -> SelectionEvidence.NATIVE_SELECTED
                    borderSelected == true -> SelectionEvidence.VISUAL_SELECTED
                    borderSelected == false -> SelectionEvidence.VISUAL_UNSELECTED
                    nativeLabels.isEmpty() -> SelectionEvidence.UNKNOWN
                    card.selected == false || card.checked == false -> SelectionEvidence.NATIVE_UNSELECTED
                    else -> SelectionEvidence.UNKNOWN
                }
                options += TicketOption(card.index, label!!, if (session) null else tierMoney(label), selection,
                    availability(labels, snapshot.enabled(card)), card.bounds)
            }
            if (options.isEmpty()) blockers += PageBlocker.UNSUPPORTED_OPTIONS
            val keys = options.map { if (session) sessionKey(it.label).toString() else tierKey(it.label) }
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
            val session = field(labels.filter { Regex("^20[0-9]{2}[-./]").containsMatchIn(it) }) { label ->
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
        private val CASHIER = listOf("收银台", "支付密码", "立即支付", "确认支付", "立即付款", "付款码", "pay now", "checkout")
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
        /** Only the exact inner-floor label has measured simplified/traditional OCR variants.
         * No fuzzy characters, area suffixes, prices, event titles or attendee names are rewritten. */
        internal fun tierKey(value: String): String {
            val n = normalized(value)
            val inner = Regex("[内內][场場]((?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?元)").matchEntire(n)
            return inner?.let { "内场${it.groupValues[1]}" } ?: n
        }
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
