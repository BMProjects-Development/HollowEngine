package ru.hollowhorizon.hollowengine.client.vfx.render

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderTarget
import ru.hollowhorizon.hollowengine.common.vfx.*

/** A uniform as a shader json declares it. */
class VfxShaderUniform(val name: String, val type: String, val count: Int, val defaults: FloatArray) {
    val isInt: Boolean get() = type.startsWith("int")

    /** The authored value the json default stands for: a number, a vector up to three, a color of four. */
    fun defaultValue(): VfxUniformValue = when {
        count >= 4 -> VfxUniformValue.Color(VfxColorValue.Solid(VfxRgba(at(0), at(1), at(2), at(3))))
        count >= 2 -> VfxUniformValue.Vector(VfxVec3Value.of(at(0), at(1), at(2)))
        else -> VfxUniformValue.Scalar(VfxValue.of(at(0)))
    }

    /** Whether [value] has the shape this uniform takes. */
    fun accepts(value: VfxUniformValue): Boolean = value.kind == defaultValue().kind

    private fun at(index: Int) = defaults.getOrElse(index) { 0f }
}

/**
 * What a core shader json declares, minus what the engine fills in itself: the uniforms and the
 * samplers an effect author gives values to.
 */
class VfxShaderDeclaration(
    val uniforms: List<VfxShaderUniform>,
    val samplers: List<String>,
    /** Whether the shader declares `GlowPass`, and so draws the glow of its surface itself. */
    val drawsGlow: Boolean = false,
    /** What a uniform or sampler is called in the GLSL, where that is not its own name. */
    private val glslNames: Map<String, String> = emptyMap(),
    /** The texture a sampler shows until the effect names one. */
    val samplerDefaults: Map<String, String> = emptyMap(),
    /** What a material graph shades; null for a core shader, which says nothing about it. */
    val target: ShaderTarget? = null,
) {
    fun uniform(name: String): VfxShaderUniform? = uniforms.firstOrNull { it.name == name }

    /** What [name] is called in the GLSL: a material graph names its properties `p_<name>` there. */
    fun glslName(name: String): String = glslNames[name] ?: name

    /**
     * The uniforms of [authored] brought in line with this shader: one per declared uniform, the
     * authored value where it has the right shape and the json default everywhere else.
     */
    fun align(authored: List<VfxUniformSpec>): List<VfxUniformSpec> = uniforms.map { uniform ->
        val kept = authored.firstOrNull { it.name == uniform.name && uniform.accepts(it.value) }
        kept ?: VfxUniformSpec(uniform.name, uniform.defaultValue())
    }

    companion object {
        /** Set by the render system or by the effect renderers for every draw. */
        private val ENGINE_UNIFORMS = setOf(
            "ModelViewMat", "ProjMat", "IViewRotMat", "TextureMat", "ColorModulator", "Light0_Direction",
            "Light1_Direction", "FogStart", "FogEnd", "FogColor", "FogShape", "LineWidth", "GameTime",
            "ScreenSize", "GlintAlpha", "ChunkOffset", "Shaded", "BlendMode", "SkyCenter", "NodeOffset", "EffectTime",
            "Softness", "Glow", "GlowPass", "ShaderTime",
        )

        /** The material texture, the light map and the scene copies. */
        private val ENGINE_SAMPLERS = setOf("Sampler0", "Sampler1", "Sampler2", "SceneColor", "SceneDepth")

        fun parse(json: JsonObject): VfxShaderDeclaration {
            val uniforms = json.getAsJsonArray("uniforms")?.mapNotNull { element ->
                val uniform = element.asJsonObject
                val name = uniform.get("name")?.asString ?: return@mapNotNull null
                val type = uniform.get("type")?.asString ?: "float"
                if (name in ENGINE_UNIFORMS || type.startsWith("matrix")) return@mapNotNull null
                val values = uniform.getAsJsonArray("values")?.map { it.asFloat }?.toFloatArray() ?: FloatArray(0)
                VfxShaderUniform(name, type, uniform.get("count")?.asInt ?: values.size.coerceAtLeast(1), values)
            }.orEmpty()
            val samplers = json.getAsJsonArray("samplers")?.mapNotNull { element ->
                element.asJsonObject.get("name")?.asString?.takeIf { it !in ENGINE_SAMPLERS }
            }.orEmpty()
            val drawsGlow = json.getAsJsonArray("uniforms")?.any { it.asJsonObject.get("name")?.asString == "GlowPass" } == true
            return VfxShaderDeclaration(uniforms, samplers, drawsGlow)
        }
    }
}

/**
 * The declarations of the shaders effects name, read from their json once, or for a material graph
 * from its properties. The editor lays out its fields from them, and a draw resets what it does not set
 * to the defaults, so one node never inherits the uniforms another node left in the same shader.
 */
object VfxShaderDeclarations {
    private val read = HashMap<String, VfxShaderDeclaration?>()

    /** The declaration of `namespace:path`, or null when there is no such shader json. */
    @Synchronized
    fun of(location: String): VfxShaderDeclaration? = read.getOrPut(location) { load(location) }

    @Synchronized
    fun clear() = read.clear()

    /** Forgets [location], which changed, so it is read again. */
    @Synchronized
    fun invalidate(location: String) {
        read.remove(location)
    }

    private fun load(location: String): VfxShaderDeclaration? {
        if (VfxGraphMaterials.isGraph(location)) return VfxGraphMaterials.read(location)?.let(VfxGraphMaterials::declaration)
        val id = ResourceLocation.tryParse(location) ?: return null
        val file = ResourceLocation.fromNamespaceAndPath(id.namespace, "shaders/core/${id.path}.json")
        val resource = Minecraft.getInstance().resourceManager.getResource(file).orElse(null) ?: return null
        return try {
            resource.openAsReader().use { VfxShaderDeclaration.parse(JsonParser.parseReader(it).asJsonObject) }
        } catch (e: Exception) {
            HollowEngine.LOGGER.warn("Could not read the effect shader {}: {}", location, e.message)
            null
        }
    }
}
