package ru.hollowhorizon.hollowengine.client.keymap

import org.lwjgl.glfw.GLFW

/**
 * The names inputs are written under in the keymap file. They name a key by where it sits on a US keyboard, the way
 * GLFW does, so a binding means the same key whatever layout is active.
 */
internal object HollowKeyNames {
    private val byCode = HashMap<Int, String>()
    private val byName = HashMap<String, Int>()

    private val Named = listOf(
        GLFW.GLFW_KEY_SPACE to "Space",
        GLFW.GLFW_KEY_APOSTROPHE to "'",
        GLFW.GLFW_KEY_COMMA to ",",
        GLFW.GLFW_KEY_MINUS to "-",
        GLFW.GLFW_KEY_PERIOD to ".",
        GLFW.GLFW_KEY_SLASH to "/",
        GLFW.GLFW_KEY_SEMICOLON to ";",
        GLFW.GLFW_KEY_EQUAL to "=",
        GLFW.GLFW_KEY_LEFT_BRACKET to "[",
        GLFW.GLFW_KEY_BACKSLASH to "\\",
        GLFW.GLFW_KEY_RIGHT_BRACKET to "]",
        GLFW.GLFW_KEY_GRAVE_ACCENT to "`",
        GLFW.GLFW_KEY_ESCAPE to "Escape",
        GLFW.GLFW_KEY_ENTER to "Enter",
        GLFW.GLFW_KEY_TAB to "Tab",
        GLFW.GLFW_KEY_BACKSPACE to "Backspace",
        GLFW.GLFW_KEY_INSERT to "Insert",
        GLFW.GLFW_KEY_DELETE to "Delete",
        GLFW.GLFW_KEY_RIGHT to "Right",
        GLFW.GLFW_KEY_LEFT to "Left",
        GLFW.GLFW_KEY_DOWN to "Down",
        GLFW.GLFW_KEY_UP to "Up",
        GLFW.GLFW_KEY_PAGE_UP to "PageUp",
        GLFW.GLFW_KEY_PAGE_DOWN to "PageDown",
        GLFW.GLFW_KEY_HOME to "Home",
        GLFW.GLFW_KEY_END to "End",
        GLFW.GLFW_KEY_CAPS_LOCK to "CapsLock",
        GLFW.GLFW_KEY_SCROLL_LOCK to "ScrollLock",
        GLFW.GLFW_KEY_NUM_LOCK to "NumLock",
        GLFW.GLFW_KEY_PRINT_SCREEN to "PrintScreen",
        GLFW.GLFW_KEY_PAUSE to "Pause",
        GLFW.GLFW_KEY_KP_DECIMAL to "NumpadDecimal",
        GLFW.GLFW_KEY_KP_DIVIDE to "NumpadDivide",
        GLFW.GLFW_KEY_KP_MULTIPLY to "NumpadMultiply",
        GLFW.GLFW_KEY_KP_SUBTRACT to "NumpadSubtract",
        GLFW.GLFW_KEY_KP_ADD to "NumpadAdd",
        GLFW.GLFW_KEY_KP_ENTER to "NumpadEnter",
        GLFW.GLFW_KEY_KP_EQUAL to "NumpadEqual",
        GLFW.GLFW_KEY_MENU to "Menu",
        GLFW.GLFW_KEY_LEFT_SHIFT to "LeftShift",
        GLFW.GLFW_KEY_RIGHT_SHIFT to "RightShift",
        GLFW.GLFW_KEY_LEFT_CONTROL to "LeftCtrl",
        GLFW.GLFW_KEY_RIGHT_CONTROL to "RightCtrl",
        GLFW.GLFW_KEY_LEFT_ALT to "LeftAlt",
        GLFW.GLFW_KEY_RIGHT_ALT to "RightAlt",
        GLFW.GLFW_KEY_LEFT_SUPER to "LeftSuper",
        GLFW.GLFW_KEY_RIGHT_SUPER to "RightSuper",
    )

    private val Arrows = mapOf("Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→")

    private val MouseNames = mapOf(
        GLFW.GLFW_MOUSE_BUTTON_LEFT to "LMB",
        GLFW.GLFW_MOUSE_BUTTON_RIGHT to "RMB",
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE to "MMB",
    )

    private const val WHEEL = "Wheel"
    private const val MOUSE_PREFIX = "Mouse"

    init {
        for (code in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z) add(code, code.toChar().toString())
        for (code in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9) add(code, code.toChar().toString())
        for (code in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25) add(code, "F${code - GLFW.GLFW_KEY_F1 + 1}")
        for (code in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_9) add(code, "Numpad${code - GLFW.GLFW_KEY_KP_0}")
        Named.forEach { (code, name) -> add(code, name) }
    }

    private fun add(code: Int, name: String) {
        byCode[code] = name
        byName[name.lowercase()] = code
    }

    fun name(input: HollowInput): String = when (input) {
        is HollowInput.Key -> byCode[input.code] ?: "Key${input.code}"
        is HollowInput.Mouse -> MouseNames[input.button] ?: "$MOUSE_PREFIX${input.button + 1}"
        HollowInput.Wheel -> WHEEL
    }

    fun displayName(input: HollowInput): String = name(input).let { Arrows[it] ?: it }

    fun input(name: String): HollowInput? {
        val lower = name.lowercase()
        if (lower == WHEEL.lowercase()) return HollowInput.Wheel
        MouseNames.entries.firstOrNull { it.value.lowercase() == lower }?.let { return HollowInput.Mouse(it.key) }
        if (lower.startsWith(MOUSE_PREFIX.lowercase())) {
            return lower.removePrefix(MOUSE_PREFIX.lowercase()).toIntOrNull()?.takeIf { it >= 1 }?.let { HollowInput.Mouse(it - 1) }
        }
        byName[lower]?.let { return HollowInput.Key(it) }
        return lower.removePrefix("key").toIntOrNull()?.let(HollowInput::Key)
    }
}
