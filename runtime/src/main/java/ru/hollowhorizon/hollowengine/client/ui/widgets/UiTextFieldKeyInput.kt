package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*

data class UiKeyInput(
    val event: UiEvent,
) {
    val node: UiNode get() = event.node
    val frame: HollowUiFrame? get() = event.frame
    val key: Int get() = event.key
    val scanCode: Int get() = event.scanCode
    val modifiers: Int get() = event.modifiers
    val repeat: Boolean get() = event.repeat
    val shift: Boolean get() = modifiers and GLFW.GLFW_MOD_SHIFT != 0
    val control: Boolean get() = modifiers and GLFW.GLFW_MOD_CONTROL != 0
    val alt: Boolean get() = modifiers and GLFW.GLFW_MOD_ALT != 0
    val command: Boolean get() = control || modifiers and GLFW.GLFW_MOD_SUPER != 0

    val consumed: Boolean get() = event.consumed

    /**
     * Whether the key types a character: a letter, digit, sign or space, or one on the keypad, with neither
     * Ctrl nor Alt held, which turn it into a shortcut.
     */
    fun typesCharacter(): Boolean {
        if (command || alt) return false
        return key in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_WORLD_2 ||
            key in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_EQUAL && key != GLFW.GLFW_KEY_KP_ENTER
    }

    /** Stops this key event from reaching lower-priority handlers on the same node. */
    fun consume() {
        event.consume()
    }

    fun markChanged() {
        event.markChanged()
    }
}

internal fun wordLeft(text: String, position: Int): Int {
    var index = position.coerceIn(0, text.length)
    if (index > 0 && text[index - 1] == '\n') return index - 1
    while (index > 0 && text[index - 1].isWhitespace() && text[index - 1] != '\n') index--
    while (index > 0 && text[index - 1].isEditorWordChar()) index--
    if (index == position.coerceIn(0, text.length) && index > 0) index--
    return index
}

internal fun wordRight(text: String, position: Int): Int {
    var index = position.coerceIn(0, text.length)
    if (index < text.length && text[index] == '\n') return index + 1
    while (index < text.length && text[index].isWhitespace() && text[index] != '\n') index++
    if (index < text.length && text[index] == '\n') return index + 1
    while (index < text.length && text[index].isEditorWordChar()) index++
    if (index == position.coerceIn(0, text.length) && index < text.length) index++
    return index
}

internal fun lineStart(text: String, position: Int): Int {
    if (position <= 0) return 0
    return text.lastIndexOf('\n', (position - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
}

internal fun lineEnd(text: String, position: Int): Int {
    val nextNewline = text.indexOf('\n', position.coerceIn(0, text.length))
    return if (nextNewline < 0) text.length else nextNewline
}

private fun Char.isEditorWordChar(): Boolean = this == '_' || isLetterOrDigit()
