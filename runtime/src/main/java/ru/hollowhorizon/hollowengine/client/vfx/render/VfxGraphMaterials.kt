package ru.hollowhorizon.hollowengine.client.vfx.render

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxGraphMaterials.builds
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kinds of surface an effect draws, each with the vertex layout its programs read and the
 * template a material graph is put into for it.
 */
enum class VfxSurface(val format: VertexFormat, internal val template: ShaderGraphTemplate) {
    /** Billboards and other planes, one instanced quad per particle. */
    PLANE(DefaultVertexFormat.POSITION_TEX, ShaderGraphTemplates.PARTICLE),

    /** The built-in meshes, instanced. */
    MESH(DefaultVertexFormat.POSITION_TEX, ShaderGraphTemplates.MESH),

    /** Trails and beams, built every frame in the vanilla particle format. */
    RIBBON(DefaultVertexFormat.PARTICLE, ShaderGraphTemplates.RIBBON),
}

/**
 * Materials made in the shader graph editor, as effects use them: a material or a post effect names
 * one by its location, `namespace:materials/fire.material`, where it would name a core shader.
 */
object VfxGraphMaterials {
    private class Loaded(val graph: ShaderGraph?, val code: ShaderGraphCode?) {
        val programs = EnumMap<VfxSurface, ShaderInstance?>(VfxSurface::class.java)
        var post: ShaderInstance? = null
        var postBuilt = false

        fun close() {
            programs.values.forEach { it?.close() }
            post?.close()
        }
    }

    private val loaded = HashMap<String, Loaded>()

    /** Programs are cached by the names of their stages, so every build gets names of its own. */
    private val builds = AtomicInteger()

    fun isGraph(location: String?): Boolean = location?.endsWith(ShaderGraph.EXTENSION) == true

    /** The graph at [location] as the packs have it now, or null when there is none or it does not read. */
    fun read(location: String): ShaderGraph? {
        val id = ResourceLocation.tryParse(location) ?: return null
        val resource = Minecraft.getInstance().resourceManager.getResource(id).orElse(null) ?: return null
        return runCatching {
            resource.openAsReader().use { ShaderGraphFormat.read(it.readText()) }
        }.onFailure { HollowEngine.LOGGER.warn("Could not read the material {}: {}", location, it.message) }.getOrNull()
    }

    /** What an effect gives values to: a uniform per number or vector property, a sampler per texture. */
    fun declaration(graph: ShaderGraph): VfxShaderDeclaration {
        val textures = graph.properties.filter { it.type == ShaderType.TEXTURE }
        val uniforms = graph.properties.filter { it.type.isVector }.map { property ->
            val defaults = FloatArray(property.type.width) {
                property.default.getOrElse(it) {
                    property.default.lastOrNull() ?: 0f
                }
            }
            VfxShaderUniform(property.name, "float", property.type.width, defaults)
        }
        return VfxShaderDeclaration(
            uniforms = uniforms,
            samplers = textures.map { it.name },
            drawsGlow = graph.target == ShaderTarget.SURFACE,
            glslNames = graph.properties.associate { it.name to propertyUniform(it.name) },
            samplerDefaults = textures.filter { it.texture.isNotBlank() }.associate { it.name to it.texture },
            target = graph.target,
        )
    }

    /**
     * The program [surface] draws the material at [location] with, or null when it cannot be built or
     * is not a surface.
     */
    fun program(location: String, surface: VfxSurface): ShaderInstance? {
        val entry = loaded(location)
        return entry.programs.getOrPut(surface) {
            val graph = entry.graph?.takeIf { it.target == ShaderTarget.SURFACE } ?: return@getOrPut null
            val code = entry.code ?: return@getOrPut null
            build(location, graph, code, surface)
        }
    }

    /** The program of the post effect at [location], or null when it cannot be built or is not a post effect. */
    fun postProgram(location: String): ShaderInstance? {
        val entry = loaded(location)
        if (!entry.postBuilt) {
            entry.postBuilt = true
            val graph = entry.graph?.takeIf { it.target == ShaderTarget.POST }
            val code = entry.code
            if (graph != null && code != null) entry.post = buildPost(location, graph, code)
        }
        return entry.post
    }

    /** Whether the material links anything into its emission, which glows even with glow of effect left at 0. */
    fun emits(location: String?): Boolean {
        if (location == null || !isGraph(location)) return false
        return loaded(location).code?.linkedOutputs?.contains(SurfaceOutputs.EMISSION) == true
    }

    /** Material at [location] was saved: it is read and built again next time it is drawn. */
    fun changed(location: String) {
        loaded.remove(location)?.close()
        VfxShaderDeclarations.invalidate(location)
        VfxQuadRenderer.invalidate()
        VfxMeshRenderer.invalidate()
    }

