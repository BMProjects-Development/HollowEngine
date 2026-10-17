package ru.hollowhorizon.hollowengine.client.vfx.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxShaders.surface
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.VfxMaterialSpec

/**
 * The blend, depth and culling state a batch of surfaces is drawn with.
 */
internal object VfxMaterialStates {
    fun apply(key: VfxBatchKey, premultiplied: Boolean) {
        applyBlend(key.blend, premultiplied)
        if (key.cull) RenderSystem.enableCull() else RenderSystem.disableCull()
        if (key.depthTest) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
        RenderSystem.depthMask(key.depthWrite)
    }

    /** What a surface draws its glow with: added up, tested against the frame depth, never written to it. */
    fun applyGlow(key: VfxBatchKey) {
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL33.GL_FUNC_ADD)
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE)
        if (key.cull) RenderSystem.enableCull() else RenderSystem.disableCull()
        if (key.depthTest) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
    }

    /**
     * The program a surface draws its glow with: the engine's, or a shader of the author that
     * declares `GlowPass` and so knows what to write in it; null when there is none.
     */
    fun glowShader(custom: String?, engine: ShaderInstance, surface: VfxSurface): ShaderInstance? {
        custom ?: return engine
        if (VfxShaderDeclarations.of(custom)?.drawsGlow != true) return null
        return VfxShaders.surface(custom, surface)
    }

    fun restore() {
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL33.GL_FUNC_ADD)
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableCull()
    }

    fun texture(location: String): Int = Minecraft.getInstance().textureManager.getTexture(location.rl).id

    /** Binds what every shader of a surface may sample besides its texture: light and the scene. */
    fun bindCommonSamplers(shader: ShaderInstance, texture: Int) {
        shader.setSampler("Sampler0", texture)
        shader.setSampler("Sampler2", HollowModelManager.lightTexture.id)
        VfxSceneTextures.bind(shader)
    }

    private fun applyBlend(blend: VfxBatchBlend, premultiplied: Boolean) {
        RenderSystem.blendEquation(GL33.GL_FUNC_ADD)
        when (blend) {
            VfxBatchBlend.OPAQUE -> RenderSystem.disableBlend()
            VfxBatchBlend.MIXED -> {
                RenderSystem.enableBlend()
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA)
            }

            VfxBatchBlend.BLEND -> {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
            }

            VfxBatchBlend.ADDITIVE -> {
                RenderSystem.enableBlend()
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
            }

            VfxBatchBlend.MULTIPLY -> {
                RenderSystem.enableBlend()
                if (premultiplied) {
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO)
                } else {
                    RenderSystem.blendFunc(
                        GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
                    )
                }
            }
        }
    }
}

/**
 * How much a material glows: what the effect set, or once for a material graph that has an emission
 * when the effect set nothing, since the emission is there to glow.
 */
internal fun VfxMaterialSpec.effectiveGlow(): Float = when {
    glow > 0f -> glow
    VfxGraphMaterials.emits(shader) -> 1f
    else -> 0f
}

/**
 * The core shaders effects name in their materials and post effects, loaded on first use.
 */
object VfxShaders {
    private val loaded = HashMap<Pair<String, VertexFormat>, ShaderInstance?>()

    fun get(location: String, format: VertexFormat): ShaderInstance? = loaded.getOrPut(location to format) {
        load(location, format)
    }

    /** What [surface] draws with when its material names [location]: a core shader, or a material graph. */
    fun surface(location: String, surface: VfxSurface): ShaderInstance? =
        if (VfxGraphMaterials.isGraph(location)) VfxGraphMaterials.program(location, surface) else get(location, surface.format)

    /** What a post effect naming [location] draws the frame with: a core shader, or a post effect graph. */
    fun post(location: String): ShaderInstance? =
        if (VfxGraphMaterials.isGraph(location)) VfxGraphMaterials.postProgram(location) else get(location, DefaultVertexFormat.POSITION_TEX)

