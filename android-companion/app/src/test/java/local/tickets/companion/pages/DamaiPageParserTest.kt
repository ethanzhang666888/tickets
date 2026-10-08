package local.tickets.companion.pages

import local.tickets.companion.policy.TicketTarget
import org.junit.Assert.*
import org.junit.Test

/** Entirely invented fixtures. No captured page, document, or buyer layout is used. */
class DamaiPageParserTest {
    private val target = TicketTarget("2026-10-18 周日 19:30", "看台380元", 38000)
    private val parser = DamaiPageParser()
    private class Tree {
        val nodes = mutableListOf<NodeSpec>()
        fun add(id: String = "", text: String = "", parent: Int? = null,
                selected: Boolean? = null, checked: Boolean? = null, visible: Boolean = true,
                enabled: Boolean = true, scrollable: Boolean = false,
                description: String = "", pkg: String = "cn.damai"): Int {
            nodes += NodeSpec(parent, pkg, if (id.isEmpty()) "" else "cn.damai:id/$id", text,
                description, visible, enabled, false, checked, selected,
                NodeBounds(0, 0, 600, 900), scrollable)
            return nodes.lastIndex
        }
        fun snapshot(complete: Boolean = true) = NodeSnapshot.create(7, nodes, complete)
    }
    private fun picker(session: String = target.session, tier: String = target.tier,
                       tag: String = "", selected: Boolean? = true,
                       edit: Tree.() -> Unit = {}): Tree = Tree().apply {
        add("tv_title", "测试演出")
        val sessions = add("project_detail_perform_flowlayout")
        val wrapper = add(parent = sessions)
        val s = add("ll_perform_item", parent = wrapper, selected = selected)
        add(text = session, parent = s)
        if (tag.isNotEmpty()) add("tv_tag", tag, s)
        val tiers = add("project_detail_perform_price_flowlayout")
        val t = add("ll_perform_item", parent = tiers, selected = false)
        add(text = tier, parent = t)
        if (tag.isNotEmpty()) add("tv_tag", tag, t)
        val quantity = add("layout_sku_num_ll")
        add("tv_num", "1张", quantity)
        edit()
    }
    private fun confirmation(edit: Tree.(Int, Int) -> Unit = { _, _ -> }): Tree = Tree().apply {
        add("order_activity_title", "确认订单")
        val content = add("recycler_view")
        val header = add(parent = content)
        add(text = "测试演出", parent = header)
        add(text = "2026.10.18 19:30", parent = header)
        add(text = "¥380票档", parent = header)
        add(text = "×1张", parent = header)
        val bottom = add("bottom_layout")
        add(text = "¥380.00", parent = bottom)
        add(text = "提交订单", parent = bottom)
        edit(header, bottom)
    }
    private fun blocked(page: PageObservation, blocker: PageBlocker) = assertTrue(page.blockers.toString(), blocker in page.blockers)

    @Test fun uniqueNativePairMatchesOnlySelectedSessionAndExactTierAndCents() {
        val page = parser.parse(picker().snapshot())
        assertEquals(PageKind.PICKER, page.kind)
        assertTrue(page.blockers.isEmpty())
        assertEquals(1, (page.quantity as Observed.Known).value)
        val match = page.matchTarget("测试演出", target)
        assertTrue(match is TargetMatch.Matched)
        assertEquals(target, (match as TargetMatch.Matched).target)
        assertTrue(page.matchTarget("另一演出", target) is TargetMatch.Blocked)
        assertTrue(page.matchTarget("测试演出", target.copy(unitPriceCents = 38001)) is TargetMatch.Blocked)
        assertTrue(page.matchTarget("测试演出", target.copy(tier = "内场380元")) is TargetMatch.Blocked)
        assertTrue(page.matchTarget("测试演出", target.copy(session = "2026-10-19 19:30")) is TargetMatch.Blocked)
    }

