package ru.hollowhorizon.hollowengine.client.ui.ide

import net.minecraft.client.Minecraft
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWDropCallback
import org.lwjgl.system.MemoryStack
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.docking.DockDropTarget
import ru.hollowhorizon.hollowengine.client.ui.docking.dockDropTargetAt
import ru.hollowhorizon.hollowengine.client.ui.render.GlfwUiPresenter
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class HollowIdeNativeWindow(
    private val handle: Long,
    customChrome: Boolean,
    val surface: HollowUiSurface = HollowUiSurface(),
) : AutoCloseable {
    val dragAndDrop = UiDragAndDropState()
    val chrome = GlfwDockWindowChrome(handle, customChrome)
    private val presenter = GlfwUiPresenter(handle)
    private var pressedButton: Int? = null
    private var lastCursor = UiCursorShape.DEFAULT
    private var lastTitle = ""
    private var pointer = HollowIdeOverlayPoint(-1f, -1f)
    private var pointerScale = HollowIdeOverlayPoint(1f, 1f)

    fun consumeCloseRequest(): Boolean {
        if (!GLFW.glfwWindowShouldClose(handle)) return false
        GLFW.glfwSetWindowShouldClose(handle, false)
        return true
    }

    val focused: Boolean get() = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE
    val hovered: Boolean get() = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE

    fun cursorRelativeTo(window: Long): HollowIdeOverlayPoint {
        val point = desktopCursorPosition(handle)
        val origin = windowPosition(window)
        return HollowIdeOverlayPoint(point.x - origin.x, point.y - origin.y)
    }

    fun hoveredDockTarget(): DockDropTarget? = cursorPosition(handle) { x, y ->
        val point = localPointer(x, y)
        surface.runtime.lastFrame?.dockDropTargetAt(point.x, point.y)
    }

    fun show(
        beforeInput: () -> Unit,
        onFocus: (Boolean) -> Unit,
        onUnhandledKey: (Int, Int) -> Unit,
        onDockRelease: () -> Boolean,
        content: HollowUiContent,
    ) {
        surface.setContent(content)
        GLFW.glfwSetWindowFocusCallback(handle) { _, focused ->
            beforeInput()
            onFocus(focused)
            if (!focused) {
                pressedButton = null
                chrome.end()
                surface.runtime.cancelPointerInput()
                surface.runtime.unfocus()
                dragAndDrop.cancel()
            }
        }
        GLFW.glfwSetCursorPosCallback(handle) { _, x, y ->
            beforeInput()
            val previous = pointer
            pointer = localPointer(x, y)
            pressedButton?.let { button ->
                surface.runtime.mouseDragged(
                    pointer.x, pointer.y, button, pointer.x - previous.x, pointer.y - previous.y,
                    currentUiKeyModifiers(handle),
                )
            }
        }
        GLFW.glfwSetCursorEnterCallback(handle) { _, entered ->
            if (!entered && pressedButton == null) pointer = HollowIdeOverlayPoint(-1f, -1f)
        }
        GLFW.glfwSetMouseButtonCallback(handle) { _, button, action, modifiers ->
            beforeInput()
            pointer = cursorPosition(handle, ::localPointer)
            when {
                action == GLFW.GLFW_PRESS -> {
                    onFocus(true)
                    if (surface.runtime.mouseClicked(pointer.x, pointer.y, button, modifiers)) pressedButton = button
                }

                action == GLFW.GLFW_RELEASE && pressedButton == button -> {
                    pressedButton = null
                    try {
                        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && onDockRelease()) {
                            surface.runtime.cancelPointerInput()
                        } else {
                            surface.runtime.mouseReleased(pointer.x, pointer.y, button, modifiers)
                        }
                    } finally {
                        chrome.end()
                    }
                }
            }
        }
        GLFW.glfwSetScrollCallback(handle) { _, dx, dy ->
            beforeInput()
            pointer = cursorPosition(handle, ::localPointer)
            surface.runtime.mouseScrolled(
                pointer.x,
                pointer.y,
                dx.toFloat(),
                dy.toFloat(),
                currentUiKeyModifiers(handle)
            )
        }
        GLFW.glfwSetKeyCallback(handle) { _, key, scanCode, action, modifiers ->
            if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
                beforeInput()
                val repeat = action == GLFW.GLFW_REPEAT
                if (!surface.runtime.keyPressed(key, scanCode, modifiers, repeat) && !repeat) {
                    onUnhandledKey(key, modifiers)
                }
            }
        }
        GLFW.glfwSetCharCallback(handle) { _, codePoint ->
            beforeInput()
            val modifiers = currentUiKeyModifiers(handle)
            Character.toChars(codePoint).forEach { surface.runtime.charTyped(it, modifiers) }
        }
        GLFW.glfwSetDropCallback(handle) { _, count, paths ->
            beforeInput()
            val files = List(count) { File(GLFWDropCallback.getName(paths, it)) }
            pointer = cursorPosition(handle, ::localPointer)
            dragAndDrop.begin(UiDragItem(HollowIdeExternalFileDrag(files)), pointer.x, pointer.y)
            dragAndDrop.drop()
        }
        GLFW.glfwShowWindow(handle)
    }

    fun focus() {
        if (!focused) {
            if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) GLFW.glfwRestoreWindow(handle)
            GLFW.glfwFocusWindow(handle)
        }
    }

    fun render(title: String) {
        if (title != lastTitle) {
            GLFW.glfwSetWindowTitle(handle, title)
            lastTitle = title
        }
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) return
        chrome.update()
        MemoryStack.stackPush().use { stack ->
            val width = stack.mallocInt(1)
            val height = stack.mallocInt(1)
            GLFW.glfwGetFramebufferSize(handle, width, height)
            val framebufferWidth = width[0]
            val framebufferHeight = height[0]
            if (framebufferWidth <= 0 || framebufferHeight <= 0) return
            GLFW.glfwGetWindowSize(handle, width, height)
            if (width[0] <= 0 || height[0] <= 0) return

            val scale = HollowIdeScale.detachedFactor(framebufferWidth, framebufferHeight)
            val logicalWidth = ceil(framebufferWidth / scale)
            val logicalHeight = ceil(framebufferHeight / scale)
            pointerScale = HollowIdeOverlayPoint(logicalWidth / width[0], logicalHeight / height[0])
            pointer = if (pressedButton != null || hovered) {
                cursorPosition(handle, ::localPointer)
            } else {
                HollowIdeOverlayPoint(-1f, -1f)
            }
            val now = System.nanoTime()
            val frame = PipelinedUiFrameBuilder.buildSerialized {
                surface.frame(logicalWidth, logicalHeight, pointer.x, pointer.y, now)
            }
            presenter.render(frame, framebufferWidth, framebufferHeight, logicalWidth, logicalHeight)
            val cursor = surface.runtime.cursor
            if (cursor != lastCursor) {
                UiCursorManager.setWindowCursor(handle, cursor)
                lastCursor = cursor
            }
        }
    }

    private fun localPointer(x: Double, y: Double) =
        HollowIdeOverlayPoint((x * pointerScale.x).toFloat(), (y * pointerScale.y).toFloat())

    override fun close() {
        try {
            surface.close()
        } finally {
            try {
                presenter.close()
            } finally {
                glfwFreeCallbacks(handle)
                GLFW.glfwDestroyWindow(handle)
            }
        }
    }

    companion object {
        fun create(title: String, width: Float, height: Float, position: HollowIdeOverlayPoint): HollowIdeNativeWindow {
            val main = Minecraft.getInstance().window.window
            val size = hollowIdeCursorPoint(width, height)
            val placement = windowPlacement(main, position, size)
            val customChrome = GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND
            GLFW.glfwDefaultWindowHints()
            val handle = try {
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
                GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE)
                GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, if (customChrome) GLFW.GLFW_FALSE else GLFW.GLFW_TRUE)
                for (hint in SharedContextHints) {
                    GLFW.glfwWindowHint(hint, GLFW.glfwGetWindowAttrib(main, hint))
                }
                GLFW.glfwCreateWindow(placement.width, placement.height, title, 0L, main)
            } finally {
                GLFW.glfwDefaultWindowHints()
            }
            check(handle != 0L) { "GLFW could not create an IDE panel window" }
            try {
                applyMinecraftWindowIcon(handle)
                GLFW.glfwSetWindowSizeLimits(
                    handle,
                    GlfwDockWindowChrome.MinWidth,
                    GlfwDockWindowChrome.MinHeight,
                    GLFW.GLFW_DONT_CARE,
                    GLFW.GLFW_DONT_CARE,
                )
                placement.position?.let { GLFW.glfwSetWindowPos(handle, it.x.roundToInt(), it.y.roundToInt()) }
                return HollowIdeNativeWindow(handle, customChrome)
            } catch (failure: Throwable) {
                GLFW.glfwDestroyWindow(handle)
                throw failure
            }
        }

        private val SharedContextHints = intArrayOf(
            GLFW.GLFW_CLIENT_API, GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_CONTEXT_VERSION_MAJOR,
            GLFW.GLFW_CONTEXT_VERSION_MINOR, GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_FORWARD_COMPAT,
        )

        private data class WindowPlacement(val width: Int, val height: Int, val position: HollowIdeOverlayPoint?)

        private fun windowPlacement(
            main: Long,
            pointer: HollowIdeOverlayPoint,
            size: HollowIdeOverlayPoint,
        ): WindowPlacement = MemoryStack.stackPush().use { stack ->
            val width = size.x.roundToInt().coerceAtLeast(GlfwDockWindowChrome.MinWidth)
            val height = size.y.roundToInt().coerceAtLeast(GlfwDockWindowChrome.MinHeight)
            // Wayland delegates window positioning to the compositor.
            if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) return WindowPlacement(width, height, null)
            val x = stack.callocInt(1)
            val y = stack.callocInt(1)
            GLFW.glfwGetWindowPos(main, x, y)
            val desktopX = x[0] + pointer.x
            val desktopY = y[0] + pointer.y
            val monitors = GLFW.glfwGetMonitors()
            val areaWidth = stack.callocInt(1)
            val areaHeight = stack.callocInt(1)
            while (monitors != null && monitors.hasRemaining()) {
                GLFW.glfwGetMonitorWorkarea(monitors.get(), x, y, areaWidth, areaHeight)
                if (desktopX < x[0] || desktopX >= x[0] + areaWidth[0] || desktopY < y[0] || desktopY >= y[0] + areaHeight[0]) continue
                val fittedWidth = width.coerceAtMost(areaWidth[0])
                val fittedHeight = height.coerceAtMost(areaHeight[0])
                return WindowPlacement(
                    fittedWidth, fittedHeight,
                    HollowIdeOverlayPoint(
                        desktopX.coerceIn(x[0].toFloat(), (x[0] + areaWidth[0] - fittedWidth).toFloat()),
                        desktopY.coerceIn(y[0].toFloat(), (y[0] + areaHeight[0] - fittedHeight).toFloat()),
                    ),
                )
            }
            WindowPlacement(width, height, null)
        }
    }
}

internal inline fun <T> cursorPosition(window: Long, read: (Double, Double) -> T): T =
    MemoryStack.stackPush().use { stack ->
        val x = stack.mallocDouble(1)
        val y = stack.mallocDouble(1)
        GLFW.glfwGetCursorPos(window, x, y)
        read(x[0], y[0])
    }

private fun windowPosition(window: Long): HollowIdeOverlayPoint = MemoryStack.stackPush().use { stack ->
    val x = stack.mallocInt(1)
    val y = stack.mallocInt(1)
    GLFW.glfwGetWindowPos(window, x, y)
    HollowIdeOverlayPoint(x[0].toFloat(), y[0].toFloat())
}

internal fun desktopCursorPosition(window: Long): HollowIdeOverlayPoint {
    val origin = windowPosition(window)
    return cursorPosition(window) { x, y -> HollowIdeOverlayPoint(origin.x + x.toFloat(), origin.y + y.toFloat()) }
}
