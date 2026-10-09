package local.tickets.companion.pages

/** Transient phone-local OCR and border observations. Never serialized or logged. */
class VisualWord(val text: String, val bounds: NodeBounds) {
    override fun toString() = "VisualWord(redacted)"
}

class VisualEvidence(
    val captureId: Long,
    val width: Int,
    val height: Int,
    words: List<VisualWord>,
    selectedCards: Map<Int, Boolean>,
    val capturedAtElapsedMs: Long,
) {
    val words = frozenList(words)
    val selectedCards: Map<Int, Boolean> = java.util.Collections.unmodifiableMap(HashMap(selectedCards))
    init {
        require(captureId >= 0 && width in 1..4096 && height in 1..4096 && capturedAtElapsedMs >= 0)
        require(words.size <= 512 && words.sumOf { it.text.length } <= NodeSnapshot.MAX_TOTAL_CHARACTERS)
        require(words.all { it.text.length <= NodeSnapshot.MAX_TEXT_LENGTH && NodeBounds(0, 0, width, height).contains(it.bounds) })
        require(selectedCards.size <= NodeSnapshot.MAX_NODES && selectedCards.keys.all { it >= 0 })
    }
    fun wordsIn(bounds: NodeBounds): List<String> = words.filter { bounds.contains(it.bounds) }.map { it.text }
    override fun toString() = "VisualEvidence(redacted)"
}
