package ru.hollowhorizon.hollowengine.client.keymap

import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * A part of editor whose keys are read together, such as timeline or a 3D view. Two actions of one context
 * may not share a chord; an action of a narrower context shadows a general one while its part has the focus.
 */
class HollowKeyContext(val id: String) {
    val label: String get() = "hollowengine.gui.keymap.context.$id".lang
}

/**
 * Something, that user does by a keyboard/mouse. The chords come from [HollowKeymap], which keeps what the user rebound; the
 * code that performs the action only asks whether an input [matches]. [kinds] are the inputs it can be bound to:
 * whether a key fires on press or a button drags is decided where the action is handled, never by the binding.
 */
class HollowKeyAction(
    val id: String,
    val context: HollowKeyContext,
    val defaults: List<HollowKeyChord>,
    val kinds: Set<HollowInputKind> = setOf(HollowInputKind.KEY),
    private val labelText: () -> String = { "hollowengine.gui.keymap.action.$id".lang },
) {
    val label: String get() = labelText()

    val bindings: List<HollowKeyChord> get() = HollowKeymap.bindingsOf(this)

    fun matches(key: Int, modifiers: Int): Boolean = bindings.any { it.matchesKey(key, modifiers) }

    fun matches(input: UiKeyInput): Boolean = matches(input.key, input.modifiers)

    fun matchesMouse(button: Int, modifiers: Int): Boolean = bindings.any { it.matchesMouse(button, modifiers) }

    fun matchesWheel(modifiers: Int): Boolean = bindings.any { it.matchesWheel(modifiers) }

    /** Whether a key or button bound to it is down right now, for an action that is held rather than pressed. */
    fun isHeld(): Boolean {
        val window = Minecraft.getInstance().window.window
        return bindings.any { chord ->
            when (val input = chord.input) {
                is HollowInput.Key -> GLFW.glfwGetKey(window, input.code) == GLFW.GLFW_PRESS
                is HollowInput.Mouse -> GLFW.glfwGetMouseButton(window, input.button) == GLFW.GLFW_PRESS
                HollowInput.Wheel -> false
            }
        }
    }

    /** The first chord as shown in menus and tooltips, or null when nothing is bound. */
    val shortcut: String? get() = bindings.firstOrNull()?.displayText

    /** [text] followed by the chord in parentheses, for a tooltip; [text] alone when nothing is bound. */
    fun hint(text: String): String = shortcut?.let { "$text ($it)" } ?: text

    override fun toString(): String = id
}
