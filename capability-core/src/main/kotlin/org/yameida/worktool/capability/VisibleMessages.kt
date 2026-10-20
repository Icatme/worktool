package org.yameida.worktool.capability

/** A visible UI snapshot is not a durable message source. Ambiguity creates a new baseline. */
class VisibleMessages {
    private var previous: List<ObservedMessage>? = null
    fun reset() { previous = null }
    fun update(rows: List<ObservedMessage>): List<ObservedMessage> {
        val old = previous
        previous = rows.toList()
        if (old.isNullOrEmpty() || rows.isEmpty() || rows == old) return emptyList()
        // Require exactly one suffix/prefix overlap. Repeated identical content is deliberately conservative.
        val overlaps = (1..minOf(old.size, rows.size)).filter { old.takeLast(it) == rows.take(it) }
        if (overlaps.size != 1) return emptyList()
        return rows.drop(overlaps.single())
    }
}