    @Test fun neverCrossProductsTiersWithUnselectedSessions() {
        val tree = picker { val list = nodes.indexOfFirst { it.resourceId.endsWith(":id/project_detail_perform_flowlayout") }
            val card = add("ll_perform_item", parent = list, selected = false)
            add(text = "2026-10-19 19:30", parent = card)
        }
        assertTrue(parser.parse(tree.snapshot()).matchTarget("测试演出", target.copy(session = "2026-10-19 19:30")) is TargetMatch.Blocked)
        assertTrue(parser.parse(picker(selected = null).snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun duplicateCardsEvenWithIdenticalTextAreAmbiguous() {
        for (id in listOf("project_detail_perform_flowlayout", "project_detail_perform_price_flowlayout")) {
            val tree = picker {
                val list = nodes.indexOfFirst { it.resourceId.endsWith(":id/$id") }
                val card = add("ll_perform_item", parent = list, selected = true)
                add(text = if (id.endsWith("price_flowlayout")) target.tier else target.session, parent = card)
            }
            assertTrue(parser.parse(tree.snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
        }
    }

    @Test fun duplicateContainersAndConflictingTitlesNeverFallBack() {
        val duplicate = parser.parse(picker { add("project_detail_perform_price_flowlayout") }.snapshot())
        blocked(duplicate, PageBlocker.AMBIGUOUS_STRUCTURE)
        assertTrue(duplicate.matchTarget("测试演出", target) is TargetMatch.Blocked)
        val titles = parser.parse(picker { add("title_tv", "另一个演出") }.snapshot())
        assertTrue(titles.eventTitle is Observed.Ambiguous)
        assertTrue(titles.matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun missingOrMalformedNativeLabelsAreBlockedNotGuessed() {
        for (tier in listOf("看台380.001元", "看台380元 原价480元", "看台元", "看台0元", "看台1e2元")) {
            assertTrue(parser.parse(picker(tier = tier).snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
        }
        for (session in listOf("10-18 19:30", "2026-02-30 19:30", "2026-10-18 25:30", "2026-10-18 19:30/20:30")) {
            assertTrue(parser.parse(picker(session = session).snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
        }
    }

    @Test fun unknownCardSiblingAndMultipleLabelsBlockWholeList() {
        val tree = picker {
            val list = nodes.indexOfFirst { it.resourceId.endsWith(":id/project_detail_perform_price_flowlayout") }
            add(text = "unrecognized self drawn card", parent = list)
        }
        blocked(parser.parse(tree.snapshot()), PageBlocker.UNSUPPORTED_OPTIONS)
        val mixed = picker {
            val card = nodes.indexOfLast { it.resourceId.endsWith(":id/ll_perform_item") }
            add(text = "内场580元", parent = card)
        }
        assertTrue(parser.parse(mixed.snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun quantityRequiresUniqueDirectChildInKnownContainer() {
        for (value in listOf("0张", "01张", "21张", "1", "1.5张")) {
            val tree = picker()
            val i = tree.nodes.indexOfFirst { it.resourceId.endsWith(":id/tv_num") }
            tree.nodes[i] = tree.nodes[i].copy(text = value)
            assertFalse(parser.parse(tree.snapshot()).quantity is Observed.Known)
        }
        val duplicate = picker {
            val row = nodes.indexOfFirst { it.resourceId.endsWith(":id/layout_sku_num_ll") }
            add("tv_num", "1张", row)
        }
        assertTrue(parser.parse(duplicate.snapshot()).quantity is Observed.Ambiguous)
        val unrelated = Tree().apply { add("tv_title", "测试演出"); add("project_detail_perform_flowlayout"); add("tv_num", "2张") }
        assertFalse(parser.parse(unrelated.snapshot()).quantity is Observed.Known)
    }

    @Test fun availabilityIsConservativeAndSoldOutWins() {
        for ((tag, state) in listOf("缺货登记" to Availability.SOLD_OUT, "未开售" to Availability.UPCOMING,
            "开售提醒" to Availability.UPCOMING, "暂不可售" to Availability.UNAVAILABLE,
            "缺货登记 开售提醒" to Availability.SOLD_OUT)) {
            val page = parser.parse(picker(tag = tag).snapshot())
            assertEquals(state, page.tiers.single().availability)
            assertTrue(page.matchTarget("测试演出", target) is TargetMatch.Blocked)
        }
    }

    @Test fun disabledAncestorAndInvisibleCardCannotBecomeUsableTargets() {
        val disabled = picker()
        val list = disabled.nodes.indexOfFirst { it.resourceId.endsWith(":id/project_detail_perform_price_flowlayout") }
        disabled.nodes[list] = disabled.nodes[list].copy(enabled = false)
        assertTrue(parser.parse(disabled.snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
        disabled.nodes[list] = disabled.nodes[list].copy(enabled = true, visible = false)
        assertTrue(parser.parse(disabled.snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun scrollableOrTruncatedSnapshotCannotProveUniqueCandidates() {
        val tree = picker()
        val list = tree.nodes.indexOfFirst { it.resourceId.endsWith(":id/project_detail_perform_price_flowlayout") }
        tree.nodes[list] = tree.nodes[list].copy(scrollable = true)
        blocked(parser.parse(tree.snapshot()), PageBlocker.POTENTIALLY_HIDDEN_CONTENT)
        assertTrue(parser.parse(tree.snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
        val truncated = parser.parse(picker().snapshot(false))
        blocked(truncated, PageBlocker.INCOMPLETE_SNAPSHOT)
        assertTrue(truncated.matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun knownDetailButtonLabelsAreObservationsNotPurchasePermission() {
        for ((label, state) in listOf("立即购买" to Availability.AVAILABLE, "开售提醒" to Availability.UPCOMING,
            "缺货登记" to Availability.SOLD_OUT, "陌生入口" to Availability.UNKNOWN)) {
            val page = parser.parse(Tree().apply {
                add("info_v2_title_tv1", "测试演出")
                val button = add("trade_project_detail_purchase_status_bar_container_fl")
                add(text = label, parent = button)
            }.snapshot())
            assertEquals(PageKind.DETAIL, page.kind)
            assertEquals(state, page.detailAvailability)
            assertTrue(page.matchTarget("测试演出", target) is TargetMatch.Blocked)
        }
    }

    @Test fun reservationOverridesPickerAndRiskOverridesEveryNativePage() {
        val reservation = parser.parse(picker { add(text = "预约想看场次") }.snapshot())
        assertEquals(PageKind.RESERVATION, reservation.kind)
        assertTrue(reservation.matchTarget("测试演出", target) is TargetMatch.Blocked)
        for (tree in listOf(picker(), confirmation(), Tree().apply { add(text = "正在排队") })) {
            tree.add(description = "请完成安全验证")
            val page = parser.parse(tree.snapshot())
            assertEquals(PageKind.RISK, page.kind)
            assertTrue(page.sessions.isEmpty())
            assertNull(page.confirmation)
            blocked(page, PageBlocker.RISK)
        }
    }

    @Test fun queueAndLoadingOverlayOverridePicker() {
        assertEquals(PageKind.QUEUE, parser.parse(picker { add(text = "正在排队") }.snapshot()).kind)
        assertEquals(PageKind.LOADING, parser.parse(picker { add(text = "加载中") }.snapshot()).kind)
        blocked(parser.parse(picker { add("damai_theme_dialog_confirm_btn", "确认并知悉") }.snapshot()), PageBlocker.DIALOG)
    }

    @Test fun nativeConfirmationExtractsPartialFieldsButCannotAuthorizeOrder() {
        val page = parser.parse(confirmation().snapshot())
        assertEquals(PageKind.CONFIRMATION, page.kind)
        val c = page.confirmation!!
        assertEquals("测试演出", (c.eventTitle as Observed.Known).value)
        assertEquals("2026.10.18 19:30", (c.session as Observed.Known).value)
        assertEquals(38000L, (c.unitPriceCents as Observed.Known).value)
        assertEquals(38000L, (c.totalCents as Observed.Known).value)
        assertEquals(1, (c.quantity as Observed.Known).value)
        assertTrue(c.tierIdentity is Observed.Unsupported)
        assertTrue(c.feesCents is Observed.Unsupported)
        assertTrue(c.buyers is Observed.Unsupported)
        assertFalse(c.buyerListComplete)
        blocked(page, PageBlocker.UNSUPPORTED_BUYER_STRUCTURE)
        blocked(page, PageBlocker.UNSUPPORTED_FEE_STRUCTURE)
        blocked(page, PageBlocker.UNSUPPORTED_TIER_IDENTITY)
        assertTrue(page.matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun matchingTotalNeverImpliesZeroFeesAndArbitraryFeeLabelIsUnsupported() {
        for (fee in listOf("", "服务费 ¥0.00", "服务费 ¥5.00", "免服务费")) {
            val page = parser.parse(confirmation { header, _ -> if (fee.isNotEmpty()) add(text = fee, parent = header) }.snapshot())
            assertTrue(page.confirmation!!.feesCents is Observed.Unsupported)
        }
    }

    @Test fun sameNameDifferentMaskedIdsDuplicateFullAndIncompleteRowsRemainUnsupported() {
        val rows = listOf(
            listOf("张甲 身份证 110***********1234", "张甲 身份证 110***********5678"),
            listOf("张甲 身份证 110***********1234", "张甲 身份证 110***********1234"),
            listOf("张甲 身份证 110123456789001234"), listOf("张甲 身份证"),
            listOf("张甲 身份证 ****************"), emptyList()
        )
        for (labels in rows) {
            val page = parser.parse(confirmation { _, _ ->
                val guessedList = add("invented_buyers")
                labels.forEach { label ->
                    val row = add("invented_row", label, guessedList)
                    add("checkbox", parent = row, checked = true)
                }
            }.snapshot())
            assertTrue(page.confirmation!!.buyers is Observed.Unsupported)
            assertFalse(page.confirmation.buyerListComplete)
            assertFalse(page.toString().contains("张甲"))
        }
    }

    @Test fun potentiallyScrollHiddenBuyersAreExplicitlyBlocked() {
        val page = parser.parse(confirmation { _, _ -> add("invented_buyers", scrollable = true) }.snapshot())
        blocked(page, PageBlocker.POTENTIALLY_HIDDEN_CONTENT)
        assertFalse(page.confirmation!!.buyerListComplete)
    }

    @Test fun confirmationRejectsDuplicateAndOverPreciseAmounts() {
        val duplicate = parser.parse(confirmation { _, bottom -> add(text = "¥380.00", parent = bottom) }.snapshot())
        assertTrue(duplicate.confirmation!!.totalCents is Observed.Ambiguous)
        val precision = confirmation()
        val total = precision.nodes.indexOfFirst { it.text == "¥380.00" }
        precision.nodes[total] = precision.nodes[total].copy(text = "¥380.001")
        assertFalse(parser.parse(precision.snapshot()).confirmation!!.totalCents is Observed.Known)
        val mixed = parser.parse(confirmation { header, _ -> add(text = "2026.10.19 19:30", parent = header) }.snapshot())
        assertTrue(mixed.confirmation!!.session is Observed.Ambiguous)
    }

    @Test fun paymentMethodOnConfirmationIsNotActualPaymentAndForeignPaymentIsUnknown() {
        val native = parser.parse(confirmation { _, _ ->
            val row = add("rl_pay")
            add("tv_pay_title", "支付宝", row)
            add("cb_pay_check", "\ue690", row)
        }.snapshot())
        assertEquals(PageKind.CONFIRMATION, native.kind)
        val foreign = parser.parse(Tree().apply { add("order_activity_title", "确认订单", pkg = "com.eg.android.AlipayGphone") }.snapshot())
        assertEquals(PageKind.UNKNOWN, foreign.kind)
        blocked(foreign, PageBlocker.FOREIGN_PACKAGE)
    }

    @Test fun arbitraryConfirmationTextAndSelfDrawnNativeContentStayUnknown() {
        for (tree in listOf(Tree().apply { add(text = "提交订单"); add(text = "观演人") }, Tree().apply { add() })) {
            val page = parser.parse(tree.snapshot())
            assertEquals(PageKind.UNKNOWN, page.kind)
            assertNull(page.confirmation)
            assertTrue(page.matchTarget("测试演出", target) is TargetMatch.Blocked)
        }
    }

    @Test fun sensitiveHoldersAndAuditDoNotEchoRawText() {
        val tree = picker()
        tree.add(text = "synthetic-private-name-1234")
        val snapshot = tree.snapshot()
        val page = parser.parse(snapshot)
        for (value in listOf(tree.nodes.last(), snapshot, snapshot.nodes.last(), page, page.eventTitle,
            page.sessions.single(), page.matchTarget("测试演出", target), page.audit())) {
            assertFalse(value.toString().contains("synthetic-private"))
            assertFalse(value.toString().contains("测试演出"))
            assertFalse(value.toString().contains("380元"))
        }
        assertEquals(PageKind.PICKER, page.audit().kind)
        assertEquals(1, page.audit().sessionCount)
        assertEquals(1, page.audit().tierCount)
    }

    @Test fun snapshotIsBoundedImmutableAndDerivesConsistentParentChildren() {
        val tree = Tree().apply { val root = add(); add(text = "original", parent = root) }
        val snapshot = tree.snapshot()
        tree.nodes[1] = tree.nodes[1].copy(text = "changed")
        assertEquals("original", snapshot.nodes[1].text)
        assertEquals(listOf(1), snapshot.nodes[0].children)
        assertEquals(0, snapshot.nodes[1].parentIndex)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.nodes as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.nodes[0].children as MutableList<*>).clear() }
        assertThrows(IllegalArgumentException::class.java) { NodeSnapshot.create(1, listOf(NodeSpec(parentIndex = 0))) }
        assertThrows(IllegalArgumentException::class.java) { NodeSnapshot.create(1, List(NodeSnapshot.MAX_NODES + 1) { NodeSpec() }) }
        assertThrows(IllegalArgumentException::class.java) { NodeSnapshot.create(1, listOf(NodeSpec(text = "x".repeat(NodeSnapshot.MAX_TEXT_LENGTH + 1)))) }
        assertThrows(IllegalArgumentException::class.java) { NodeSnapshot.create(1, List(NodeSnapshot.MAX_DEPTH + 1) { NodeSpec(parentIndex = if (it == 0) null else it - 1) }) }
    }

    @Test fun mixedKnownPageFamiliesAndClippedAncestorsBlockMatching() {
        val mixed = parser.parse(picker { add("info_v2_title_tv1", "另一演出") }.snapshot())
        assertTrue(mixed.matchTarget("测试演出", target) is TargetMatch.Blocked)
        val tree = picker()
        val list = tree.nodes.indexOfFirst { it.resourceId.endsWith(":id/project_detail_perform_price_flowlayout") }
        // Insert a clipped parent before the list, then remap all old indices.
        tree.nodes.add(list, NodeSpec(bounds = NodeBounds(0, 0, 100, 100)))
        for (i in list + 1 until tree.nodes.size) {
            val node = tree.nodes[i]
            tree.nodes[i] = node.copy(parentIndex = when {
                i == list + 1 -> list
                node.parentIndex == null -> null
                node.parentIndex >= list -> node.parentIndex + 1
                else -> node.parentIndex
            })
        }
        val clipped = parser.parse(tree.snapshot())
        blocked(clipped, PageBlocker.POTENTIALLY_HIDDEN_CONTENT)
        assertTrue(clipped.matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun unknownOrMultipleTierSelectionsCannotBeMatched() {
        val unknown = picker()
        val card = unknown.nodes.indexOfLast { it.resourceId.endsWith(":id/ll_perform_item") }
        unknown.nodes[card] = unknown.nodes[card].copy(selected = null)
        assertTrue(parser.parse(unknown.snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
        val multiple = picker {
            val selectedTier = nodes.indexOfLast { it.resourceId.endsWith(":id/ll_perform_item") }
            nodes[selectedTier] = nodes[selectedTier].copy(selected = true)
            val list = nodes.indexOfFirst { it.resourceId.endsWith(":id/project_detail_perform_price_flowlayout") }
            val other = add("ll_perform_item", parent = list, selected = true)
            add(text = "内场580元", parent = other)
        }
        assertTrue(parser.parse(multiple.snapshot()).matchTarget("测试演出", target) is TargetMatch.Blocked)
    }

    @Test fun visibleAmountAloneCannotSubstituteForAnotherTierIdentity() {
        val page = parser.parse(picker {
            val list = nodes.indexOfFirst { it.resourceId.endsWith(":id/project_detail_perform_price_flowlayout") }
            val other = add("ll_perform_item", parent = list, selected = false)
            add(text = "内场380元", parent = other)
        }.snapshot())
        assertTrue(page.matchTarget("测试演出", target) is TargetMatch.Matched)
        assertTrue(page.matchTarget("测试演出", target.copy(tier = "站票380元")) is TargetMatch.Blocked)
    }

    @Test fun snapshotTotalTextBudgetAndAuditCollectionsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeSnapshot.create(1, List(70) { NodeSpec(text = "x".repeat(1024)) })
        }
        val page = parser.parse(picker().snapshot())
        assertThrows(UnsupportedOperationException::class.java) { (page.sessions as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (page.blockers as MutableSet<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (page.audit().blockers as MutableSet<*>).clear() }
        assertFalse(page.matchTarget("测试演出", target).toString().contains("2026"))
    }

    @Test fun moneyUsesExactIntegerCentsAndRejectsLossyFormats() {
        assertEquals(38000L, DamaiPageParser.moneyCents("380"))
        assertEquals(38010L, DamaiPageParser.moneyCents("380.1"))
        assertEquals(38001L, DamaiPageParser.moneyCents("380.01"))
        assertEquals(0L, DamaiPageParser.moneyCents("0", allowZero = true))
        for (value in listOf("0", "-1", "+1", "1.000", "1.001", "1e2", "NaN", "1,000", "01", "999999999999999999999")) {
            assertNull(DamaiPageParser.moneyCents(value))
        }
    }
}
