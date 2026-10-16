package ru.hollowhorizon.hollowengine.client.keymap

import org.lwjgl.glfw.GLFW

/** What a binding presses: a key, a mouse button, or the wheel turned either way. */
sealed interface HollowInput {
    val kind: HollowInputKind

    data class Key(val code: Int) : HollowInput {
        override val kind get() = HollowInputKind.KEY
    }

    data class Mouse(val button: Int) : HollowInput {
        override val kind get() = HollowInputKind.MOUSE
    }

    data object Wheel : HollowInput {
        override val kind get() = HollowInputKind.WHEEL
    }
}

/** The kinds of [HollowInput] an action takes; whether it is pressed, held or turned is the action's own business. */
enum class HollowInputKind { KEY, MOUSE, WHEEL }

/**
 * An input with the modifiers held for it. Super counts as Ctrl and the lock keys do not count, so a chord reads the
 * same on every keyboard. [toString] is the text the keymap file stores and the editor shows, such as `Ctrl+Shift+F`.
 */
data class HollowKeyChord(val input: HollowInput, val modifiers: Int) {
    fun matchesKey(key: Int, modifiers: Int): Boolean =
        input is HollowInput.Key && input.code == key && this.modifiers == normalize(modifiers)

    fun matchesMouse(button: Int, modifiers: Int): Boolean =
        input is HollowInput.Mouse && input.button == button && this.modifiers == normalize(modifiers)

    fun matchesWheel(modifiers: Int): Boolean = input is HollowInput.Wheel && this.modifiers == normalize(modifiers)

    /** How the chord is shown: arrows as arrows, the rest as stored. */
    val displayText: String
        get() = (modifierNames() + HollowKeyNames.displayName(input)).joinToString("+")

    override fun toString(): String = (modifierNames() + HollowKeyNames.name(input)).joinToString("+")

    private fun modifierNames(): List<String> = buildList {
        if (modifiers and CONTROL != 0) add("Ctrl")
        if (modifiers and SHIFT != 0) add("Shift")
        if (modifiers and ALT != 0) add("Alt")
    }

    companion object {
        const val CONTROL = GLFW.GLFW_MOD_CONTROL
        const val SHIFT = GLFW.GLFW_MOD_SHIFT
        const val ALT = GLFW.GLFW_MOD_ALT

        /** [modifiers] as GLFW reports them, down to the ones a chord keeps. */
        fun normalize(modifiers: Int): Int {
            val kept = modifiers and (CONTROL or SHIFT or ALT)
            return if (modifiers and GLFW.GLFW_MOD_SUPER != 0) kept or CONTROL else kept
        }

        fun key(code: Int, modifiers: Int = 0) = HollowKeyChord(HollowInput.Key(code), normalize(modifiers))

        fun mouse(button: Int, modifiers: Int = 0) = HollowKeyChord(HollowInput.Mouse(button), normalize(modifiers))

        fun wheel(modifiers: Int = 0) = HollowKeyChord(HollowInput.Wheel, normalize(modifiers))

        /** Reads what [toString] writes; null for anything else. */
        fun parse(text: String): HollowKeyChord? {
            val parts = text.split('+').map(String::trim).filter(String::isNotEmpty)
            if (parts.isEmpty()) return null
            var modifiers = 0
            for (part in parts.dropLast(1)) {
                modifiers = modifiers or when (part.lowercase()) {
                    "ctrl", "control", "cmd", "super" -> CONTROL
                    "shift" -> SHIFT
                    "alt" -> ALT
                    else -> return null
                }
            }
            val input = HollowKeyNames.input(parts.last()) ?: return null
            return HollowKeyChord(input, modifiers)
        }

        /** Whether [key] only modifies others, and so cannot be a chord by itself. */
        fun isModifierKey(key: Int): Boolean = key in GLFW.GLFW_KEY_LEFT_SHIFT..GLFW.GLFW_KEY_RIGHT_SUPER
    }
}
