package local.tickets.companion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import local.tickets.companion.pages.DamaiPageParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit operator navigation of public event pages only; never attendee/order/submit controls. */
@Suppress("DEPRECATION")
class PublicEventNavigationDeviceTest {
    @Test fun inspectPublicNavigationAfterClosingStaleDetail() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("explicitPublicNavigation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = automation.serviceInfo
        fun nodes(): List<AccessibilityNodeInfo> {
            val root = checkNotNull(automation.rootInActiveWindow)
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                result += node
                check(result.size < 512 && node.packageName?.toString() == Safety.DAMAI && !node.isPassword)
                check(node.viewIdResourceName != "cn.damai:id/order_activity_title")
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            try { visit(root); return result } catch (e: Exception) { result.forEach { it.recycle() }; throw e }
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            if (InstrumentationRegistry.getArguments().getString("inspectPublicDialog") == "true") {
                val current = nodes()
                try {
                    check(current.size <= 40 && current.any { it.viewIdResourceName == "cn.damai:id/damai_theme_dialog_confirm_btn" })
                    val allowedLabels = setOf("确定", "取消", "关闭", "知道了", "我知道了", "返回", "继续购票", "再想想", "离开", "重试", "刷新")
                    val metadata = current.filter { it.isVisibleToUser && !it.text.isNullOrBlank() }.map { node ->
                        val text = node.text.toString()
                        val category = when {
                            text in allowedLabels -> text
                            listOf("验证", "安全", "风险", "频繁", "环境", "辅助", "无障碍").any { it in text } -> "risk_or_environment_notice"
                            listOf("登录", "账号", "实名", "身份证").any { it in text } -> "account_notice"
                            listOf("网络", "连接", "超时", "繁忙", "异常", "服务").any { it in text } -> "network_or_service_notice"
                            listOf("场次", "票档", "演出", "库存").any { it in text } -> "event_or_stock_notice"
                            else -> "other_unexported"
                        }
                        JSONObject().put("id", node.viewIdResourceName).put("clickable", node.isClickable)
                            .put("enabled", node.isEnabled).put("category", category).put("textLength", text.length)
                            .put("knownKeywords", JSONArray(listOf("登录", "失效", "过期", "账号", "冻结", "安全", "验证", "实名", "身份", "信息", "授权", "绑定", "手机", "风险", "异常", "频繁", "密码", "保险", "协议", "保护", "退出", "重新", "提醒").filter { it in text }))
                    }
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPUBLIC_DIALOG_CATEGORIES=${JSONArray(metadata)}\n") })
                    if (InstrumentationRegistry.getArguments().getString("dismissRealNameNotice") == "true") {
                        val title = current.single { it.viewIdResourceName == "cn.damai:id/damai_theme_dialog_title" && it.isVisibleToUser }
                        check(title.text?.contains("实名") == true && current.none { it.isEditable || it.isPassword })
                        val cancel = current.single { it.viewIdResourceName == "cn.damai:id/damai_theme_dialog_cancel_btn" &&
                            it.text?.toString() == "知道了" && it.isVisibleToUser && it.isEnabled && it.isClickable }
                        val context = instrumentation.context
                        check(context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                        val root = checkNotNull(automation.rootInActiveWindow)
                        try { check(root.packageName?.toString() == Safety.DAMAI && root.windowId == cancel.windowId) }
                        finally { root.recycle() }
                        check(cancel.refresh() && cancel.text?.toString() == "知道了" && cancel.isVisibleToUser && cancel.isEnabled &&
                            cancel.viewIdResourceName == "cn.damai:id/damai_theme_dialog_cancel_btn")
                        check(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nREAL_NAME_NOTICE_CANCEL_DISPATCHED=true\n") })
                    }
                } finally { current.forEach { it.recycle() } }
                return
            }
            if (InstrumentationRegistry.getArguments().getString("closePicker") == "true") {
                val current = nodes()
                try {
                    check(current.none { it.viewIdResourceName == "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}" })
                    val title = current.single { it.viewIdResourceName == "cn.damai:id/tv_title" && it.isVisibleToUser }
                    check(DamaiPageParser.eventKey(title.text?.toString().orEmpty()) == DamaiPageParser.eventKey("郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站"))
                    val back = current.single { it.viewIdResourceName == "cn.damai:id/title_back_btn" && it.isVisibleToUser && it.isEnabled && it.isClickable }
                    check(back.refresh() && back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                } finally { current.forEach { it.recycle() } }
                val deadline = SystemClock.elapsedRealtime() + 3000
                var matched = false
                while (!matched && SystemClock.elapsedRealtime() < deadline) {
                    val currentPage = nodes()
                    try {
                        matched = currentPage.singleOrNull { it.viewIdResourceName == "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}" && it.isVisibleToUser }?.let {
                            DamaiPageParser.eventKey(it.text?.toString().orEmpty()) == DamaiPageParser.eventKey("郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站")
                        } == true
                    } finally { currentPage.forEach { it.recycle() } }
                    if (!matched) SystemClock.sleep(50)
                }
                check(matched) { "Picker back did not restore authorized detail" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nAUTHORIZED_DETAIL_RESTORED_FROM_PICKER=true\n") })
                return
            }
            if (InstrumentationRegistry.getArguments().getString("closeDetail") == "true") {
                val current = nodes()
                try {
                    val title = current.single { it.viewIdResourceName == "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}" && it.isVisibleToUser }
                    check(DamaiPageParser.eventKey(title.text?.toString().orEmpty()) == DamaiPageParser.eventKey("郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站"))
                    val back = current.single { it.viewIdResourceName == "cn.damai:id/project_detail_title_bar_back_btn" && it.isVisibleToUser && it.isEnabled && it.isClickable }
                    check(back.refresh() && back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                } finally { current.forEach { it.recycle() } }
            }
            if (InstrumentationRegistry.getArguments().getString("reenterZhengzhou") == "true") {
                val current = nodes()
                try {
                    val row = current.filter { it.viewIdResourceName == "cn.damai:id/ll_city_tour" && it.isVisibleToUser && it.isEnabled && it.isClickable }
                        .single { candidate ->
                            val cities = candidate.findAccessibilityNodeInfosByViewId("cn.damai:id/tv_city")
                            try { cities.count { it.text?.toString() == "郑州" && it.isVisibleToUser } == 1 } finally { cities.forEach { it.recycle() } }
                        }
                    var ancestor: AccessibilityNodeInfo? = row.parent
                    var verifiedTour = false
                    try {
                        repeat(8) {
                            val node = ancestor ?: return@repeat
                            val titles = node.findAccessibilityNodeInfosByViewId("cn.damai:id/tv_project_tourName")
                            try {
                                verifiedTour = titles.size == 1 && titles.single().text?.toString()?.replace('’', '\'')?.let(DamaiPageParser::normalized) ==
                                    DamaiPageParser.normalized("2026Ella陈嘉桦It's Me艾拉主意巡回演唱会")
                            } finally { titles.forEach { it.recycle() } }
                            if (!verifiedTour) { ancestor = node.parent; node.recycle() }
                        }
                    } finally { ancestor?.recycle() }
                    check(verifiedTour && row.refresh() && row.isVisibleToUser && row.isEnabled)
                    check(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                } finally { current.forEach { it.recycle() } }
                val deadline = SystemClock.elapsedRealtime() + 5000
                var matched = false
                while (!matched && SystemClock.elapsedRealtime() < deadline) {
                    val currentPage = nodes()
                    try {
                        matched = currentPage.singleOrNull { it.viewIdResourceName == "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}" && it.isVisibleToUser }?.let {
                            DamaiPageParser.eventKey(it.text?.toString().orEmpty()) == DamaiPageParser.eventKey("郑州·2026 Ella陈嘉桦It's Me艾拉主意巡回演唱会郑州站")
                        } == true
                    } finally { currentPage.forEach { it.recycle() } }
                    if (!matched) SystemClock.sleep(50)
                }
                check(matched) { "Authorized public detail did not reopen" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nAUTHORIZED_PUBLIC_DETAIL_REOPENED=true\n") })
                return
            }
            val deadline = SystemClock.elapsedRealtime() + 3000
            var result: List<AccessibilityNodeInfo> = emptyList()
            while (SystemClock.elapsedRealtime() < deadline) {
                result = nodes()
                if (result.none { it.viewIdResourceName == "cn.damai:id/${DamaiPageParser.DETAIL_TITLE}" && it.isVisibleToUser }) break
                result.forEach { it.recycle() }; result = emptyList()
                SystemClock.sleep(50)
            }
            try {
                val metadata = result.filter { it.isVisibleToUser && it.viewIdResourceName in setOf("cn.damai:id/header_search_v2_input", "cn.damai:id/tv_project_tourName", "cn.damai:id/tv_city", "cn.damai:id/ll_city_tour") }
                    .map { node ->
                        val parent = node.parent
                        val parentId = try { parent?.viewIdResourceName } finally { parent?.recycle() }
                        JSONObject().put("id", node.viewIdResourceName).put("parentId", parentId).put("class", node.className)
                        .put("clickable", node.isClickable).put("editable", node.isEditable)
                        .put("publicLabel", listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString()).firstOrNull { word ->
                            word in setOf("搜索", "首页", "演出", "全部", "取消", "请输入搜索内容") || word.contains("Ella", true) || word.contains("郑州")
                        } ?: "other") }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPUBLIC_NAVIGATION=$metadata\n") })
            } finally { result.forEach { it.recycle() } }
        } finally { automation.serviceInfo = original }
    }
}
