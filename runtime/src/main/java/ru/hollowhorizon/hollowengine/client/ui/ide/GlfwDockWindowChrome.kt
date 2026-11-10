package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack
import ru.hollowhorizon.hollowengine.client.ui.docking.DockResizeEdge
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class GlfwDockWindowChrome(private val handle: Long, val custom: Boolean) {
    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)
    private data class Grab(val bounds: Bounds, val pointer: HollowIdeOverlayPoint, val edge: DockResizeEdge?)

    private var grab: Grab? = null
    private var restoredBounds: Bounds? = null
    private var moving = false
    private var showingDockPreview = false
    var maximized by mutableStateOf(false)
        private set

    fun update() {
        maximized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE
        if (!maximized) restoredBounds = bounds()
    }

    fun minimize() = GLFW.glfwIconifyWindow(handle)

    fun toggleMaximize() {
        if (maximized) GLFW.glfwRestoreWindow(handle) else GLFW.glfwMaximizeWindow(handle)
        update()
    }

    fun requestClose() = GLFW.glfwSetWindowShouldClose(handle, true)

    fun begin(edge: DockResizeEdge? = null) {
        if (maximized && edge != null) return
        val bounds = bounds()
        grab = Grab(bounds, desktopCursorPosition(handle), edge)
    }

    /** True once a title drag is moving the window and can also dock the whole stack. */
    fun drag(): Boolean {
        var start = grab ?: return false
        val current = bounds()
        val point = desktopCursorPosition(handle)
        if (start.edge == null && !moving) {
            if (hypot(point.x - start.pointer.x, point.y - start.pointer.y) < MoveThreshold) return false
            if (maximized) {
                GLFW.glfwRestoreWindow(handle)
                val restored = restoredBounds ?: bounds()
                val grabFraction = ((start.pointer.x - start.bounds.x) / start.bounds.width).coerceIn(0f, 1f)
                val x = point.x - restored.width * grabFraction
                val y = point.y - (start.pointer.y - start.bounds.y)
                start = Grab(restored.copy(x = x.roundToInt(), y = y.roundToInt()), point, null)
                grab = start
            }
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE)
            if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WIN32) {
                // Win32 needs an alpha value for the layered window used by mouse passthrough.
                GLFW.glfwSetWindowOpacity(handle, 1f)
            }
            moving = true
        }
        val dx = (point.x - start.pointer.x).roundToInt()
        val dy = (point.y - start.pointer.y).roundToInt()
        val bounds = start.bounds
        val edge = start.edge
        val next = if (edge == null) {
            bounds.copy(x = bounds.x + dx, y = bounds.y + dy)
        } else {
            resize(bounds, edge, dx, dy)
        }
        if (next.width != current.width || next.height != current.height) {
            GLFW.glfwSetWindowSize(handle, next.width, next.height)
        }
        if (next.x != current.x || next.y != current.y) GLFW.glfwSetWindowPos(handle, next.x, next.y)
        return moving
    }

    fun showDockPreview(available: Boolean) {
        if (showingDockPreview == available) return
        GLFW.glfwSetWindowOpacity(handle, if (available) DockPreviewOpacity else 1f)
        showingDockPreview = available
    }

    fun end() {
        grab = null
        val restoreOpacity = moving || showingDockPreview
        if (moving) {
            moving = false
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_FALSE)
        }
        if (restoreOpacity) GLFW.glfwSetWindowOpacity(handle, 1f)
        showingDockPreview = false
    }

    private fun bounds(): Bounds = MemoryStack.stackPush().use { stack ->
        val x = stack.mallocInt(1)
        val y = stack.mallocInt(1)
        val width = stack.mallocInt(1)
        val height = stack.mallocInt(1)
        GLFW.glfwGetWindowPos(handle, x, y)
        GLFW.glfwGetWindowSize(handle, width, height)
        Bounds(x[0], y[0], width[0], height[0])
    }

    private fun resize(bounds: Bounds, edge: DockResizeEdge, dx: Int, dy: Int): Bounds {
        val left = when (edge) {
            DockResizeEdge.LEFT, DockResizeEdge.TOP_LEFT, DockResizeEdge.BOTTOM_LEFT -> dx.coerceAtMost(bounds.width - MinWidth)
            else -> 0
        }
        val right = when (edge) {
            DockResizeEdge.RIGHT, DockResizeEdge.TOP_RIGHT, DockResizeEdge.BOTTOM_RIGHT -> dx.coerceAtLeast(MinWidth - bounds.width)
            else -> 0
        }
        val top = when (edge) {
            DockResizeEdge.TOP, DockResizeEdge.TOP_LEFT, DockResizeEdge.TOP_RIGHT -> dy.coerceAtMost(bounds.height - MinHeight)
            else -> 0
        }
        val bottom = when (edge) {
            DockResizeEdge.BOTTOM, DockResizeEdge.BOTTOM_LEFT, DockResizeEdge.BOTTOM_RIGHT -> dy.coerceAtLeast(MinHeight - bounds.height)
            else -> 0
        }
        return Bounds(bounds.x + left, bounds.y + top, bounds.width + right - left, bounds.height + bottom - top)
    }

    companion object {
        const val MinWidth = 200
        const val MinHeight = 120
        private const val MoveThreshold = 4f
        private const val DockPreviewOpacity = 0.72f
    }
}
