package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager

interface ModelInstancingBackend {
    fun canBatch(): Boolean = true

    fun flush(batches: Map<PipelineRenderer, List<SubmittedInstance>>)
}

object VanillaInstancingBackend : ModelInstancingBackend {
    override fun flush(batches: Map<PipelineRenderer, List<SubmittedInstance>>) {
        if (batches.isEmpty()) return
        val materialBatches = groupByMaterial(batches)

        withInstancingRenderState {
            renderPass(materialBatches, translucent = false)
            renderPass(materialBatches, translucent = true)
        }
    }

    private fun renderPass(batches: List<MaterialInstanceBatch>, translucent: Boolean) {
        val passBatches = batches
            .asSequence()
            .filter { (it.material.blend == Material.Blend.BLEND) == translucent }
            .let { sequence ->
                if (translucent) {
                    sequence.sortedByDescending {
                        it.instances.maxOfOrNull(SubmittedInstance::sortKey) ?: Float.NEGATIVE_INFINITY
                    }
                } else {
                    sequence
                }
            }
            .toList()
        val (instanced, fallback) = passBatches.partition {
            it.renderer.shouldUseInstancing(it.instances.size, it.material)
        }
        val state = if (translucent) translucentShaderState() else opaqueShaderState()

        if (instanced.isNotEmpty()) {
            val shader = INSTANCED_SHADER
            drawWithShader(shader, state) {
                instanced.forEach { batch ->
                    batch.renderer.renderInstanced(
                        batch.instances,
                        shader,
                        InstancedShaderLayoutMode.FIXED,
                    )
                }
            }
        }
        if (fallback.isNotEmpty()) {
            val shader = SHADER
            drawWithShader(shader, state) {
                fallback.forEach { batch ->
                    fallbackToCapturedDraws(batch.renderer, batch.instances, shader)
                }
            }
        }
    }
}

data class MaterialInstanceBatch(
    val renderer: PipelineRenderer,
    val material: Material,
    val instances: List<SubmittedInstance>,
)

fun groupByMaterial(
    batches: Map<PipelineRenderer, List<SubmittedInstance>>,
): List<MaterialInstanceBatch> = buildList {
    batches.forEach { (renderer, instances) ->
        instances.groupBy { it.material }.forEach { (material, materialInstances) ->
            add(MaterialInstanceBatch(renderer, material, materialInstances))
        }
    }
}

inline fun withInstancingRenderState(body: () -> Unit) {
    val activeTexture = GlStateManager._getActiveTexture()
    val currentVao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
    val currentArrayBuffer = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING)
    val shaderTexture0 = RenderSystem.getShaderTexture(0)
    val shaderTexture1 = RenderSystem.getShaderTexture(1)
    val shaderTexture2 = RenderSystem.getShaderTexture(2)

    RenderSystem.activeTexture(GL33.GL_TEXTURE2)
    val texture2 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
    RenderSystem.bindTexture(HollowModelManager.lightTexture.id)
    RenderSystem.setShaderTexture(2, HollowModelManager.lightTexture.id)

    RenderSystem.activeTexture(GL33.GL_TEXTURE1)
    val texture1 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
    Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor()
    RenderSystem.bindTexture(RenderSystem.getShaderTexture(1))
    RenderSystem.setShaderTexture(1, RenderSystem.getShaderTexture(1))
    Minecraft.getInstance().gameRenderer.overlayTexture().teardownOverlayColor()

    RenderSystem.activeTexture(GL33.GL_TEXTURE0)
    val texture0 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding

    try {
        body()
    } finally {
        RenderSystem.setShaderTexture(0, shaderTexture0)
        RenderSystem.setShaderTexture(1, shaderTexture1)
        RenderSystem.setShaderTexture(2, shaderTexture2)
        RenderSystem.activeTexture(GL33.GL_TEXTURE2)
        RenderSystem.bindTexture(texture2)
        RenderSystem.activeTexture(GL33.GL_TEXTURE1)
        RenderSystem.bindTexture(texture1)
        RenderSystem.activeTexture(GL33.GL_TEXTURE0)
        RenderSystem.bindTexture(texture0)
        RenderSystem.activeTexture(activeTexture)

        RenderSystem.glBindVertexArray(currentVao)
        RenderSystem.glBindBuffer(GL33.GL_ARRAY_BUFFER, currentArrayBuffer)

        GlStateManager._glUseProgram(0)
    }
}

inline fun renderOpaque(
    batches: List<MaterialInstanceBatch>,
    shader: ShaderInstance,
    render: (PipelineRenderer, List<SubmittedInstance>, ShaderInstance) -> Unit,
) {
    for (batch in batches.asSequence().filter { it.material.blend != Material.Blend.BLEND }) {
        render(batch.renderer, batch.instances, shader)
    }
}

inline fun renderTranslucent(
    batches: List<MaterialInstanceBatch>,
    shader: ShaderInstance,
    render: (PipelineRenderer, List<SubmittedInstance>, ShaderInstance) -> Unit,
) {
    for (batch in batches
        .asSequence()
        .filter { it.material.blend == Material.Blend.BLEND }
        .sortedByDescending { it.instances.maxOfOrNull(SubmittedInstance::sortKey) ?: Float.NEGATIVE_INFINITY }) {
        render(batch.renderer, batch.instances, shader)
    }
}

fun fallbackToCapturedDraws(
    renderer: PipelineRenderer,
    instances: List<SubmittedInstance>,
    shader: ShaderInstance = SHADER,
    layoutMode: InstancedShaderLayoutMode = InstancedShaderLayoutMode.FIXED,
) {
    val drawInstances = if (instances.firstOrNull()?.material?.blend == Material.Blend.BLEND) {
        instances.sortedByDescending(SubmittedInstance::sortKey)
    } else {
        instances
    }
    for (instance in drawInstances) {
        renderer.renderCapturedInstance(instance, shader, layoutMode)
    }
}

enum class InstancedShaderLayoutMode {
    FIXED,
    RUNTIME,
}
