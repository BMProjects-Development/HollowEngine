package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Composable
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderGraph
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderTarget
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.UiLength
import ru.hollowhorizon.hollowengine.client.ui.alignItems
import ru.hollowhorizon.hollowengine.client.ui.gap
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxShaderDeclarations
import ru.hollowhorizon.hollowengine.common.vfx.VfxCameraShakeSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPostEffectSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxSamplerSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxSkySpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxUniformSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxUniformValue

@Composable
internal fun PostEffectFields(document: VfxEditing, state: VfxInspectorState, post: VfxPostEffectSpec) {
    Folding(state, "post", vfxText("section_post"), VfxIcons.POST) {
        VfxShaderRow(post.shader, vfxText("shader_post_hint"), "vfx-post-shader", graphs = ShaderTarget.POST) { shader ->
            document.replace(post.copy(shader = shader.orEmpty(), uniforms = alignUniforms(shader, post.uniforms)))
        }
        VfxShaderFields(
            post.shader,
            post.uniforms,
            post.samplers,
            onUniforms = { document.replace(post.copy(uniforms = it)) },
            onSamplers = { document.replace(post.copy(samplers = it)) },
        )
    }
}

@Composable
internal fun SkyFields(document: VfxEditing, state: VfxInspectorState, sky: VfxSkySpec) {
    Folding(state, "sky", vfxText("section_sky"), VfxIcons.SKY) {
        VfxShaderRow(sky.shader, vfxText("shader_sky_hint"), "vfx-sky-shader") { shader ->
            document.replace(sky.copy(shader = shader.orEmpty(), uniforms = alignUniforms(shader, sky.uniforms)))
        }
        VfxShaderFields(
            sky.shader,
            sky.uniforms,
            sky.samplers,
            onUniforms = { document.replace(sky.copy(uniforms = it)) },
            onSamplers = { document.replace(sky.copy(samplers = it)) },
        )
    }
}

@Composable
internal fun CameraShakeFields(document: VfxEditing, state: VfxInspectorState, shake: VfxCameraShakeSpec) {
    Folding(state, "shake", vfxText("section_shake"), VfxIcons.SHAKE) {
        VfxValueRow(vfxText("shake_strength"), shake.strength, VfxProperty.SHAKE_STRENGTH, vfxText("shake_strength_hint")) {
            document.replace(shake.copy(strength = it))
        }
        VfxValueRow(vfxText("shake_frequency"), shake.frequency, VfxProperty.SHAKE_FREQUENCY, vfxText("shake_frequency_hint")) {
            document.replace(shake.copy(frequency = it))
        }
        VfxFloat3Row(vfxText("shake_amplitude"), shake.amplitude, hint = vfxText("shake_amplitude_hint")) {
            document.replace(shake.copy(amplitude = it))
        }
    }
}

/**
 * The shader a surface or a screen effect is drawn with, by its `namespace:path` name. [hint] says what
 * the geometry hands the shader, so it lives in the tooltip of the label. [graphs] is the target of the
 * material graphs offered besides the core shaders, if any are.
 */
@Composable
internal fun VfxShaderRow(
    shader: String?,
    hint: String,
    id: String,
    graphs: ShaderTarget? = null,
    onChange: (String?) -> Unit,
) {
    VfxAssetRow(
        label = vfxText(if (graphs == ShaderTarget.SURFACE) "surface_shader" else "shader"),
        value = shader.orEmpty(),
        hint = hint,
        id = id,
        candidates = { VfxShaderAssets.list(graphs) },
        exists = VfxShaderAssets::exists,
        placeholder = vfxText("shader_placeholder"),
    ) { typed -> onChange(typed.trim().ifBlank { null }) }
}

/**
 * What the loaded packs offer an effect to draw with, named the way a material names them.
 */
internal object VfxShaderAssets {
    private const val Root = "shaders/core/"
    private const val Materials = "materials"

