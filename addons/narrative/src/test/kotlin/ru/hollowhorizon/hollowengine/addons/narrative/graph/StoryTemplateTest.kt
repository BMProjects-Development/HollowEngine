package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.test.Test
import kotlin.test.assertEquals

class StoryTemplateTest {
    @Test
    fun `the template of a new dialogue is a story that starts at main`() {
        val text = javaClass.classLoader.getResourceAsStream("internal/templates/dialogue.story.template")!!.reader().readText()
        val start = StoryFlow(StoryGraph.decode(text)).sections.single()
        assertEquals("main", start.start?.name)
        assertEquals(listOf("hello"), start.steps.map { it.id })
    }
}
