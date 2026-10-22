package ru.hollowhorizon.hollowengine.addons.narrative.story

/**
 * What the compiler turns a `for` over a range into when the loop calls stages: an index it can save instead
 * of an iterator it cannot.
 */
object StoryLoops {
    /** How many values [progression] goes through. */
    @JvmStatic
    fun count(progression: IntProgression): Int =
        if (progression.isEmpty()) 0 else (progression.last - progression.first) / progression.step + 1

    /** The value at [index] of a progression starting at [first] with [step]. */
    @JvmStatic
    fun element(first: Int, step: Int, index: Int): Int = first + index * step
}
