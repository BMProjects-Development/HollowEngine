package ru.hollowhorizon.hollowengine.client.vfx.render

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import ru.hollowhorizon.hollowengine.client.vfx.*
import ru.hollowhorizon.hollowengine.common.utils.MutableColor
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxSamplerSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxUniformSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxUniformValue

/**
 * The uniforms and textures one draw hands its shader, evaluated for this frame.
 */
class VfxUniformValues(
    private val shader: String,
    private val names: Array<String>,
    private val values: FloatArray,
    private val samplers: List<VfxSamplerSpec>,
) {
    /**
     * Sets every uniform the shader declares for the author: to what this draw evaluated, or to the
     * default, since the shader still holds whatever the previous draw with it left there. A sampler
     * the effect names no texture for shows the default texture of its declaration, if it has one.
     */
    fun apply(program: ShaderInstance) {
        val declaration = VfxShaderDeclarations.of(shader)
        declaration?.uniforms?.forEach { uniform ->
            val index = names.indexOf(uniform.name)
            val glsl = declaration.glslName(uniform.name)
            if (index >= 0) {
                set(program, glsl, uniform.isInt, index * 4, values)
            } else {
                set(program, glsl, uniform.isInt, 0, uniform.defaults.copyOf(4))
            }
        }
        names.forEachIndexed { index, name ->
            if (declaration?.uniform(name) == null) set(program, name, false, index * 4, values)
        }

        val textures = Minecraft.getInstance().textureManager
        val named = samplers.filter { it.name.isNotBlank() && it.texture.isNotBlank() }.associate { it.name to it.texture }
        (declaration?.samplerDefaults.orEmpty() + named).forEach { (name, texture) ->
            if (texture.isBlank()) return@forEach
            program.setSampler(declaration?.glslName(name) ?: name, textures.getTexture(texture.rl).id)
        }
    }

    private fun set(program: ShaderInstance, name: String, whole: Boolean, at: Int, from: FloatArray) {
        val uniform = program.safeGetUniform(name)
        if (whole) {
            uniform.setSafe(from[at].toInt(), from[at + 1].toInt(), from[at + 2].toInt(), from[at + 3].toInt())
        } else {
            uniform.setSafe(from[at], from[at + 1], from[at + 2], from[at + 3])
        }
    }
}

/**
 * Reads what the author wrote into the uniforms of [shader], with the timeline over it, once per
 * frame for the node that owns them.
 */
class VfxUniformBinding(
    private val shader: String,
    uniforms: List<VfxUniformSpec>,
    private val samplers: List<VfxSamplerSpec>,
    node: VfxNodeRuntime,
) {
    private val names: Array<String> = uniforms.map { it.name }.filter { it.isNotBlank() }.toTypedArray()

    /** Writes one uniform, four floats at [at], for the frame [context] describes. */
    private fun interface Reader {
        fun read(context: VfxEvalContext, into: FloatArray, at: Int)
    }

    private val readers: List<Reader> = uniforms.filter { it.name.isNotBlank() }.map { uniform -> reader(uniform, node) }

    fun evaluate(context: VfxEvalContext): VfxUniformValues {
        val values = FloatArray(names.size * 4)
        readers.forEachIndexed { index, reader -> reader.read(context, values, index * 4) }
        return VfxUniformValues(shader, names, values, samplers)
    }

    private fun reader(uniform: VfxUniformSpec, node: VfxNodeRuntime): Reader {
        val property = VfxProperty.uniform(uniform.name)
        val drive = node.drive(property)
        val salt = property.hashCode()
        val expressions = node.expressions
        return when (val value = uniform.value) {
            is VfxUniformValue.Scalar -> scalarReader(
                VfxSamplers.driven(VfxSamplers.scalar(value.value, expressions, 0f, VfxRangeMode.PER_PARTICLE, salt), drive)
            )

            is VfxUniformValue.Vector -> vectorReader(
                VfxSamplers.vec3(value.value, expressions, 0f, VfxRangeMode.PER_PARTICLE, salt, drive)
            )

            is VfxUniformValue.Color -> colorReader(VfxSamplers.color(value.value, expressions, salt, drive))
        }
    }

    private fun scalarReader(sampler: VfxFloatSampler) = Reader { context, into, at -> into[at] = sampler.eval(context) }

    private fun vectorReader(sampler: VfxVec3Sampler): Reader {
        val scratch = MutableVec3f()
        return Reader { context, into, at ->
            sampler.eval(context, scratch)
            into[at] = scratch.x
            into[at + 1] = scratch.y
            into[at + 2] = scratch.z
        }
    }

    private fun colorReader(sampler: VfxColorSampler): Reader {
        val scratch = MutableColor(1f, 1f, 1f, 1f)
        return Reader { context, into, at ->
            sampler.eval(context, scratch)
            into[at] = scratch.r
            into[at + 1] = scratch.g
            into[at + 2] = scratch.b
            into[at + 3] = scratch.a
        }
    }
}
