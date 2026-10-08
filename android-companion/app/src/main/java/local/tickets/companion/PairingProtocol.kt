package local.tickets.companion

/** Not a data class: default toString must not expose the candidate. */
class PairAttempt(val candidate: String?, val validEnvelope: Boolean)

/**
 * Pure, bounded enrollment grammar, independent of Android/JVM JSONObject leniency differences.
 * Accepts ordinary JSON whitespace and any key order, but no duplicate/extra keys, escaped keys,
 * single-quoted strings, numeric codes, decimal/string versions or trailing content.
 */
object PairingProtocol {
    private const val WS = "[ \\t\\r\\n]*"
    private val marker = Regex("\"action\"$WS:$WS\"pair\"|\"pairingCode\"")
    private val candidate = Regex("\"pairingCode\"$WS:$WS\"([0-9]{8})\"")
    private const val FIELD = "\"(protocolVersion|action|pairingCode)\"$WS:$WS(1|\"pair\"|\"[0-9]{8}\")"
    private val envelope = Regex("\\A$WS\\{$WS$FIELD(?:$WS,$WS$FIELD){2}$WS\\}$WS\\z")
    private val field = Regex(FIELD)

    fun parse(line: String): PairAttempt? {
        require(line.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_BYTES) { "invalid_request" }
        if (!marker.containsMatchIn(line)) return null
        val code = candidate.find(line)?.groupValues?.get(1)
        // Candidate extraction alone grants nothing. Every comparison is gated on the full grammar.
        val fields = if (envelope.matches(line)) field.findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList() else emptyList()
        val values = fields.toMap()
        val valid = fields.size == 3 && values.size == 3 && values["protocolVersion"] == "1" &&
            values["action"] == "\"pair\"" && code != null && values["pairingCode"] == "\"$code\""
        return PairAttempt(code, valid)
    }
}
