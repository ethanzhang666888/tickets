package local.tickets.companion.execution

import local.tickets.companion.pages.DamaiPageParser
import local.tickets.companion.pages.VisualEvidence
import local.tickets.companion.pages.NodeSnapshot

/** A native tree cannot reveal a changed self-drawn selection. Require two agreeing visual observations. */
internal object VisualDispatchEvidence {
    fun agrees(before: VisualEvidence?, after: VisualEvidence?, now: Long, snapshot: NodeSnapshot? = null): Boolean {
        if (before == null || after == null) return before == null && after == null
        if (after.capturedAtElapsedMs > now || now - after.capturedAtElapsedMs > 2000 ||
            after.capturedAtElapsedMs < before.capturedAtElapsedMs) return false
        val ignoredRegions = snapshot?.let { listOfNotNull(ExecutionCapturePolicy.detailPulseBounds(it),
            ExecutionCapturePolicy.detailStatusBounds(it), ExecutionCapturePolicy.pickerStatusBounds(it)) }.orEmpty()
        // Animation/status OCR is irrelevant to the target, but safety markers anywhere still win.
        val nextBounds = snapshot?.let(ExecutionCapturePolicy::pickerNextBounds)
        if ((ignoredRegions.isNotEmpty() || nextBounds != null) && ExecutionCapturePolicy.unsafeWords(
                (before.words + after.words).map { it.text }, snapshot?.let(ExecutionCapturePolicy::picker) == true)) return false
        val beforeWords = before.words.filterNot { word -> ignoredRegions.any { it.contains(word.bounds) } }.toMutableList()
        val afterWords = after.words.filterNot { word -> ignoredRegions.any { it.contains(word.bounds) } }.toMutableList()
        if (nextBounds != null) {
            val beforeLabel = beforeWords.filter { nextBounds.contains(it.bounds) }.singleOrNull()
            val afterLabel = afterWords.filter { nextBounds.contains(it.bounds) }.singleOrNull()
            if (beforeLabel != null && afterLabel != null &&
                DamaiPageParser.normalized(beforeLabel.text) in setOf("确定", "立即购买", "立即预订") &&
                DamaiPageParser.normalized(beforeLabel.text) == DamaiPageParser.normalized(afterLabel.text)) {
                val a = beforeLabel.bounds
                val b = afterLabel.bounds
                if (listOf(a.left - b.left, a.top - b.top, a.right - b.right, a.bottom - b.bottom).all { it in -4..4 }) {
                    beforeWords.remove(beforeLabel)
                    afterWords.remove(afterLabel)
                }
            }
        }
        fun words(value: List<local.tickets.companion.pages.VisualWord>) = value
            .map { DamaiPageParser.normalized(it.text) to it.bounds }.groupingBy { it }.eachCount()
        return before.width == after.width && before.height == after.height &&
            before.selectedCards == after.selectedCards && words(beforeWords) == words(afterWords)
    }
}
