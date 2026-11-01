package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.test.Test
import kotlin.test.assertEquals

class StoryTemplateTest {
    @Test
    fun `the template of a new dialogue is a story that starts at main`() {
        val text = javaClass.classLoader.getResourceAsStream("internal/templates/dialogue.story.template")!!.reader().readText()
        val graph = StoryGraph.decode(text)
        val start = graph.nodes.filterIsInstance<StoryNode.Start>().single()
        assertEquals("main", start.name)
        assertEquals(listOf("hello"), graph.links.filter { it.from == start.id && it.isFlow }.map { it.to })
    }
}
