package ru.hollowhorizon.hollowengine.client.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UndoHistoryTest {
    private val log = ArrayList<String>()
    private var now = 0L

    private inner class Step(val name: String, val possible: Boolean = true, override val cost: Int = 1) : UndoStep {
        override val label = UndoLabel(name)

        override fun undo(): Boolean = possible.also { if (it) log += "undo $name" }

        override fun redo(): Boolean = possible.also { if (it) log += "redo $name" }
    }

    /** A value rewritten whole, the way documents record their edits. */
    private inner class Value(var current: String) {
        fun set(next: String, history: UndoHistory, mergeKey: String? = null) {
            history.record(SnapshotStep(this, UndoLabel("edit"), current, { current }, { current = it }), mergeKey)
            current = next
        }
    }

    private fun history(limit: Int = 100, budget: Int = Int.MAX_VALUE) =
        UndoHistory(limit, budget, mergeWindowNanos = 100, nanoTime = { now })

    @Test
    fun `undo skips and drops a step that can no longer be taken`() {
        val history = history()
        history.record(Step("a"))
        history.record(Step("gone", possible = false))
        history.record(Step("b"))

        assertTrue(history.undo())
        assertTrue(history.undo())
        assertEquals(listOf("undo b", "undo a"), log)
        assertEquals(listOf("a", "b"), history.steps.map { it.label.key })
        assertTrue(history.redo())
        assertEquals(1, history.position)
    }

    @Test
    fun `steps removed from the middle keep the position on the same step`() {
        val history = history()
        history.record(Step("a"))
        history.record(Step("b"))
        history.record(Step("c"))
        history.undo()

        history.removeAll { it.label.key == "a" }

        assertEquals(1, history.position)
        assertTrue(history.redo())
        assertEquals(listOf("undo c", "redo c"), log)
    }

    @Test
    fun `edits with one merge key fold only while they come quickly`() {
        val history = history()
        val value = Value("")
        value.set("a", history, "typing")
        now += 50
        value.set("ab", history, "typing")
        now += 500
        value.set("abc", history, "typing")

        assertEquals(2, history.steps.size)
        history.undo()
        assertEquals("ab", value.current)
        history.undo()
        assertEquals("", value.current)
        history.redo()
        history.redo()
        assertEquals("abc", value.current)
    }

    @Test
    fun `a transaction becomes one step, and none when it changed nothing`() {
        val history = history()
        val value = Value("start")
        history.transaction {
            value.set("dragging", history)
            value.set("dropped", history)
        }
        history.transaction {
            value.set("elsewhere", history)
            value.set("dropped", history)
        }

        assertEquals(1, history.steps.size)
        history.undo()
        assertEquals("start", value.current)
        history.redo()
        assertEquals("dropped", value.current)
    }

    @Test
    fun `steps of different owners in a transaction go back together, newest first`() {
        val history = history()
        history.transaction(UndoLabel("both")) {
            history.record(Step("a"))
            history.record(Step("b"))
        }

        assertEquals(listOf("both"), history.steps.map { it.label.key })
        history.undo()
        history.redo()
        assertEquals(listOf("undo b", "undo a", "redo a", "redo b"), log)
    }

    @Test
    fun `the budget drops the oldest steps but never the newest`() {
        val history = history(budget = 10)
        history.record(Step("a", cost = 6))
        history.record(Step("b", cost = 6))
        history.record(Step("huge", cost = 50))

        assertEquals(listOf("huge"), history.steps.map { it.label.key })
    }

    @Test
    fun `the saved mark follows undo, redo and a new branch`() {
        val history = history()
        history.record(Step("a"))
        history.markSaved()
        assertTrue(history.isAtSaved)

        history.undo()
        assertFalse(history.isAtSaved)
        history.redo()
        assertTrue(history.isAtSaved)

        history.undo()
        history.record(Step("b"))
        assertFalse(history.isAtSaved)
        history.undo()
        assertFalse(history.isAtSaved)
    }

    @Test
    fun `moving to a step undoes or redoes everything between`() {
        val history = history()
        history.record(Step("a"))
        history.record(Step("b"))
        history.record(Step("c"))

        history.moveTo(0)
        history.moveTo(2)

        assertEquals(2, history.position)
        assertEquals(listOf("undo c", "undo b", "undo a", "redo a", "redo b"), log)
    }
}
