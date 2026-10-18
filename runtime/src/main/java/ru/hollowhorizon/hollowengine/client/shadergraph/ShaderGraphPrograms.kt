package ru.hollowhorizon.hollowengine.client.shadergraph

import com.mojang.blaze3d.vertex.VertexFormat
import kotlinx.serialization.json.*
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceProvider
import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.ByteArrayInputStream
import java.util.*

/** A uniform a program json declares: its GLSL type as the json names it, and its starting values. */
class ShaderGraphUniform(val name: String, val type: String, val values: List<Float>)

/**
 * Shader programs made out of generated source rather than files. The program and its sources live
 * under `minecraft:shaders/core/hollowengine_graph/`, served from memory; everything they import or
 * name besides that, such as a shared vertex shader, comes from the resource packs as usual.
 */
object ShaderGraphPrograms {
    private const val FOLDER = "hollowengine_graph"

    /**
     * A program named [name], out of [json] and its generated stages, keyed by extension (`vsh`, `fsh`);
     * a failure carries what the driver said.
     */
    fun create(name: String, json: String, stages: Map<String, String>, format: VertexFormat): Result<ShaderInstance> {
        val resources = Minecraft.getInstance().resourceManager
        val pack = Minecraft.getInstance().vanillaPackResources
        val files = buildMap {
            put("shaders/core/$FOLDER/$name.json", json)
            stages.forEach { (extension, source) -> put("shaders/core/$FOLDER/$name.$extension", source) }
        }
        val provider = ResourceProvider { wanted ->
            val text = files[wanted.path].takeIf { wanted.namespace == ResourceLocation.DEFAULT_NAMESPACE }
            if (text != null) {
                Optional.of(Resource(pack) { ByteArrayInputStream(text.toByteArray()) })
            } else {
                resources.getResource(wanted)
            }
        }
        return runCatching { ShaderInstance(provider, "$FOLDER/$name", format) }.onFailure { e ->
            HollowEngine.LOGGER.warn("Could not build the shader graph program {}: {}", name, e.message)
        }
    }

    /** What a stage of [name] is called inside a program json. */
    fun stage(name: String): String = "$FOLDER/$name"

    /** A program json: the stages it names, its attributes, samplers and uniforms. */
    fun json(
        vertex: String,
        fragment: String,
        attributes: List<String>,
        samplers: List<String>,
        uniforms: List<ShaderGraphUniform>,
    ): String = buildJsonObject {
        put("vertex", vertex)
        put("fragment", fragment)
        put("attributes", JsonArray(attributes.map(::JsonPrimitive)))
        put("samplers", buildJsonArray { samplers.forEach { add(buildJsonObject { put("name", it) }) } })
        put("uniforms", buildJsonArray {
            uniforms.forEach { uniform ->
                add(buildJsonObject {
                    put("name", uniform.name)
                    put("type", uniform.type)
                    put("count", uniform.values.size)
                    put("values", JsonArray(uniform.values.map(::JsonPrimitive)))
                })
            }
        })
    }.toString()

    /** The uniform or sampler each property of a graph becomes. */
    fun propertyUniforms(properties: List<ShaderGraphProperty>): List<ShaderGraphUniform> =
        properties.filter { it.type.isVector }.map { property ->
            val defaults = (0 until property.type.width).map { property.default.getOrElse(it) { property.default.lastOrNull() ?: 0f } }
            ShaderGraphUniform(propertyUniform(property.name), "float", defaults)
        }

    fun propertySamplers(properties: List<ShaderGraphProperty>): List<String> =
        properties.filter { it.type == ShaderType.TEXTURE }.map { propertyUniform(it.name) }
}
