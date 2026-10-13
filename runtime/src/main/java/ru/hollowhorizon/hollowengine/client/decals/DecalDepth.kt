package ru.hollowhorizon.hollowengine.client.decals

import com.mojang.blaze3d.platform.GlStateManager
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage

/**
 * A copy of the depth of whatever framebuffer is being drawn into, the world's or a panel's, for decals to read the
 * surfaces back from. A depth blit needs both sides in one format, and a panel keeps its depth in another than the
 * world does, so the copy takes the format of its source.
 */
internal class DepthCopy {
    private var framebuffer = 0
    private var texture = 0
    private var width = 0
    private var height = 0
    private var format = 0

    /** The copy, valid after [capture] returned true. */
    val textureId: Int get() = texture

    /** The size of the copy, which covers the framebuffer from its corner up to the far corner of the viewport. */
    val copyWidth: Int get() = width
    val copyHeight: Int get() = height

    /** Copies the depth of the bound framebuffer as it is now; false when it has none a copy can be made of. */
    fun capture(viewport: IntArray): Boolean {
        val source = GL33.glGetInteger(GL33.GL_DRAW_FRAMEBUFFER_BINDING)
        val read = GL33.glGetInteger(GL33.GL_READ_FRAMEBUFFER_BINDING)
        if (source == 0) return false
        val wanted = depthFormat(source) ?: return false.also { GlStateManager._glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, read) }
        ensure(viewport[0] + viewport[2], viewport[1] + viewport[3], wanted)

        val scissor = GL33.glIsEnabled(GL33.GL_SCISSOR_TEST)
        if (scissor) GL33.glDisable(GL33.GL_SCISSOR_TEST)
        GlStateManager._glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, source)
        GlStateManager._glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, framebuffer)
        GL33.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL33.GL_DEPTH_BUFFER_BIT, GL33.GL_NEAREST)
        GlStateManager._glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, source)
        GlStateManager._glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, read)
        if (scissor) GL33.glEnable(GL33.GL_SCISSOR_TEST)
        return true
    }

    /** The sized format the depth of [source] is kept in, or null when it has no depth to copy. */
    private fun depthFormat(source: Int): Int? {
        GlStateManager._glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, source)
        val target = GL33.GL_READ_FRAMEBUFFER
        val attachment = GL33.GL_DEPTH_ATTACHMENT
        val type = GL33.glGetFramebufferAttachmentParameteri(target, attachment, GL33.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
        val name = GL33.glGetFramebufferAttachmentParameteri(target, attachment, GL33.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
        return when (type) {
            GL33.GL_RENDERBUFFER -> {
                val bound = GL33.glGetInteger(GL33.GL_RENDERBUFFER_BINDING)
                GL33.glBindRenderbuffer(GL33.GL_RENDERBUFFER, name)
                val format = GL33.glGetRenderbufferParameteri(GL33.GL_RENDERBUFFER, GL33.GL_RENDERBUFFER_INTERNAL_FORMAT)
                GL33.glBindRenderbuffer(GL33.GL_RENDERBUFFER, bound)
                format
            }

            GL33.GL_TEXTURE -> {
                val bound = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D)
                GL33.glBindTexture(GL33.GL_TEXTURE_2D, name)
                val format = GL33.glGetTexLevelParameteri(GL33.GL_TEXTURE_2D, 0, GL33.GL_TEXTURE_INTERNAL_FORMAT)
                GL33.glBindTexture(GL33.GL_TEXTURE_2D, bound)
                format
            }

            else -> null
        }
    }

    private fun ensure(wantedWidth: Int, wantedHeight: Int, wantedFormat: Int) {
        if (texture != 0 && wantedWidth == width && wantedHeight == height && wantedFormat == format) return
        if (framebuffer == 0) framebuffer = GL33.glGenFramebuffers()
        if (texture != 0) GL33.glDeleteTextures(texture)
        width = wantedWidth.coerceAtLeast(1)
        height = wantedHeight.coerceAtLeast(1)
        format = wantedFormat
        texture = GL33.glGenTextures()

        val bound = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D)
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture)
        val stencil = format == GL33.GL_DEPTH24_STENCIL8 || format == GL33.GL_DEPTH32F_STENCIL8
        val pixelFormat = if (stencil) GL33.GL_DEPTH_STENCIL else GL33.GL_DEPTH_COMPONENT
        val pixelType = when (format) {
            GL33.GL_DEPTH24_STENCIL8 -> GL33.GL_UNSIGNED_INT_24_8
            GL33.GL_DEPTH32F_STENCIL8 -> GL33.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
            else -> GL33.GL_FLOAT
        }
        plainUnpack {
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, format, width, height, 0, pixelFormat, pixelType, 0L)
        }
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST)
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST)
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_COMPARE_MODE, GL33.GL_NONE)
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE)
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE)
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, bound)

        val drawn = GL33.glGetInteger(GL33.GL_DRAW_FRAMEBUFFER_BINDING)
        GlStateManager._glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, framebuffer)
        val attachment = if (stencil) GL33.GL_DEPTH_STENCIL_ATTACHMENT else GL33.GL_DEPTH_ATTACHMENT
        GL33.glFramebufferTexture2D(GL33.GL_DRAW_FRAMEBUFFER, attachment, GL33.GL_TEXTURE_2D, texture, 0)
        GL33.glDrawBuffer(GL33.GL_NONE)
        GlStateManager._glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, drawn)
    }
}

/**
 * The depths decals read: the frame as it is when they are drawn, and in the world the blocks alone, copied before
 * entities are drawn over them, so a decal knows a surface nearer than the blocks is an entity's and leaves it alone.
 */
@ClientOnly
internal object DecalDepth {
    val scene = DepthCopy()
    val blocks = DepthCopy()

    /** The frame [blocks] was copied in, so a copy left from another frame is not read. */
    var blocksFrame = Long.MIN_VALUE
        private set

    /** The last frame the world drew decals in: the blocks are copied only while there are decals to read them. */
    var drawnFrame = Long.MIN_VALUE

    private val viewport = IntArray(4)

    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_CUTOUT_BLOCKS || TickHandler.renderFrame - drawnFrame > 1) return
        GL33.glGetIntegerv(GL33.GL_VIEWPORT, viewport)
        if (blocks.capture(viewport)) blocksFrame = TickHandler.renderFrame
    }

    /** Whether [blocks] holds this frame and matches [scene] in size, so the two can be read side by side. */
    val blocksReady: Boolean
        get() = blocksFrame == TickHandler.renderFrame &&
                blocks.copyWidth == scene.copyWidth && blocks.copyHeight == scene.copyHeight
}
