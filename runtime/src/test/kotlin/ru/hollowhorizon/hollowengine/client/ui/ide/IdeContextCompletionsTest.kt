package ru.hollowhorizon.hollowengine.client.ui.ide

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.ui.widgets.TextFieldCompletionState
import ru.hollowhorizon.hollowengine.client.ui.widgets.TextFieldState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.common.events.client.IdeCompletionEvent
import ru.hollowhorizon.hollowengine.common.events.eventListenerOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeContextCompletionsTest {
    @Test
    fun `position and held item snippets preserve coordinates count and imports`() {
        val snippets = contextSnippetCompletions(Vec3(-1.25, 64.0, 0.125), "minecraft:diamond", 7)
        assertEquals("pos(-1.25, 64.0, 0.125)", snippets.first().insertText)
        assertEquals("item(\"minecraft:diamond\", 7)", snippets.last().insertText)
        assertEquals(snippets.first().insertText, snippets.first().label)
        assertEquals(snippets.last().insertText, snippets.last().label)
        assertTrue(snippets.first().importFqName!!.endsWith(".pos"))
        assertTrue(snippets.last().importFqName!!.endsWith(".item"))
        assertEquals(1, contextSnippetCompletions(Vec3.ZERO, null, 0).size)
    }

    @Test
    fun `snippets are left out where their type does not fit`() {
        val onlyItems = contextSnippetCompletions(Vec3.ZERO, "minecraft:diamond", 1, listOf(ItemSnippetType))
        assertEquals(listOf("item(\"minecraft:diamond\", 1)"), onlyItems.map { it.insertText })
        assertTrue(contextSnippetCompletions(Vec3.ZERO, "minecraft:diamond", 1, emptyList()).isEmpty())
    }

    @Test
    fun `item specifications retain changed and removed components and escape Kotlin strings`() {
        val patch = CompoundTag().apply {
            putString("minecraft:custom_name", "\"example\$")
            put("!minecraft:damage", CompoundTag())
        }
        val specification = itemSpecification("minecraft:diamond_sword", patch)
        assertTrue("!minecraft:damage," in specification, specification)
        assertTrue("minecraft:custom_name=" in specification, specification)
        val insertion = contextSnippetCompletions(Vec3.ZERO, specification, 2).last().insertText
        assertTrue("\\\$" in insertion, insertion)
        assertTrue("\\\"" in insertion, insertion)
    }

    @Test
    fun `completion event contributions accept insertion text imports and caret offset`() {
        val listener = eventListenerOf<IdeCompletionEvent> { event ->
            assertEquals("scripts/test.kts", event.path)
            event.items += UiTextCompletion("example", "Example()", filterText = "example",
                importFqName = "sample.Example", caretOffset = 8)
        }
        IdeCompletionEvent.register(listener)
        try {
            val state = TextFieldState("exa")
            val completion = TextFieldCompletionState(state).apply {
                contributor = UiCompletionContributor { context ->
                    IdeCompletionEvent.post(IdeCompletionEvent("scripts/test.kts", context.text, context.caret, mutableListOf())).items
                }
            }
            assertTrue(completion.open())
            assertTrue(completion.accept())
            assertEquals("import sample.Example\n\nExample()", state.text)
            assertEquals(')', state.text[state.caret])
        } finally {
            IdeCompletionEvent.unregister(listener)
        }
    }
}