    /** The core shaders, and before them the material graphs of [graphs], when it is given. */
    fun list(graphs: ShaderTarget?): List<String> {
        val manager = Minecraft.getInstance().resourceManager ?: return emptyList()
        return runCatching {
            val materials = if (graphs != null) {
                manager.listResources(Materials) { it.path.endsWith(ShaderGraph.EXTENSION) }.keys.map { it.toString() }
                    .filter { VfxShaderDeclarations.of(it)?.target == graphs }
                    .sorted()
            } else {
                emptyList()
            }
            val shaders = manager.listResources(Root.trimEnd('/')) { it.path.endsWith(".json") }.keys
                .filter { it.namespace != "minecraft" }
                .map { "${it.namespace}:${it.path.removePrefix(Root).removeSuffix(".json")}" }
                .sorted()
            materials + shaders
        }.getOrDefault(emptyList())
    }

    fun exists(name: String): Boolean = VfxShaderDeclarations.of(name) != null
}

/** [authored] brought in line with what [shader] declares, or left as it is when it cannot be read. */
internal fun alignUniforms(shader: String?, authored: List<VfxUniformSpec>): List<VfxUniformSpec> =
    shader?.let(VfxShaderDeclarations::of)?.align(authored) ?: authored

/**
 * One row per uniform and sampler the shader json declares; the engine's own are left out. A shader
 * that cannot be read shows nothing here, its name field already says why.
 */
@Composable
internal fun VfxShaderFields(
    shader: String?,
    uniforms: List<VfxUniformSpec>,
    samplers: List<VfxSamplerSpec>,
    onUniforms: (List<VfxUniformSpec>) -> Unit,
    onSamplers: (List<VfxSamplerSpec>) -> Unit,
) {
    if (shader.isNullOrBlank()) return
    val declaration = VfxShaderDeclarations.of(shader) ?: return

    declaration.uniforms.forEach { uniform ->
        val value = uniforms.firstOrNull { it.name == uniform.name && uniform.accepts(it.value) }?.value
            ?: uniform.defaultValue()
        val property = VfxProperty.uniform(uniform.name)
        fun set(next: VfxUniformValue) = onUniforms(uniforms.upsert(VfxUniformSpec(uniform.name, next)) { it.name })

        when (value) {
            is VfxUniformValue.Scalar -> VfxValueRow(uniform.name, value.value, property) {
                set(VfxUniformValue.Scalar(it))
            }

            is VfxUniformValue.Vector -> VfxVec3Row(uniform.name, value.value, property) {
                set(VfxUniformValue.Vector(it))
            }

            is VfxUniformValue.Color -> VfxColorRow(uniform.name, value.value, property) {
                set(VfxUniformValue.Color(it))
            }
        }
    }

    val stale = uniforms.filter { declaration.uniform(it.name) == null }
    if (stale.isNotEmpty()) {
        Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
            Text(
                vfxText("uniforms_stale") + " " + stale.joinToString { it.name },
                tags = listOf("insp-hint"),
                modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
            )
            InspectorIconButton(
                VfxIcons.REMOVE,
                vfxText("uniforms_drop"),
                tags = listOf("insp-inline-icon", "danger"),
            ) { onUniforms(uniforms - stale.toSet()) }
        }
    }

    declaration.samplers.forEach { name ->
        val texture = samplers.firstOrNull { it.name == name }?.texture.orEmpty()
        VfxAssetRow(name, texture, VfxTextureExtensions, id = "vfx-sampler-$name") { typed ->
            val kept = samplers.filterNot { it.name == name }
            onSamplers(if (typed.isBlank()) kept else samplers.upsert(VfxSamplerSpec(name, typed)) { it.name })
        }
    }
}

/** [item] in place of the element with the same key, or added at the end when there is none. */
private fun <T> List<T>.upsert(item: T, key: (T) -> String): List<T> {
    val index = indexOfFirst { key(it) == key(item) }
    return if (index < 0) this + item else toMutableList().also { it[index] = item }
}
