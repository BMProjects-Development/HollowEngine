package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLink
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StoryGraphClipboardTest {
    @Test
    fun `copied function calls follow the copied function even after a name collision`() {
        val source = StoryGraph(
            nodes = listOf(
                StoryNode.Function(
                    "f",
                    "custom",
                    body = StoryGraph(nodes = listOf(StoryNode.Invoke("self", "custom")))
                ),
                StoryNode.Invoke("call", "custom"),
            )
        )
        val snapshot = StoryGraphClipboard.snapshot(source, setOf("f", "call"), emptyMap())
        val destination = StoryGraph(nodes = listOf(StoryNode.Function("existing", "function1")))
        val (result, ids) = StoryGraphClipboard.paste(snapshot, destination, StoryPoint(0f, 0f))
        val function = result.nodes.filterIsInstance<StoryNode.Function>().single { it.id in ids }
        val call = result.nodes.filterIsInstance<StoryNode.Invoke>().single { it.id in ids }
        assertNotEquals("function1", function.name)
        assertEquals(function.name, call.function)
        assertEquals(function.name, (function.body.nodes.single() as StoryNode.Invoke).function)
        assertNotEquals("self", function.body.nodes.single().id)
    }

    @Test
    fun `an external value link is retained only where its source exists`() {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Expression("value", "42"), StoryNode.Say("say", text = "hello")),
            links = listOf(StoryLink("value", "result", "say", "text")),
            layout = mapOf("say" to StoryPoint(10f, 20f)),
        )
        val snapshot = StoryGraphClipboard.snapshot(graph, setOf("say"), graph.layout)
        val (same, sameIds) = StoryGraphClipboard.paste(snapshot, graph, StoryPoint(20f, 20f))
        assertTrue(same.links.any { it.from == "value" && it.to in sameIds })
        val (other, otherIds) = StoryGraphClipboard.paste(snapshot, StoryGraph(), StoryPoint(20f, 20f))
        assertTrue(other.links.none { it.to in otherIds })
    }

    @Test
    fun `clipboard keeps values and selected links while making fresh ids`() {
        val nodes = listOf(StoryNode.Say("a", text = "<b>Hello</b>"), StoryNode.Say("b", text = "world"))
        val graph = StoryGraph(
            nodes = nodes,
            links = listOf(StoryLink("a", "next", "b", "in")),
            layout = mapOf("a" to StoryPoint(10f, 20f), "b" to StoryPoint(50f, 20f)),
            collapsed = setOf("a")
        )
        val snapshot = StoryGraphClipboard.snapshot(graph, setOf("a", "b"), graph.layout)
        val (result, ids) = StoryGraphClipboard.paste(snapshot, graph, StoryPoint(100f, 50f))
        assertEquals(2, ids.size)
        assertTrue(ids.intersect(setOf("a", "b")).isEmpty())
        val copied = result.nodes.filter { it.id in ids }.filterIsInstance<StoryNode.Say>()
        assertEquals(setOf("<b>Hello</b>", "world"), copied.map { it.text }.toSet())
        assertEquals(1, result.links.count { it.from in ids && it.to in ids })
        assertEquals(1, result.collapsed.intersect(ids).size)
        assertNotEquals(graph.layout.values.toSet(), result.layout.filterKeys { it in ids }.values.toSet())
    }
}
