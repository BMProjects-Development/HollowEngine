package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.copyNodes
import kotlin.test.Test
import kotlin.test.assertTrue

class StoryGraphEditsTest {
    @Test
    fun `duplicating a start gives the copy a distinct stage name`() {
        val graph = StoryGraph(nodes = listOf(StoryNode.Start("start")))

        val (copied, _) = graph.copyNodes(setOf("start"), StoryPoint(24f, 24f))
        val generated = StoryGraphCompiler.compile(copied)

        assertTrue(
            generated.problems.none { it.key == "shared_start" },
            "An editor duplicate must preserve compilable stage names"
        )
    }

    @Test
    fun `duplicating a collapsed node keeps the copy collapsed`() {
        val graph = StoryGraph(nodes = listOf(StoryNode.Say("say")), collapsed = setOf("say"))

        val (copied, ids) = graph.copyNodes(setOf("say"), StoryPoint(24f, 24f))

        assertTrue(ids.getValue("say") in copied.collapsed)
    }
}