    /** Where an IDE file of a material, `assets/<namespace>/<path>`, is found in the packs. */
    fun locationOf(path: String): String = path.substringAfter("assets/").replaceFirst("/", ":")

    fun clear() {
        loaded.values.forEach(Loaded::close)
        loaded.clear()
    }

    private fun loaded(location: String): Loaded = loaded.getOrPut(location) {
        val graph = read(location)
        Loaded(graph, graph?.let(ShaderGraphCompiler::compile))
    }

    private fun build(
        location: String,
        graph: ShaderGraph,
        code: ShaderGraphCode,
        surface: VfxSurface,
    ): ShaderInstance? {
        if (code.outputs.isEmpty()) return null
        val sources = ShaderGraphTemplates.surface(code, surface.template)
        val name = programName(location, surface.name)
        val stage = ShaderGraphPrograms.stage(name)
        val json = ShaderGraphPrograms.json(
            vertex = stage,
            fragment = stage,
            attributes = surface.format.elementAttributeNames,
            samplers = listOf(
                "Sampler0", "Sampler2", "SceneDepth", "SceneColor"
            ) + ShaderGraphPrograms.propertySamplers(graph.properties),
            uniforms = uniforms(surface) + ShaderGraphPrograms.propertyUniforms(graph.properties),
        )
        return ShaderGraphPrograms.create(
            name, json, mapOf("vsh" to sources.vertex, "fsh" to sources.fragment), surface.format
        ).getOrNull()
    }

    /** A quad over the frame, which [VfxPostProcessor] draws with the frame and its depth copied. */
    private fun buildPost(location: String, graph: ShaderGraph, code: ShaderGraphCode): ShaderInstance? {
        if (code.outputs.isEmpty()) return null
        val sources = ShaderGraphTemplates.post(code)
        val name = programName(location, "post")
        val stage = ShaderGraphPrograms.stage(name)
        val format = DefaultVertexFormat.POSITION_TEX
        val json = ShaderGraphPrograms.json(
            vertex = stage,
            fragment = stage,
            attributes = format.elementAttributeNames,
            samplers = listOf("SceneColor", "SceneDepth") + ShaderGraphPrograms.propertySamplers(graph.properties),
            uniforms = listOf(
                ShaderGraphUniform("SceneProjMat", "matrix4x4", Identity),
                ShaderGraphUniform("InvViewProjMat", "matrix4x4", Identity),
                ShaderGraphUniform("ViewProjMat", "matrix4x4", Identity),
                ShaderGraphUniform("ViewEye", "float", listOf(0f, 0f, 0f)),
                ShaderGraphUniform("NodeOffset", "float", listOf(0f, 0f, 0f)),
                ShaderGraphUniform("ShaderTime", "float", listOf(0f)),
                ShaderGraphUniform("ScreenSize", "float", listOf(1f, 1f)),
            ) + ShaderGraphPrograms.propertyUniforms(graph.properties),
        )
        return ShaderGraphPrograms.create(
            name, json, mapOf("vsh" to sources.vertex, "fsh" to sources.fragment), format
        ).getOrNull()
    }

    /** The name of one build of [location]; see [builds]. */
    private fun programName(location: String, kind: String): String =
        "vfx_${location.lowercase().replace(Unsafe, "_")}_${kind.lowercase()}_${builds.incrementAndGet()}"

    /** What the renderer of [surface] sets on its programs, as the engine's own program for it declares. */
    private fun uniforms(surface: VfxSurface): List<ShaderGraphUniform> {
        val common = listOf(
            ShaderGraphUniform("ModelViewMat", "matrix4x4", Identity),
            ShaderGraphUniform("ProjMat", "matrix4x4", Identity),
            ShaderGraphUniform("ColorModulator", "float", listOf(1f, 1f, 1f, 1f)),
            ShaderGraphUniform("FogStart", "float", listOf(0f)),
            ShaderGraphUniform("FogEnd", "float", listOf(1f)),
            ShaderGraphUniform("FogShape", "int", listOf(0f)),
            ShaderGraphUniform("ShaderTime", "float", listOf(0f)),
            ShaderGraphUniform("ScreenSize", "float", listOf(1f, 1f)),
            ShaderGraphUniform("GlowPass", "float", listOf(0f)),
        )
        val perDraw = listOf(
            ShaderGraphUniform("BlendMode", "float", listOf(0f)),
            ShaderGraphUniform("Softness", "float", listOf(0f)),
            ShaderGraphUniform("Glow", "float", listOf(0f)),
        )
        return common + when (surface) {
            VfxSurface.PLANE -> listOf(ShaderGraphUniform("AlphaCutoff", "float", listOf(0.01f)))
            VfxSurface.MESH -> perDraw + ShaderGraphUniform("Shaded", "float", listOf(0f))
            VfxSurface.RIBBON -> perDraw
        }
    }

    private val Identity = listOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val Unsafe = Regex("[^a-z0-9_]")
}
