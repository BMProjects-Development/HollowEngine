package ru.hollowhorizon.hollowengine.client.models.internal

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.irisshaders.iris.shadows.ShadowRenderer
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import org.lwjgl.opengl.GL13
import ru.hollowhorizon.hollowengine.bridge.mixins.client.ShaderInstanceAccessor
import ru.hollowhorizon.hollowengine.client.utils.shouldOverrideShaders
import ru.hollowhorizon.hollowengine.common.registry.ModShaders
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper
import java.util.function.Function


inline fun drawWithShader(
    shader: net.minecraft.client.renderer.ShaderInstance = SHADER,
    state: RenderType = translucentShaderState(),
    body: () -> Unit,
) {
    val accessor = shader as ShaderInstanceAccessor

    state.setupRenderState()
    RenderSystem.setShader { shader }
    shader.setDefaultUniforms(
        VertexFormat.Mode.TRIANGLES,
        RenderSystem.getModelViewMatrix(),
        RenderSystem.getProjectionMatrix(),
        Minecraft.getInstance().window
    )
    shader.apply()

    accessor.samplerLocations().forEachIndexed { texture, index ->
        RenderSystem.glUniform1i(index, texture)
    }

    body()

    shader.clear()
    state.clearRenderState()
}

fun Material.packedLight(worldLight: Int): Int = if (emissive) LightTexture.FULL_BRIGHT else worldLight

fun opaqueShaderState(): RenderType = RenderType.entityCutoutNoCull(Material.MISSING_TEXTURE)

fun translucentShaderState(): RenderType = RenderType.entityTranslucent(Material.MISSING_TEXTURE)

val batchingRenderType: Function<Material, RenderType> = Util.memoize<Material, RenderType> { material: Material ->
    val compositeState = RenderType.CompositeState.builder()
        .setShaderState(RenderStateShard.ShaderStateShard(GameRenderer::getRendertypeEntityCutoutShader))
        .setTextureState(RenderStateShard.TextureStateShard(material.texture, false, false)).setTransparencyState(
            when (material.blend) {
                Material.Blend.BLEND -> RenderStateShard.TRANSLUCENT_TRANSPARENCY
                Material.Blend.OPAQUE -> RenderStateShard.NO_TRANSPARENCY
            }
        ).setCullState(if (material.doubleSided) RenderStateShard.NO_CULL else RenderStateShard.CULL)
        .setLightmapState(RenderStateShard.LIGHTMAP).setOverlayState(RenderStateShard.OVERLAY)
        .createCompositeState(true)
    RenderType.create(
        "hollowengine:entity_cutout",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.TRIANGLES,
        4096,
        true,
        false,
        compositeState
    )
}

val SHADER
    get() = if (IrisHelper.hasIris && (ShadowRenderer.ACTIVE || shouldOverrideShaders())) GameRenderer.getRendertypeEntityCutoutShader()!!
    else ModShaders.GLTF_ENTITY // Ванильный шейдер не поддерживает матрицу нормалей

val INSTANCED_SHADER
    get() = ModShaders.GLTF_ENTITY_INSTANCED

const val COLOR_MAP_INDEX = GL13.GL_TEXTURE0
