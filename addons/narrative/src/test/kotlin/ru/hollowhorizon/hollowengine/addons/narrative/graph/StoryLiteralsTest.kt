package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameVariable
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `a whole number is still a double in Kotlin`() {
        assertEquals("1.toDouble()", StoryLiterals.expression("1", typeOf<Double>()))
    }

    @Test
    fun `a vector keeps a coordinate that is not typed yet`() {
        val written = StoryLiterals.vector(listOf("1", "", "z"), "vec")
        assertEquals(listOf("1", "", "z"), StoryLiterals.vectorParts(written, "vec"))
    }

    @Test
    fun `a coordinate can hold commas of its own`() {
        assertEquals(listOf("max(1, 2)", "\"a,b\".length", "3"), StoryLiterals.vectorParts("vec(max(1, 2), \"a,b\".length, 3)", "vec"))
    }

    @Test
    fun `an expression is not taken for coordinates`() {
        assertNull(StoryLiterals.vectorParts("storyPlayer().position()", "vec"))
    }

    @Test
    fun `a line takes its text from a link`() {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), StoryNode.Say("say", text = "typed"), StoryNode.Expression("value", "name")),
            links = listOf(StoryLink("s", StoryPins.NEXT, "say", StoryPins.IN), StoryLink("value", StoryPins.VALUE, "say", StoryPins.TEXT)),
        )
        val code = StoryGraphCompiler.compile(graph).code
        assertTrue("((name)).toString())" in code, code)
    }

    @Test
    fun `a list keeps its type through the field`() {
        val written = StoryVariableTypes.expression("1, 2.5", "List<Double>")
        assertEquals("listOf(1.toDouble(), 2.5)", written)
        assertEquals("1, 2.5", StoryVariableTypes.text(written, "List<Double>"))
    }

    @Test
    fun `a map keeps its pairs through the field`() {
        val written = StoryVariableTypes.expression("a = 1, b = 2.5", "Map<String, Double>")
        assertEquals("mapOf(\"a\" to 1.toDouble(), \"b\" to 2.5)", written)
        assertEquals("a = 1, b = 2.5", StoryVariableTypes.text(written, "Map<String, Double>"))
    }

    @Test
    fun `an entity fits what takes any entity and nothing narrower`() {
        assertTrue(StoryPinType.NPC.fits(StoryPinType.ENTITY))
        assertTrue(!StoryPinType.ENTITY.fits(StoryPinType.NPC))
        assertTrue(!StoryPinType.PLAYER.fits(StoryPinType.NPC))
    }

    @Test
    fun `renaming a variable reaches what reads it and nothing else`() {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("trust", "0")),
            nodes = listOf(
                StoryNode.Say("say", text = "{trust} and trust"),
                StoryNode.Assign("set", "trust", "trust + npc.trust + \"trust\".length + mistrust"),
            ),
        ).renameVariable("trust", "faith")
        assertEquals("faith", graph.variables.single().name)
        assertEquals("{faith} and trust", (graph.node("say") as StoryNode.Say).text)
        val set = graph.node("set") as StoryNode.Assign
        assertEquals("faith", set.variable)
        assertEquals("faith + npc.trust + \"trust\".length + mistrust", set.value)
    }
}
