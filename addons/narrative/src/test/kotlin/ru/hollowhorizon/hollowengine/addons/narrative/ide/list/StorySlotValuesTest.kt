package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import ru.hollowhorizon.hollowengine.addons.narrative.scene.NarrativeActions
import ru.hollowhorizon.hollowengine.addons.narrative.scene.StoryList
import ru.hollowhorizon.hollowengine.addons.narrative.scene.StoryNumber
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorySlotValuesTest {
    @Test
    fun `a variable is typed by what every set puts in it`() {
        NarrativeActions.register()
        val source = """
            @set guard = npc([0, 64, 0])
            @set count = 1
            @set mood = "calm"
            @set mood = 2
            Виталик: Привет!
        """.trimIndent()

        val variables = StorySlotValues.of(StoryParser.parse(source).cst)

        assertEquals(StoryType.ACTOR, variables["guard"])
        assertEquals(StoryType.NUMBER, variables["count"])
        assertEquals(null, variables.getValue("mood"), "two sets of different types leave it untyped")
        assertEquals(StoryType.ACTOR, variables["Виталик"])
        assertEquals(StoryType.ACTOR, variables["player"])
    }

    @Test
    fun `a result out of a known set fills only a slot of that set`() {
        val result = StoryFunctionSignature(
            "use-block", emptyList(), returns = StoryType.STRING, returnValues = listOf("success", "fail")
        )
        val name = StoryFunctionSignature("name", emptyList(), returns = StoryType.STRING)

        assertFalse(result.fills(StoryParam("type", StoryType.STRING)))
        assertFalse(result.fills(StoryParam("level", StoryType.STRING, suggestions = listOf("minecraft:overworld"))))
        assertTrue(result.fills(StoryParam("value", StoryType.ANY)))
        assertTrue(name.fills(StoryParam("type", StoryType.STRING)))
    }

    @Test
    fun `a negative number in a list is a number of the story`() {
        val list = StoryExpressions.parse("[0, -60, 2 * 3]", 0, 0).evaluate { null }

        assertEquals(StoryList(listOf(StoryNumber(0f), StoryNumber(-60f), StoryNumber(6f))), list)
    }

    @Test
    fun `a change made elsewhere selects the card it touched first`() {
        val state = StoryListState()
        val before = "Первая.\n@wait 1s\nТретья."
        state.follow(StoryListModel("test.story", before))

        state.follow(StoryListModel("test.story", "Первая.\n@wait 2s\nТретья."))

        assertEquals(StorySelection(1), state.selected)
    }
}
