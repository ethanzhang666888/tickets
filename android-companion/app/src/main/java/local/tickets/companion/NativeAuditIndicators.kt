package local.tickets.companion

import org.json.JSONObject

/** Counts structural evidence only; never retains source text, names, document digits or prices. */
class NativeAuditIndicators {
    val structure = NativeAuditStructure()
    private var sessions = 0
    private var tiers = 0
    private var confirmations = 0
    private var dialogs = 0
    private var checkboxes = 0
    private var checked = 0
    private var maskedDocuments = 0
    private var documentTypes = 0
    private var scrollables = 0
    private var submits = 0
    private var paymentLabels = 0
    private var resourceIds = 0
    private var webViews = 0
    private var sessionLabels = 0
    private var tierLabels = 0
    private var appWindows = 0
    private var activeAppWindows = 0
    private var focusedAppWindows = 0
    private var inspectedWindows = 0

    fun observeWindow(samePackage: Boolean, active: Boolean, focused: Boolean, inspected: Boolean) {
        if (!samePackage) return
        appWindows++
        if (active) activeAppWindows++
        if (focused) focusedAppWindows++
        if (inspected) inspectedWindows++
    }
    var cashierDetected = false
        private set

    fun observe(resourceId: String?, text: CharSequence?, description: CharSequence?,
                visible: Boolean, checkable: Boolean, isChecked: Boolean, scrollable: Boolean,
                className: CharSequence? = null) {
        if (!visible) return
        if (resourceId?.startsWith("cn.damai:id/") == true) resourceIds++
        if (className?.toString() == "android.webkit.WebView") webViews++
        when (resourceId) {
            "cn.damai:id/project_detail_perform_flowlayout" -> sessions++
            "cn.damai:id/project_detail_perform_price_flowlayout" -> tiers++
            "cn.damai:id/damai_theme_dialog_confirm_btn" -> dialogs++
            "cn.damai:id/order_activity_title" -> if (text?.toString()?.trim() in setOf("确认购买", "确认订单", "订单确认")) confirmations++
        }
        if (checkable || resourceId == "cn.damai:id/checkbox") { checkboxes++; if (isChecked) checked++ }
        if (scrollable) scrollables++
        val values = listOfNotNull(text?.toString(), description?.toString()).filter { it.length <= 1000 }
        if (values.any { it.contains("场次") }) sessionLabels++
        if (values.any { it.contains("票档") }) tierLabels++
        if (values.any { MASKED_DOCUMENT.containsMatchIn(it) }) maskedDocuments++
        if (values.any { value -> DOCUMENT_TYPES.any { value.contains(it) } }) documentTypes++
        if (values.any { it.trim() in setOf("立即提交", "提交订单") }) submits++
        if (values.any { Safety.isPayment(it) }) paymentLabels++
        if (values.any { value -> CASHIER_MARKERS.any { value.contains(it, ignoreCase = true) } }) cashierDetected = true
    }

    fun json(): JSONObject = JSONObject().apply {
        put("sessionContainerCount", sessions)
        put("tierContainerCount", tiers)
        put("confirmationTitleCount", confirmations)
        put("dialogCount", dialogs)
        put("checkboxCount", checkboxes)
        put("checkedCheckboxCount", checked)
        put("maskedIdentifierNodeCount", maskedDocuments)
        put("documentTypeNodeCount", documentTypes)
        put("scrollableNodeCount", scrollables)
        put("submitButtonCount", submits)
        put("paymentLabelCount", paymentLabels)
        put("resourceIdNodeCount", resourceIds)
        put("webViewNodeCount", webViews)
        put("sessionLabelNodeCount", sessionLabels)
        put("tierLabelNodeCount", tierLabels)
        put("appWindowCount", appWindows)
        put("activeAppWindowCount", activeAppWindows)
        put("focusedAppWindowCount", focusedAppWindows)
        put("inspectedWindowMatchCount", inspectedWindows)
        // Existence/structure counts never establish an exact authorized identity or order.
        put("identityTriplesVerified", false)
        put("executionReady", false)
    }
    companion object {
        private val MASKED_DOCUMENT = Regex("[0-9A-Za-z]{1,8}[*＊•]{2,}[0-9A-Za-z]{1,8}")
        private val CASHIER_MARKERS = listOf("确认支付", "收银台", "支付密码", "立即付款", "立即支付", "付款码", "pay now")
        private val DOCUMENT_TYPES = listOf("身份证", "护照", "港澳居民来往内地通行证", "台湾居民来往大陆通行证")
    }
}
