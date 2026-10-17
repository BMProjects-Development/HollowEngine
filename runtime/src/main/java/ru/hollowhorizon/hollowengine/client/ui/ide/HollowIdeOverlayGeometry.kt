package ru.hollowhorizon.hollowengine.client.ui.ide

import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

internal data class HollowIdeOverlayPoint(val x: Float, val y: Float)

/**
 * Turns a pointer in the window's own pixels into the editor's logical coordinates.
 */
internal fun hollowIdeOverlayPoint(x: Float, y: Float): HollowIdeOverlayPoint {
    val sourceWidth = HollowIdeGameViewport.windowWidth().toFloat().coerceAtLeast(1f)
    val sourceHeight = HollowIdeGameViewport.windowHeight().toFloat().coerceAtLeast(1f)
    return HollowIdeOverlayPoint(
        x = x * HollowIdeScale.scaledWidth() / sourceWidth,
        y = y * HollowIdeScale.scaledHeight() / sourceHeight,
    )
}

/** Where the window's cursor is for [x], [y] in the IDE's coordinates: [hollowIdeOverlayPoint] taken back. */
internal fun hollowIdeCursorPoint(x: Float, y: Float): HollowIdeOverlayPoint {
    val window = Minecraft.getInstance().window
    return HollowIdeOverlayPoint(
        x = x * window.screenWidth / HollowIdeScale.scaledWidth(),
        y = y * window.screenHeight / HollowIdeScale.scaledHeight(),
    )
}

/**
 * Turns a pointer in the pixels of the target the world was drawn into.
 */
internal fun hollowIdeWorldPoint(x: Float, y: Float): HollowIdeOverlayPoint {
    val scale = HollowIdeScale.factor()
    return HollowIdeOverlayPoint(x / scale, y / scale)
}

internal fun hollowIdeControlModifierDown(window: Long = Minecraft.getInstance().window.window): Boolean {
    return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(
        window,
        GLFW.GLFW_KEY_RIGHT_CONTROL
    ) == GLFW.GLFW_PRESS
}

internal fun hollowIdeModifierMask(window: Long = Minecraft.getInstance().window.window): Int {
    var modifiers = 0
    if (hollowIdeControlModifierDown(window)) modifiers = modifiers or GLFW.GLFW_MOD_CONTROL
    if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(
            window,
            GLFW.GLFW_KEY_RIGHT_SHIFT
        ) == GLFW.GLFW_PRESS
    ) {
        modifiers = modifiers or GLFW.GLFW_MOD_SHIFT
    }
    if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(
            window,
            GLFW.GLFW_KEY_RIGHT_ALT
        ) == GLFW.GLFW_PRESS
    ) {
        modifiers = modifiers or GLFW.GLFW_MOD_ALT
    }
    return modifiers
}
