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
            assertFalse(applyEditableFieldQuickFix(state, UiTextQuickFix("Invalid", edits)))
            assertEquals("value", state.text)
        }
    }

    @Test
    fun `asynchronous intentions populate the menu and apply as one undo step`() {
        val state = TextFieldState("val unused = effect()", multiline = true)
        val menu = EditableFieldQuickFixState()
        var publish: ((List<UiTextQuickFix>) -> Unit)? = null
        menu.request(state, UiIntentionActionsProvider { _, callback -> publish = callback })
        assertFalse(menu.active)
        val fix = UiTextQuickFix("Remove variable", listOf(UiTextEdit(0, 13, "")))
        publish!!(listOf(fix))
        assertEquals(fix, menu.selected())
        assertTrue(applyEditableFieldQuickFix(state, fix))
        assertEquals("effect()", state.text)
        assertTrue(state.undo())
        assertEquals("val unused = effect()", state.text)
    }

    @Test
    fun `moving the caret editing or closing discards late intention results`() {
        for (change in listOf<(TextFieldState, EditableFieldQuickFixState) -> Unit>(
            { state, _ -> state.moveCaret(0) },
            { state, _ -> state.insert("x") },
            { _, menu -> menu.close() },
        )) {
            val state = TextFieldState("value")
            val menu = EditableFieldQuickFixState()
            var publish: ((List<UiTextQuickFix>) -> Unit)? = null
            menu.request(state, UiIntentionActionsProvider { _, callback -> publish = callback })
            change(state, menu)
            menu.onFrame(state)
            publish!!(listOf(UiTextQuickFix("late", listOf(UiTextEdit(0, 0, "import x\n")))))
            assertFalse(menu.active)
        }
    }
}
