import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.editor.WorldHistory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One history for the whole world: undo walks back through every entity in the order edits were made. */
class WorldHistoryTest {
    private val log = ArrayList<String>()

    private inner class Step(override val entityId: Int, val name: String, val possible: Boolean = true) : WorldHistory.Step {
        override fun undo(): Boolean = possible.also { if (it) log += "undo $name" }

        override fun redo(): Boolean = possible.also { if (it) log += "redo $name" }
    }

    @AfterEach
    fun clear() {
        while (WorldHistory.undo()) Unit
        WorldHistory.forget(1)
        WorldHistory.forget(2)
    }

    @Test
    fun `undo goes back across entities, skipping a step that can no longer be taken`() {
        WorldHistory.record(Step(1, "a"))
        WorldHistory.record(Step(2, "gone", possible = false))
        WorldHistory.record(Step(2, "b"))

        assertTrue(WorldHistory.undo())
        assertTrue(WorldHistory.undo())
        assertTrue(WorldHistory.redo())
        assertEquals(listOf("undo b", "undo a", "redo a"), log)
    }

    @Test
    fun `an entity changed elsewhere leaves the history, the others stay`() {
        WorldHistory.record(Step(1, "a"))
        WorldHistory.record(Step(2, "b"))

        WorldHistory.forget(2)

        assertTrue(WorldHistory.undo())
        assertFalse(WorldHistory.undo())
        assertEquals(listOf("undo a"), log)
    }
}
