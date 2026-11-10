package ru.hollowhorizon.hollowengine.client.ui

import org.lwjgl.glfw.GLFW.*

/**
 * Owns the GLFW cursor for every surface at once.
 */
object UiCursorManager {
    /** A modal screen owns the pointer outright. */
    const val ScreenPriority = 300

    /** An overlay the pointer is actually over. */
    const val OverlayPriority = 200

    /** Something drawn in the world, under any interface above it. */
    const val WorldPriority = 100

    private const val ClaimTimeoutNanos = 500_000_000L

    private val cursors = HashMap<UiCursorShape, Long>()
    private val claims = LinkedHashMap<Any, Claim>()
    private val nativeOwners = HashSet<Any>()
    private var current: UiCursorShape? = null

    private data class Claim(val shape: UiCursorShape, val priority: Int, val claimedAtNanos: Long)

    /**
     * States what [owner] wants the cursor to be, or releases it when [shape] is null. The winning
     * claim is applied immediately, so the result does not depend on which surface draws first.
     */
    fun claim(window: Long, owner: Any, shape: UiCursorShape?, priority: Int = OverlayPriority) {
        val now = System.nanoTime()
        if (shape == null) claims.remove(owner) else claims[owner] = Claim(shape, priority, now)
        claims.values.removeIf { now - it.claimedAtNanos > ClaimTimeoutNanos }
        apply(window, claims.values.maxByOrNull { it.priority }?.shape ?: UiCursorShape.DEFAULT)
    }

    fun release(window: Long, owner: Any) = claim(window, owner, shape = null)

    /** OLE supplies its own copy/forbidden cursor while a native drag is over a surface. */
    fun nativeControl(window: Long, owner: Any, active: Boolean) {
        if (active) {
            nativeOwners += owner
        } else if (nativeOwners.remove(owner) && nativeOwners.isEmpty()) {
            current = null
            claim(window, owner, shape = null)
        }
    }

    private fun apply(window: Long, shape: UiCursorShape) {
        if (window == 0L || nativeOwners.isNotEmpty() || shape == current) return
        current = shape
        glfwSetCursor(window, cursorHandle(shape))
    }

    /** A dedicated window has no competing cursor owners. */
    internal fun setWindowCursor(window: Long, shape: UiCursorShape) {
        glfwSetCursor(window, cursorHandle(shape))
    }

    private fun cursorHandle(shape: UiCursorShape): Long {
        if (shape == UiCursorShape.DEFAULT) return 0L // 0 = window's default arrow cursor
        return cursors.getOrPut(shape) { glfwCreateStandardCursor(standardCursor(shape)) }
    }

    private fun standardCursor(shape: UiCursorShape): Int = when (shape) {
        UiCursorShape.DEFAULT -> GLFW_ARROW_CURSOR
        UiCursorShape.HAND -> GLFW_POINTING_HAND_CURSOR
        UiCursorShape.MOVE -> GLFW_RESIZE_ALL_CURSOR
        UiCursorShape.TEXT -> GLFW_IBEAM_CURSOR
        UiCursorShape.CROSSHAIR -> GLFW_CROSSHAIR_CURSOR
        UiCursorShape.RESIZE_HORIZONTAL -> GLFW_RESIZE_EW_CURSOR
        UiCursorShape.RESIZE_VERTICAL -> GLFW_RESIZE_NS_CURSOR
        UiCursorShape.RESIZE_NESW -> GLFW_RESIZE_NESW_CURSOR
        UiCursorShape.RESIZE_NWSE -> GLFW_RESIZE_NWSE_CURSOR
        UiCursorShape.NOT_ALLOWED -> GLFW_NOT_ALLOWED_CURSOR
    }
}
