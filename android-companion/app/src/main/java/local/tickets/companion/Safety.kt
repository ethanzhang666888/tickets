package local.tickets.companion

import java.security.MessageDigest
import org.json.JSONObject

object Safety {
    const val DAMAI = "cn.damai"
    const val OWN = "local.tickets.companion"
    private val payments = listOf("支付", "收银台", "付款", "确认订单", "提交订单", "订单确认", "确认购买", "购买确认", "pay now", "checkout")
    private val candidates = listOf("立即购买", "立即预订", "选座购买", "选择场次", "选择票档", "选购", "预约抢票", "开售提醒")
    fun isPayment(text: CharSequence?): Boolean = text != null && payments.any { text.contains(it, ignoreCase = true) }
    fun isCandidate(text: CharSequence?): Boolean = text != null && candidates.any { text.contains(it) }
    fun pageKind(texts: Iterable<String>, pickerLayoutRecognized: Boolean = false): String {
        var selection = pickerLayoutRecognized
        var detail = false
        for (text in texts) {
            if (isPayment(text) && (!pickerLayoutRecognized || PickerCaptureEvidence.isStrongPayment(text))) return "payment"
            selection = selection || text.contains("票档") || text.contains("场次")
            detail = detail || isCandidate(text)
        }
        return if (selection) "selection" else if (detail) "detail" else "unknown"
    }
    fun canInspect(pkg: String?, locked: Boolean, accessibility: Boolean, payment: Boolean): Boolean =
        accessibility && !locked && !payment && (pkg == DAMAI || pkg == OWN)
    fun captureMatches(pkg: String?, expectedSynthetic: Boolean, syntheticVisible: Boolean): Boolean =
        if (expectedSynthetic) pkg == OWN && syntheticVisible else pkg == DAMAI
    fun canClickTest(pkg: String?, matches: Int, clickable: Boolean, visible: Boolean): Boolean =
        pkg == OWN && matches == 1 && clickable && visible
}

data class SafeSummary(
    val pageKind: String = "unknown",
    val nodeCount: Int = 0,
    val textNodeCount: Int = 0,
    val clickableCount: Int = 0,
    val candidateCount: Int = 0,
    val nativeMs: Long = 0,
    val truncated: Boolean = false,
    val screenshotMs: Long = 0,
    val ocrMs: Long = 0,
    val ocrBlockCount: Int = 0,
    val ocrCandidateCount: Int = 0,
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0,
    val captureState: String = "not_requested",
    val pickerLayoutRecognized: Boolean = false
) {
    fun json(): JSONObject = JSONObject().apply {
        put("protocolVersion", 1)
        put("stage", "feasibility")
        put("executionSupported", false)
        put("pageKind", pageKind.takeIf { it in setOf("unknown", "detail", "selection", "payment", "synthetic") } ?: "unknown")
        put("nodeCount", nodeCount)
        put("textNodeCount", textNodeCount)
        put("clickableCount", clickableCount)
        put("candidateCount", candidateCount)
        put("nativeMs", nativeMs)
        put("truncated", truncated)
        put("screenshotMs", screenshotMs)
        put("ocrMs", ocrMs)
        put("ocrBlockCount", ocrBlockCount)
        put("ocrCandidateCount", ocrCandidateCount)
        put("screenshotWidth", screenshotWidth)
        put("screenshotHeight", screenshotHeight)
        put("captureState", captureState)
        put("pickerLayoutRecognized", pickerLayoutRecognized)
    }
}

data class Request(val action: String)

object Protocol {
    const val MAX_BYTES = 8192
    val actions = setOf("capabilities", "inspect", "native_audit", "status", "stop")
    fun parse(line: String, expectedToken: String): Request {
        require(line.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "invalid_request" }
        val obj = try { JSONObject(line) } catch (_: Exception) { throw IllegalArgumentException("invalid_request") }
        require(obj.keys().asSequence().toSet() == setOf("protocolVersion", "token", "action")) { "invalid_request" }
        require(obj.opt("protocolVersion") is Int && obj.optInt("protocolVersion") == 1) { "invalid_request" }
        val token = obj.opt("token")
        require(token is String && token.length == expectedToken.length && MessageDigest.isEqual(token.toByteArray(), expectedToken.toByteArray())) { "unauthorized" }
        val action = obj.opt("action")
        require(action is String && action in actions) { "invalid_request" }
        return Request(action)
    }
}
