package ru.hollowhorizon.hollowengine.client.vfx.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxGlow.begin

/**
 * The glow of the frame: surfaces with a glow draw a second time into a float texture that shares the
 * depth of the frame, so the scene hides it where it hides them, and that texture is blurred down a
 * pyramid of halving levels and added over the frame.
 */
object VfxGlow {
    private const val DOWNSAMPLE = "hollowengine:vfx/glow/downsample"
    private const val UPSAMPLE = "hollowengine:vfx/glow/upsample"

    private const val MAX_LEVELS = 6
    private const val MIN_LEVEL_SIZE = 8

    /** How much of the coarser level each finer one takes on the way up; more spreads the glow wider. */
    private const val SCATTER = 0.7f

    /** The world and the editor preview draw into targets of different sizes in one frame; both stay. */
    private const val KEPT_CHAINS = 3

    private val chains = object : LinkedHashMap<Long, GlowChain>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, GlowChain>): Boolean =
            (size > KEPT_CHAINS).also { if (it) eldest.value.delete() }
    }

    private var current: GlowChain? = null
    private var warned = false

    /**
     * Binds the glow surface for [frame], cleared and over its depth. Returns false when that cannot
     * be done, and [frame] is bound again.
     */
    fun begin(frame: RenderTarget): Boolean {
        val chain = chains.getOrPut(frame.width.toLong() shl 32 or frame.height.toLong()) {
            GlowChain(frame.width, frame.height)
        }
        chain.base.bind()
        GL33.glFramebufferTexture2D(
            GL33.GL_FRAMEBUFFER, GL33.GL_DEPTH_ATTACHMENT, GL33.GL_TEXTURE_2D, frame.depthTextureId, 0,
        )
        if (GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) != GL33.GL_FRAMEBUFFER_COMPLETE) {
            if (!warned) HollowEngine.LOGGER.warn("Effect glow is off: the frame depth cannot be shared")
            warned = true
            detachDepth()
            frame.bindWrite(true)
            return false
        }
        GL33.glClearBufferfv(GL33.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f))
        current = chain
        return true
    }

    /** Blurs what was drawn since [begin] and adds it over [frame], which is bound afterward. */
    fun finish(frame: RenderTarget) {
        val chain = current ?: return
        current = null
        chain.base.bind()
        detachDepth()

        val down = VfxShaders.get(DOWNSAMPLE, DefaultVertexFormat.POSITION_TEX)
        val up = VfxShaders.get(UPSAMPLE, DefaultVertexFormat.POSITION_TEX)
        if (down == null || up == null) {
            frame.bindWrite(true)
            return
        }

        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        try {
            RenderSystem.disableBlend()
            var source = chain.base
            chain.levels.forEachIndexed { index, level ->
                level.bind()
                VfxScreenQuad.draw(down) { shader ->
                    shader.setSampler("Source", source.texture)
                    shader.safeGetUniform("SourceTexel").set(1f / source.width, 1f / source.height)
                    shader.safeGetUniform("FirstPass").set(if (index == 0) 1f else 0f)
                }
                source = level
            }

            RenderSystem.enableBlend()
            GL33.glBlendColor(0f, 0f, 0f, SCATTER)
            RenderSystem.blendFunc(
                GlStateManager.SourceFactor.CONSTANT_ALPHA, GlStateManager.DestFactor.ONE_MINUS_CONSTANT_ALPHA,
            )
            for (index in chain.levels.lastIndex downTo 1) {
                chain.levels[index - 1].bind()
                upsample(up, chain.levels[index])
            }
            GL33.glBlendColor(0f, 0f, 0f, 0f)

            frame.bindWrite(true)
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE)
            upsample(up, chain.levels.firstOrNull() ?: chain.base)
        } finally {
            VfxMaterialStates.restore()
        }
    }

    private fun upsample(shader: ShaderInstance, source: GlowSurface) {
        VfxScreenQuad.draw(shader) { bound ->
            bound.setSampler("Source", source.texture)
            bound.safeGetUniform("SourceTexel").set(1f / source.width, 1f / source.height)
            bound.safeGetUniform("Strength").set(1f)
        }
    }

    /** The depth belongs to the frame, which may be resized or deleted while this is not looking. */
    private fun detachDepth() {
        GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_DEPTH_ATTACHMENT, GL33.GL_TEXTURE_2D, 0, 0)
    }

    /** The full-size surface and the halving levels under it, for one size of frame. */
    private class GlowChain(width: Int, height: Int) {
        val base = GlowSurface(width, height)
        val levels: List<GlowSurface> = buildList {
            var levelWidth = width / 2
            var levelHeight = height / 2
            while (size < MAX_LEVELS && levelWidth >= MIN_LEVEL_SIZE && levelHeight >= MIN_LEVEL_SIZE) {
                add(GlowSurface(levelWidth, levelHeight))
                levelWidth /= 2
                levelHeight /= 2
            }
        }

        fun delete() {
            base.delete()
            levels.forEach(GlowSurface::delete)
        }
    }

    /** A half-float color texture and the framebuffer that draws into it. */
    private class GlowSurface(val width: Int, val height: Int) {
        val texture: Int = GlStateManager._genTexture()
        private val framebuffer: Int = GlStateManager.glGenFramebuffers()

        init {
            GlStateManager._bindTexture(texture)
            GlStateManager._texImage2D(
                GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA16F, width, height, 0, GL33.GL_RGBA, GL33.GL_FLOAT, null,
            )
            GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_LINEAR)
            GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_LINEAR)
            GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE)
            GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE)
            GlStateManager._glBindFramebuffer(GL33.GL_FRAMEBUFFER, framebuffer)
            GlStateManager._glFramebufferTexture2D(
                GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, texture, 0,
            )
        }

        fun bind() {
            GlStateManager._glBindFramebuffer(GL33.GL_FRAMEBUFFER, framebuffer)
            RenderSystem.viewport(0, 0, width, height)
        }

        fun delete() {
            GlStateManager._deleteTexture(texture)
            GlStateManager._glDeleteFramebuffers(framebuffer)
        }
    }
}

/** A quad over the whole target, for the passes that work on a picture rather than on geometry. */
internal object VfxScreenQuad {
    private val SCREEN = VfxView(Matrix4f(), Matrix4f(), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(), 0f)

    fun draw(shader: ShaderInstance, prepare: (ShaderInstance) -> Unit) {
        val builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        builder.addVertex(-1f, -1f, 0f).setUv(0f, 0f)
        builder.addVertex(1f, -1f, 0f).setUv(1f, 0f)
        builder.addVertex(1f, 1f, 0f).setUv(1f, 1f)
        builder.addVertex(-1f, 1f, 0f).setUv(0f, 1f)
        val mesh = builder.build() ?: return
        SCREEN.drawImmediate(mesh, shader, prepare)
    }
}
