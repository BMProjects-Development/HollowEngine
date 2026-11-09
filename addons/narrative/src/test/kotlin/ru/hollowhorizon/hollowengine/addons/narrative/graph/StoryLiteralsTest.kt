package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryHandle
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryLiteralsTest {
    /** A field writes the file on every letter, so what was typed has to come back letter for letter. */
    @Test
    fun `a number survives being typed`() {
        fun typed(text: String, type: KType) = StoryLiterals.text(StoryLiterals.expression(text, type), type)
        listOf("-", "-1", "1.", "1.5", "x + 1").forEach { text ->
            assertEquals(text, typed(text, typeOf<Float>()))
            assertEquals(text, typed(text, typeOf<Double>()))
        }
    }

    @Test
    fun `a value with no plain form survives being typed`() {
        fun typed(text: String, type: KType) = StoryLiterals.text(StoryLiterals.expression(text, type), type)
        listOf("t", "task", "tasks[0]").forEach { text ->
            assertEquals(text, typed(text, typeOf<StoryHandle<*>>()))
            assertEquals(text, typed(text, typeOf<Any>()))
        }
        assertEquals("task", StoryLiterals.expression("task", typeOf<StoryHandle<*>>()))
        assertEquals("\"task\"", StoryLiterals.expression("task", typeOf<Any>()))
    }

    @Test
    fun `a whole number is still a double in Kotlin`() {
        assertEquals("1.toDouble()", StoryLiterals.expression("1", typeOf<Double>()))
    }

    @Test
    fun `a line takes its text from a link`() {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s"), StoryNode.Say("say", text = "typed"), StoryNode.Expression("value", "name")),
            links = listOf(StoryLink("s", StoryPins.NEXT, "say", StoryPins.IN), StoryLink("value", StoryPins.VALUE, "say", StoryPins.TEXT)),
        )
        val code = StoryGraphCompiler.compile(graph).code
        assertTrue("((name)).toString())" in code, code)
    }

    @Test
    fun `an entity fits what takes any entity and nothing narrower`() {
        assertTrue(StoryPinType.NPC.fits(StoryPinType.ENTITY))
        assertTrue(!StoryPinType.ENTITY.fits(StoryPinType.NPC))
        assertTrue(!StoryPinType.PLAYER.fits(StoryPinType.NPC))
    }
}
