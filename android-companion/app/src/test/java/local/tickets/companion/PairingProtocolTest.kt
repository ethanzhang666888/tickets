package local.tickets.companion

import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class PairingProtocolTest {
    private val valid = """{"protocolVersion":1,"action":"pair","pairingCode":"12345678"}"""
    private fun fresh() = Enrollment({ 0L }, object : SecureRandom() {
        override fun nextInt(bound: Int) = 12345678
    }).also { it.issue() }
    private fun consume(enrollment: Enrollment, payload: String): Boolean {
        val attempt = PairingProtocol.parse(payload) ?: return false
        return enrollment.consume(attempt.candidate, attempt.validEnvelope)
    }

    @Test fun exactHandshakeAcceptsAnyKeyOrderAndJsonWhitespace() {
        assertTrue(consume(fresh(), valid))
        assertTrue(consume(fresh(), " \n{ \"pairingCode\": \"12345678\",\t\"action\":\"pair\",\"protocolVersion\": 1 }\r"))
        assertEquals(setOf("capabilities", "inspect", "native_audit", "status", "stop"), Protocol.actions)
    }
    @Test fun sameParserTestsDoNotDependOnJSONObjectForEnrollment() {
        for (payload in listOf(
            valid.replace("}", ",\"extra\":0}"),
            valid.replace("}", ",\"pairingCode\":\"12345678\"}"),
            valid.replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\""),
            valid.replace("\"protocolVersion\":1", "\"protocolVersion\":1.0"),
            valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
            valid + "{}",
            valid.replace(",\"action\"", ";\"action\""),
            valid.replace("\"protocolVersion\"", "protocolVersion"),
            valid.replace("\"protocolVersion\"", "'protocolVersion'"),
            valid.replace("\"protocolVersion\"", "\"protocol\\u0056ersion\""),
            valid.replace("{", "{\u000b"),
            "[$valid]"
        )) {
            val enrollment = fresh()
            repeat(5) { assertFalse(consume(enrollment, payload)) }
            assertFalse(consume(enrollment, valid))
        }
    }
    @Test fun candidateFormatIsStrictAndNeverCoerced() {
        for (value in listOf("12345678", "\"1234567\"", "\"１２３４５６７８\"", "\"12345678 \"", "null", "\"\\u00312345678\"")) {
            val attempt = PairingProtocol.parse(valid.replace("\"12345678\"", value))!!
            assertFalse(attempt.validEnvelope)
            assertNull(attempt.candidate)
        }
    }
    @Test fun normalAuthenticatedRequestIsNotEnrollmentAndPairCannotUseNormalAuthParser() {
        val token = "a".repeat(48)
        val normal = """{"protocolVersion":1,"token":"$token","action":"status"}"""
        assertNull(PairingProtocol.parse(normal))
        assertEquals("status", Protocol.parse(normal, token).action)
        assertThrows(IllegalArgumentException::class.java) { Protocol.parse(valid, token) }
        assertThrows(IllegalArgumentException::class.java) { PairingProtocol.parse(" ".repeat(8193) + valid) }
    }
    @Test fun malformedHandshakeCannotRevealCorrectCodeOrResetAttemptLimit() {
        val enrollment = fresh()
        repeat(4) { assertFalse(consume(enrollment, valid.replace("12345678", "00000000"))) }
        assertFalse(consume(enrollment, valid.replace("}", ",\"token\":\"unused\"}")))
        assertFalse(consume(enrollment, valid))
    }
}
