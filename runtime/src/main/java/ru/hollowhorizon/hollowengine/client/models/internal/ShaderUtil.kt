package ru.hollowhorizon.hollowengine.client.models.internal

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import net.irisshaders.iris.shadows.ShadowRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import org.lwjgl.opengl.GL13
import ru.hollowhorizon.hollowengine.bridge.mixins.client.ShaderInstanceAccessor
import ru.hollowhorizon.hollowengine.client.utils.shouldOverrideShaders
import ru.hollowhorizon.hollowengine.common.registry.ModShaders
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper


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

val SHADER
    get() = if (IrisHelper.hasIris && (ShadowRenderer.ACTIVE || shouldOverrideShaders())) GameRenderer.getRendertypeEntityCutoutShader()!!
    else ModShaders.GLTF_ENTITY // Ванильный шейдер не поддерживает матрицу нормалей

val INSTANCED_SHADER
    get() = ModShaders.GLTF_ENTITY_INSTANCED

const val COLOR_MAP_INDEX = GL13.GL_TEXTURE0
