package ru.hollowhorizon.hollowengine.addons.narrative.story

/**
 * What the compiler turns a `for` into when the loop calls stages: an index it can save instead of an iterator it
 * cannot. A range is counted as it is; anything else is first copied into a list, so a collection changed while
 * the story waits does not shift the elements under the saved index.
 */
object StoryLoops {
    /** How many values [progression] goes through. */
    @JvmStatic
    fun count(progression: IntProgression): Int =
        if (progression.isEmpty()) 0 else (progression.last - progression.first) / progression.step + 1

    /** The value at [index] of a progression starting at [first] with [step]. */
    @JvmStatic
    fun element(first: Int, step: Int, index: Int): Int = first + index * step

    /** The elements a `for` over [source] goes through, in order. */
    @JvmStatic
    fun snapshot(source: Any?): List<Any?> = when (source) {
        is Iterable<*> -> source.toList()
        is Map<*, *> -> source.entries.toList()
        is Array<*> -> source.toList()
        is IntArray -> source.toList()
        is LongArray -> source.toList()
        is ShortArray -> source.toList()
        is ByteArray -> source.toList()
        is FloatArray -> source.toList()
        is DoubleArray -> source.toList()
        is BooleanArray -> source.toList()
        is CharArray -> source.toList()
        is CharSequence -> source.toList()
        is Iterator<*> -> source.asSequence().toList()
        else -> throw StoryException("A loop that calls stages cannot go over ${source?.javaClass?.name}")
    }
}
