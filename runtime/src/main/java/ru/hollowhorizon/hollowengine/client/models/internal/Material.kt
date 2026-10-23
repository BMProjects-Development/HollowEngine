package ru.hollowhorizon.hollowengine.client.models.internal

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.HollowEngine.MODID
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.MaterialBindContext
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderMaterial
import ru.hollowhorizon.hollowengine.client.utils.areShadersEnabled
import ru.hollowhorizon.hollowengine.client.utils.toTexture
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * How a surface of a model is drawn.
 *
 * The name is how everything else addresses it: `.hemeta` renames it, and the materials of an entity
 * are keyed by it. Models that name nothing get `material_0`, `material_1`, and so on.
 */
data class Material(
    var name: String = "",
    var color: Color = Color(1f, 1f, 1f, 1f),
    var texture: ResourceLocation = MISSING_TEXTURE,
    var normalTexture: ResourceLocation = MISSING_NORMAL,
    var specularTexture: ResourceLocation = MISSING_SPECULAR,
    var doubleSided: Boolean = false,
    var blend: Blend = Blend.OPAQUE,
    var emissive: Boolean = false,
    var textureContent: ModelTexture? = null,
) : RenderMaterial {
    enum class Blend { OPAQUE, BLEND, ADDITIVE }

    override val isTranslucent: Boolean get() = blend != Blend.OPAQUE

    override fun bind(context: MaterialBindContext) {
        val shader = context.shader
        if (context.colorLocation != -1) {
            GL33.glVertexAttrib4f(context.colorLocation, color.r, color.g, color.b, color.a)
        }

        if (areShadersEnabled) {
            bindExtraMap(shader, "normals", normalTexture)
            bindExtraMap(shader, "specular", specularTexture)
        }

        RenderSystem.activeTexture(COLOR_MAP_INDEX)
        val content = textureContent.takeIf { texture == MISSING_TEXTURE }
        RenderSystem.bindTexture(content?.textureId() ?: Minecraft.getInstance().textureManager.getTexture(texture).id)

        if (doubleSided) RenderSystem.disableCull() else RenderSystem.enableCull()

        when (blend) {
            Blend.OPAQUE -> RenderSystem.disableBlend()
            Blend.BLEND -> {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
            }
            Blend.ADDITIVE -> {
                RenderSystem.enableBlend()
                RenderSystem.blendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE)
            }
        }
    }

    override fun clear(context: MaterialBindContext) = Unit

    private fun bindExtraMap(shader: ShaderInstance, uniform: String, map: ResourceLocation) {
        val location = GL33.glGetUniformLocation(shader.id, uniform)
        if (location == -1) return

        RenderSystem.activeTexture(COLOR_MAP_INDEX + GL33.glGetUniformi(shader.id, location))
        RenderSystem.bindTexture(map.toTexture().id)
    }

    companion object {
        val MISSING_TEXTURE = "$MODID:default_color_map".rl
        val MISSING_NORMAL = "$MODID:default_normal_map".rl
        val MISSING_SPECULAR = "$MODID:default_specular_map".rl
    }
}