    /** Resource packs changed: every shader is read again the next time it is drawn. */
    fun clear() {
        loaded.values.filterNotNull().forEach(ShaderInstance::close)
        loaded.clear()
        VfxGraphMaterials.clear()
        VfxShaderDeclarations.clear()
        VfxQuadRenderer.invalidate()
        VfxMeshRenderer.invalidate()
    }

    private fun load(location: String, format: VertexFormat): ShaderInstance? {
        val id = ResourceLocation.tryParse(location) ?: return null
        val alias = "$ALIAS_FOLDER/${id.namespace}/${id.path}"
        val resources = Minecraft.getInstance().resourceManager
        val provider = ResourceProvider { wanted ->
            val path = wanted.path
            val prefix = "shaders/core/$ALIAS_FOLDER/"
            if (wanted.namespace == ResourceLocation.DEFAULT_NAMESPACE && path.startsWith(prefix)) {
                val rest = path.removePrefix(prefix)
                val namespace = rest.substringBefore('/')
                val file = "shaders/core/${rest.substringAfter('/')}"
                resources.getResource(ResourceLocation.fromNamespaceAndPath(namespace, file))
            } else {
                resources.getResource(wanted)
            }
        }
        return try {
            ShaderInstance(provider, alias, format)
        } catch (e: Exception) {
            HollowEngine.LOGGER.warn("Could not load the effect shader {}: {}", location, e.message)
            null
        }
    }

    private const val ALIAS_FOLDER = "hollowengine_vfx"
}

/**
 * Copies of the color and depth of the frame being drawn into, for shaders that read the scene:
 * `SceneColor` for distortion and post effects, `SceneDepth` for soft intersections.
 */
object VfxSceneTextures {
    private var copy: TextureTarget? = null

    /** Copies what [source] holds right now; everything drawn afterwards is not in the copy. */
    fun capture(source: RenderTarget) {
        val width = source.width
        val height = source.height
        val target = copy?.takeIf { it.width == width && it.height == height }
            ?: TextureTarget(width, height, true, Minecraft.ON_OSX).also {
                copy?.destroyBuffers()
                copy = it
            }

        val scissor = GL33.glIsEnabled(GL33.GL_SCISSOR_TEST)
        if (scissor) GL33.glDisable(GL33.GL_SCISSOR_TEST)
        GlStateManager._glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, source.frameBufferId)
        GlStateManager._glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, target.frameBufferId)
        GL33.glBlitFramebuffer(
            0, 0, width, height, 0, 0, width, height,
            GL33.GL_COLOR_BUFFER_BIT or GL33.GL_DEPTH_BUFFER_BIT, GL33.GL_NEAREST,
        )
        if (scissor) GL33.glEnable(GL33.GL_SCISSOR_TEST)
        source.bindWrite(false)
    }

    /** Binds the copies, and sets `ScreenSize` to their size: the preview draws into less than the window. */
    fun bind(shader: ShaderInstance) {
        val target = copy ?: return
        shader.setSampler("SceneColor", target.colorTextureId)
        shader.setSampler("SceneDepth", target.depthTextureId)
        shader.safeGetUniform("ScreenSize").set(target.width.toFloat(), target.height.toFloat())
    }
}

/**
 * What the render system sets on every program, but with the matrices and the clock of this view
 * rather than the ones it holds, which in the editor preview are the panel's and the paused world's.
 */
internal fun VfxView.setDefaultUniforms(shader: ShaderInstance, mode: VertexFormat.Mode) {
    shader.setDefaultUniforms(mode, modelView, projection, Minecraft.getInstance().window)
    shader.safeGetUniform("ShaderTime").set(time)
}

/**
 * Draws a finished buffer with [shader] and the matrices of [view], rather than whatever the render
 * system holds, which in the editor preview is the matrices of the panel.
 */
internal fun VfxView.drawImmediate(mesh: MeshData, shader: ShaderInstance, prepare: (ShaderInstance) -> Unit) {
    val buffer = mesh.drawState().format().immediateDrawVertexBuffer
    buffer.bind()
    buffer.upload(mesh)
    setDefaultUniforms(shader, mesh.drawState().mode())
    prepare(shader)
    shader.apply()
    buffer.draw()
    shader.clear()
    VertexBuffer.unbind()
}
