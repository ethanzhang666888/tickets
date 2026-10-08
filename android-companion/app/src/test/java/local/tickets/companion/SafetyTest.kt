package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    @Test fun onlyScopedUnlockedNonPaymentForegroundIsAllowed() {
        assertTrue(Safety.canInspect(Safety.DAMAI, false, true, false))
        assertTrue(Safety.canInspect(Safety.OWN, false, true, false))
        assertFalse(Safety.canInspect("com.eg.android.AlipayGphone", false, true, false))
        assertFalse(Safety.canInspect(Safety.DAMAI, true, true, false))
        assertFalse(Safety.canInspect(Safety.DAMAI, false, false, false))
        assertFalse(Safety.canInspect(Safety.DAMAI, false, true, true))
        assertFalse(Safety.canInspect(null, false, true, false))
    }

    @Test fun paymentDetectionTakesPrecedenceOverCandidateLabels() {
        assertEquals("payment", Safety.pageKind(listOf("立即购买", "确认支付")))
        assertEquals("payment", Safety.pageKind(listOf("确认订单", "提交订单")))
        assertEquals("unknown", Safety.pageKind(listOf("张三", "身份证号 123456")))
        assertEquals("selection", Safety.pageKind(listOf("选择票档", "选择场次")))
    }

    @Test fun summariesNeverContainInputTextOrPackageNames() {
        val summary = SafeSummary(pageKind = Safety.pageKind(listOf("张三 13800138000")), nodeCount = 3)
        val json = summary.json().toString()
        assertFalse(json.contains("张三"))
        assertFalse(json.contains("13800138000"))
        assertFalse(json.contains("com.damai"))
        assertTrue(json.contains("\"executionSupported\":false"))
        assertTrue(json.contains("\"stage\":\"feasibility\""))
    }

    @Test fun protocolRejectsExecutionWrongVersionExtraFieldsAndBadToken() {
        val token = "a".repeat(48)
        assertEquals("inspect", Protocol.parse("""{"protocolVersion":1,"token":"$token","action":"inspect"}""", token).action)
        for (payload in listOf(
            """{"protocolVersion":1,"token":"$token","action":"buy"}""",
            """{"protocolVersion":2,"token":"$token","action":"inspect"}""",
            """{"protocolVersion":1,"token":"wrong","action":"status"}""",
            """{"protocolVersion":1,"token":"$token","action":"inspect","x":5}""",
            """{"protocolVersion":"1","token":"$token","action":"status"}""",
            "[]", "x".repeat(8193)
        )) {
            assertThrows(IllegalArgumentException::class.java) { Protocol.parse(payload, token) }
        }
    }

    @Test fun syntheticClickHasNoDamaiPathAndRequiresUniqueNativeControl() {
        assertTrue(Safety.canClickTest(Safety.OWN, 1, true, true))
        assertFalse(Safety.canClickTest(Safety.DAMAI, 1, true, true))
        assertFalse(Safety.canClickTest(Safety.OWN, 2, true, true))
        assertFalse(Safety.canClickTest(Safety.OWN, 1, false, true))
        assertFalse(Safety.canClickTest(Safety.OWN, 1, true, false))
    }
}
