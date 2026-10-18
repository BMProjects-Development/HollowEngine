package ru.hollowhorizon.hollowengine.client.ui.input

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyInputPriorityTest {
    private fun keyEvent(node: UiNode, key: Int): UiEvent {
        node.resolvedModifiers = node.modifiers.flattenModifiers()
        return UiEvent(UiEventKind.KEY_PRESSED, node, key = key)
    }

    @Test
    fun `key handlers run in descending priority order`() {
        val order = mutableListOf<String>()
        val node = BoxNode(
            modifiers = listOf(
                Modifier
                    .onKeyInput(priority = 0) { order += "low" }
                    .onKeyInput(priority = 10) { order += "high" }
            )
        )
        node.dispatch(keyEvent(node, 65))
        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun `consuming a key stops lower priority handlers`() {
        val order = mutableListOf<String>()
        val node = BoxNode(
            modifiers = listOf(
                Modifier
                    .onKeyInput(priority = 10) { order += "high"; it.consume() }
                    .onKeyInput(priority = 0) { order += "low" }
            )
        )
        node.dispatch(keyEvent(node, 65))
        assertEquals(listOf("high"), order)
    }

    @Test
    fun `text field default keymap has a low priority so user handlers win first`() {
        assertTrue(TextFieldDefaultKeyPriority < 0)
    }
}
