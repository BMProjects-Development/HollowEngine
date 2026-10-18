package ru.hollowhorizon.hollowengine.client.ui.panels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mojang.blaze3d.pipeline.TextureTarget
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.HollowUiSurface
import ru.hollowhorizon.hollowengine.client.ui.render.MinecraftUiRenderer
import ru.hollowhorizon.hollowengine.client.ui.render.UiRenderTarget
import ru.hollowhorizon.hollowengine.client.ui.render.withOwnRenderTarget
import ru.hollowhorizon.hollowengine.common.ui.UiPanelDefinition
import ru.hollowhorizon.hollowengine.common.ui.UiPanelScope
import ru.hollowhorizon.hollowengine.common.ui.panels.UiPanelEventPacket
import java.util.concurrent.atomic.AtomicInteger

/**
 * One panel shown in the world: its composition, drawn into a texture of its own as often as its frame rate allows
 * while someone sees it, and the pointer and keys the player gives it while pointing at it.
 */
internal class UiPanelInstance(val definition: UiPanelDefinition, override val entity: Entity) : UiPanelScope, AutoCloseable {
    private val surface = HollowUiSurface()
    private val renderer = MinecraftUiRenderer()
    private var target: TextureTarget? = null

    /** What the world draws the panel with; it shows the last frame drawn into [target]. */
    val texture: ResourceLocation = ResourceLocation.fromNamespaceAndPath("hollowengine", "ui_panel/${Ids.incrementAndGet()}")
    private val textureView = PanelTexture()

    override var isHovered by mutableStateOf(false)
        private set

    /** Where the panel was in the world the last time it was drawn there: its plane, from its own corner. */
    var worldPlacement: Matrix4f? = null

    /** The frame the world last drew the panel in, and the attachment settings it was drawn with then. */
    var seenFrame = Long.MIN_VALUE
    var reach = 5f
    var interactive = true

    private var pointerX = -1f
    private var pointerY = -1f
    private var activeButton: Int? = null
    private var drawnAt = NEVER

    init {
        surface.setContent { definition.content(this@UiPanelInstance) }
        Minecraft.getInstance().textureManager.register(texture, textureView)
    }

    override fun send(payload: CompoundTag) {
        UiPanelEventPacket(entity.id, definition.id.toString(), payload).send()
    }

    /** Whether text the player types goes to the panel. */
    val isTyping: Boolean get() = surface.runtime.isAnyFocused

    /** Points the player at [x], [y] of the panel, in its pixels, or nowhere when null. */
    fun point(at: Pair<Float, Float>?) {
        val hovered = at != null
        if (isHovered != hovered) isHovered = hovered
        if (at == null) {
            if (activeButton != null) release(activeButton!!)
            pointerX = -1f
            pointerY = -1f
            if (isTyping) surface.runtime.unfocus()
            return
        }
        val (x, y) = at
        val button = activeButton
        if (button != null && (x != pointerX || y != pointerY)) {
            surface.runtime.mouseDragged(x, y, button, x - pointerX, y - pointerY, 0)
        }
        pointerX = x
        pointerY = y
    }

    fun press(button: Int): Boolean {
        if (!isHovered) return false
        activeButton = button
        surface.runtime.mouseClicked(pointerX, pointerY, button, 0)
        if (!definition.keyboard && isTyping) surface.runtime.unfocus()
        return true
    }

    fun release(button: Int): Boolean {
        if (activeButton != button) return false
        activeButton = null
        surface.runtime.mouseReleased(pointerX, pointerY, button, 0)
        return true
    }

    fun scroll(scrollX: Double, scrollY: Double): Boolean {
        if (!isHovered) return false
        surface.runtime.mouseScrolled(pointerX, pointerY, scrollX.toFloat(), scrollY.toFloat(), 0)
        return true
    }

    /** Takes a key while the player types into the panel, when it takes keys at all; Escape stops typing. */
    fun key(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean {
        if (!definition.keyboard || !isTyping) return false
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) return true
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            surface.runtime.unfocus()
            return true
        }
        surface.runtime.keyPressed(key, scanCode, modifiers, repeat = action == GLFW.GLFW_REPEAT)
        return true
    }

    fun char(codePoint: Int, modifiers: Int): Boolean {
        if (!definition.keyboard || !isTyping) return false
        surface.runtime.charTyped(codePoint.toChar(), modifiers)
        return true
    }

    /** Draws the panel again when its frame rate allows; on the render thread, outside of drawing the world. */
    fun render(nowNanos: Long) {
        if (drawnAt != NEVER && nowNanos - drawnAt < NANOS_PER_SECOND / definition.frameRate) return
        drawnAt = nowNanos
        val width = definition.width
        val height = definition.height
        val drawn = target?.takeIf { it.width == width * SCALE && it.height == height * SCALE }
            ?: TextureTarget(width * SCALE, height * SCALE, false, Minecraft.ON_OSX).also {
                target?.destroyBuffers()
                target = it
            }
        textureView.show(drawn.colorTextureId)

        if (definition.rebuildEveryFrame) surface.advanceFrameTime(nowNanos)
        val frame = surface.frame(width.toFloat(), height.toFloat(), pointerX, pointerY, nowNanos)
        withOwnRenderTarget {
            val background = definition.background
            drawn.setClearColor(
                (background shr 16 and 0xFF) / 255f, (background shr 8 and 0xFF) / 255f, (background and 0xFF) / 255f, 1f,
            )
            drawn.clear(Minecraft.ON_OSX)
            renderer.render(
                frame,
                UiRenderTarget(
                    framebufferId = drawn.frameBufferId,
                    x = 0,
                    y = 0,
                    width = drawn.width,
                    height = drawn.height,
                    logicalWidth = width.toFloat(),
                    logicalHeight = height.toFloat(),
                    scale = SCALE.toFloat(),
                ),
            )
        }
    }

    override fun close() {
        Minecraft.getInstance().textureManager.release(texture)
        renderer.close()
        surface.close()
        target?.destroyBuffers()
        target = null
    }

    /** The texture manager's view of [target]: it neither loads nor frees it, the panel does. */
    private class PanelTexture : AbstractTexture() {
        init {
            id = 0
        }

        fun show(texture: Int) {
            id = texture
        }

        override fun load(resourceManager: ResourceManager) = Unit

        override fun releaseId() = Unit
    }

    private companion object {
        /** How many texels a pixel of the panel gets, so its text stays sharp up close. */
        const val SCALE = 2

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** When a panel that was never drawn was drawn: a mark, not a time to subtract from. */
        const val NEVER = Long.MIN_VALUE

        val Ids = AtomicInteger()
    }
}
