package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.Composable
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeScale
import ru.hollowhorizon.hollowengine.client.ui.ide.hollowIdeWorldPoint
import ru.hollowhorizon.hollowengine.client.ui.render.MinecraftUiRenderer
import ru.hollowhorizon.hollowengine.client.ui.render.UiRenderTarget
import ru.hollowhorizon.hollowengine.client.ui.style.CompiledHss

class HollowUiWorldOverlay(
    stylesheet: CompiledHss? = null,
    private val pipelineFrames: Boolean = false,
    private val pointerOverride: () -> Pair<Float, Float>? = { null },
    private val cursorOverride: () -> UiCursorShape? = { null },
    private val manageCursor: Boolean = true,
) : AutoCloseable {
    private val surface = HollowUiSurface(stylesheet = stylesheet)
    private val renderer = MinecraftUiRenderer()
    private val pipeline = PipelinedUiFrameBuilder()

    private var activeButton: Int? = null
    private var lastX = 0f
    private var lastY = 0f
    private var contentSet = false

    val runtime get() = surface.runtime

    fun setContent(content: @Composable () -> Unit) {
        contentSet = true
        surface.setContent { content() }
    }

    fun isMouseOver(x: Float, y: Float): Boolean {
        val (lx, ly) = logicalPoint(x, y)
        return surface.runtime.lastFrame?.hitsVisible(lx, ly) ?: false
    }

    fun handleMouseMove(x: Float, y: Float): Boolean {
        pipeline.await()
        val (lx, ly) = logicalPoint(x, y)
        val dx = lx - lastX
        val dy = ly - lastY
        lastX = lx
        lastY = ly
        val button = activeButton ?: return false
        return surface.runtime.mouseDragged(lx, ly, button, dx, dy, currentUiKeyModifiers())
    }

    fun handleMouseButton(x: Float, y: Float, button: Int, action: Int): Boolean {
        pipeline.await()
        val (lx, ly) = logicalPoint(x, y)
        lastX = lx
        lastY = ly
        return when (action) {
            GLFW.GLFW_PRESS -> {
                val handled = surface.runtime.mouseClicked(lx, ly, button, currentUiKeyModifiers())
                if (handled) activeButton = button
                handled
            }

            GLFW.GLFW_RELEASE -> {
                activeButton = null
                surface.runtime.mouseReleased(lx, ly, button, currentUiKeyModifiers())
            }

            else -> false
        }
    }

    fun handleMouseScroll(x: Float, y: Float, scrollX: Double, scrollY: Double): Boolean {
        pipeline.await()
        val (lx, ly) = logicalPoint(x, y)
        return surface.runtime.mouseScrolled(lx, ly, scrollX.toFloat(), scrollY.toFloat(), currentUiKeyModifiers())
    }

    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) return false
        pipeline.await()
        return surface.runtime.keyPressed(key, scanCode, modifiers, repeat = action == GLFW.GLFW_REPEAT)
    }

    fun handleChar(codePoint: Int, modifiers: Int): Boolean {
        pipeline.await()
        return surface.runtime.charTyped(codePoint.toChar(), modifiers)
    }

    fun render() {
        if (!contentSet) return
        val target = currentBlitTarget()
        val frameWidth = target.logicalWidth
        val frameHeight = target.logicalHeight
        val (px, py) = pointerOverride() ?: (lastX to lastY)
        val frame = (if (pipelineFrames) pipeline.take(frameWidth, frameHeight) else null)
            ?: surface.frame(frameWidth, frameHeight, px, py, System.nanoTime())
        renderer.render(frame, target)
        if (manageCursor) {
            val over = surface.runtime.lastFrame?.hitsVisible(px, py) == true
            UiCursorManager.claim(
                window = Minecraft.getInstance().window.window,
                owner = this,
                shape = cursorOverride() ?: surface.runtime.cursor.takeIf { over },
            )
        }
        if (pipelineFrames) {
            pipeline.schedule(frameWidth, frameHeight) {
                surface.frame(frameWidth, frameHeight, px, py, System.nanoTime())
            }
        }
    }

    override fun close() {
        pipeline.reset()
        renderer.close()
        surface.close()
    }

    /**
     * The pointer arrives in the pixels of whatever the world was drawn into, and the scale that
     * sizes this overlay's frame is what turns those into its logical coordinates.
     */
    private fun logicalPoint(x: Float, y: Float): Pair<Float, Float> {
        pointerOverride()?.let { return it }
        val point = hollowIdeWorldPoint(x, y)
        return point.x to point.y
    }

    private fun currentBlitTarget(): UiRenderTarget {
        val viewport = IntArray(4)
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
        val scale = HollowIdeScale.factor()
        val width = viewport[2].coerceAtLeast(1)
        val height = viewport[3].coerceAtLeast(1)
        return UiRenderTarget(
            framebufferId = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            x = viewport[0],
            y = viewport[1],
            width = width,
            height = height,
            logicalWidth = (width / scale).coerceAtLeast(1f),
            logicalHeight = (height / scale).coerceAtLeast(1f),
            scale = scale,
        )
    }
}
