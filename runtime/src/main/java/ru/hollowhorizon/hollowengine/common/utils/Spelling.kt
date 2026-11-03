package ru.hollowhorizon.hollowengine.common.utils

/** Names that look like a mistyped one, to offer in its place. */
object Spelling {
    /** The Levenshtein distance between [left] and [right]. */
    fun distance(left: String, right: String): Int {
        if (left == right) return 0
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    /**
     * Up to [limit] of [names] that [word] may have meant, the closest first: those a few edits away, ignoring case,
     * and those that hold it or that it holds.
     */
    fun similar(word: String, names: Iterable<String>, limit: Int = 3): List<String> {
        val typed = word.lowercase()
        val allowed = (typed.length / 3).coerceAtLeast(1)
        return names.asSequence()
            .map { it to distance(typed, it.lowercase()) }
            .filter { (name, distance) -> distance <= allowed || typed.isNotEmpty() && (typed in name.lowercase() || name.lowercase() in typed) }
            .sortedBy { (_, distance) -> distance }
            .take(limit)
            .map { (name, _) -> name }
            .toList()
    }
}
