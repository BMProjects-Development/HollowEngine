package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntentionActionsTest {
    @Test
    fun `overlapping and invalid intention edits are rejected atomically`() {
        val state = TextFieldState("value")
        for (edits in listOf(
            listOf(UiTextEdit(0, 3, "a"), UiTextEdit(2, 4, "b")),
            listOf(UiTextEdit(0, 9, "a")),
            listOf(UiTextEdit(-1, 0, "a")),
        )) {
            assertFalse(state.applyTextEdits(edits))
            assertEquals("value", state.text)
        }
    }

    @Test
    fun `diagnostic fixes come first and late analyzer actions join the open menu`() {
        val state = TextFieldState("val unused = effect()", multiline = true)
        val completion = TextFieldCompletionState(state)
        var answer = emptyList<UiTextCompletion>()
        completion.contributor = UiCompletionContributor { context -> if (context.intentions) answer else emptyList() }
        val fix = UiTextQuickFix("Remove variable", listOf(UiTextEdit(0, 13, "")))
        assertTrue(completion.openIntentions(listOf(fix)))
        assertEquals(listOf("Remove variable"), completion.items.map { it.label })

        answer = listOf(UiTextQuickFix("Import effect", listOf(UiTextEdit(0, 0, "import effect\n"))).toCompletion())
        completion.onFrame(revision = 1)
        assertEquals(listOf("Remove variable", "Import effect"), completion.items.map { it.label })

        assertTrue(completion.accept(0))
        assertEquals("effect()", state.text)
        assertFalse(completion.active)
        assertTrue(state.undo())
        assertEquals("val unused = effect()", state.text)
    }

    @Test
    fun `moving the caret or editing closes the menu before late actions arrive`() {
        for (change in listOf<(TextFieldState) -> Unit>({ it.moveCaret(0) }, { it.insert("x") })) {
            val state = TextFieldState("value")
            state.moveCaret(state.text.length)
            val completion = TextFieldCompletionState(state)
            var answer = emptyList<UiTextCompletion>()
            completion.contributor = UiCompletionContributor { context -> if (context.intentions) answer else emptyList() }
            completion.openIntentions(emptyList())
            change(state)
            completion.onFrame(revision = 0)
            answer = listOf(UiTextQuickFix("late", listOf(UiTextEdit(0, 0, "import x\n"))).toCompletion())
            completion.onFrame(revision = 1)
            assertFalse(completion.active)
        }
    }
}
