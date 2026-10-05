package ru.hollowhorizon.hollowengine.client.ui.ide.files

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.history.UndoStep

/** The pixels one stroke touches, with the color each had before it. */
internal class HollowIdeImageEdit {
    private val originalPixels = Int2IntOpenHashMap().apply { defaultReturnValue(Int.MIN_VALUE) }

    val isEmpty: Boolean get() = originalPixels.isEmpty()

    fun record(index: Int, color: Int) {
        if (!originalPixels.containsKey(index)) originalPixels.put(index, color)
    }

    fun build(readPixel: (Int) -> Int, writePixel: (Int, Int) -> Unit): HollowIdePixelChange {
        val indices = IntArray(originalPixels.size)
        val before = IntArray(originalPixels.size)
        val after = IntArray(originalPixels.size)
        val iterator = originalPixels.int2IntEntrySet().fastIterator()
        var position = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            indices[position] = entry.intKey
            before[position] = entry.intValue
            after[position] = readPixel(entry.intKey)
            position++
        }
        return HollowIdePixelChange(indices, before, after, writePixel)
    }
}

/** A stroke in an image's history: the pixels it changed, as they were before and after it. */
internal class HollowIdePixelChange(
    private val indices: IntArray,
    private val before: IntArray,
    private val after: IntArray,
    private val writePixel: (Int, Int) -> Unit,
) : UndoStep {
    override val label get() = STROKE

    override val cost: Int get() = indices.size

    override fun undo(): Boolean = apply(before)

    override fun redo(): Boolean = apply(after)

    private fun apply(colors: IntArray): Boolean {
        for (index in indices.indices) writePixel(indices[index], colors[index])
        return true
    }

    companion object {
        private val STROKE = UndoLabel("${UndoLabel.LANG}.stroke")

        /** The history of one image: how many strokes it keeps, and how many pixels they may hold in all. */
        fun history() = UndoHistory(limit = 128, budget = 4 * 1024 * 1024)
    }
}
