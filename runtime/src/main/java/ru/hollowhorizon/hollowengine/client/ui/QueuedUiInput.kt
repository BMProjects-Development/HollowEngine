package ru.hollowhorizon.hollowengine.client.ui

import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

internal sealed interface QueuedUiInput {
    data class MouseClicked(
        val mouseX: Float,
        val mouseY: Float,
        val button: Int,
        val modifiers: Int = 0,
    ) : QueuedUiInput

    data class MouseReleased(
        val mouseX: Float,
        val mouseY: Float,
        val button: Int,
        val modifiers: Int = 0,
    ) : QueuedUiInput

    data class MouseDragged(
        val mouseX: Float,
        val mouseY: Float,
        val button: Int,
        val dragX: Float,
        val dragY: Float,
        val modifiers: Int = 0,
    ) : QueuedUiInput

    data class MouseScrolled(
        val mouseX: Float,
        val mouseY: Float,
        val scrollX: Float,
        val scrollY: Float,
        val modifiers: Int = 0,
    ) : QueuedUiInput

    data class CharTyped(val codePoint: Char, val modifiers: Int) : QueuedUiInput
    data class KeyPressed(val keyCode: Int, val scanCode: Int, val modifiers: Int, val repeat: Boolean = false) : QueuedUiInput
}

/**
 * The GLFW modifier bits (shift/ctrl/alt/super) currently held, queried from the game window.
 * Mouse callbacks don't carry modifiers the way key callbacks do, so entry points ask for them here.
 */
fun currentUiKeyModifiers(): Int = currentUiKeyModifiers(Minecraft.getInstance().window.window)

internal fun currentUiKeyModifiers(window: Long): Int {
    fun down(vararg keys: Int) = keys.any { GLFW.glfwGetKey(window, it) == GLFW.GLFW_PRESS }
    var modifiers = 0
    if (down(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) modifiers = modifiers or GLFW.GLFW_MOD_SHIFT
    if (down(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) modifiers = modifiers or GLFW.GLFW_MOD_CONTROL
    if (down(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) modifiers = modifiers or GLFW.GLFW_MOD_ALT
    if (down(GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER)) modifiers = modifiers or GLFW.GLFW_MOD_SUPER
    return modifiers
}
