package ru.hollowhorizon.hollowengine.fabric.internal.rendering

import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.*
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper

object IrisInstancingBackend : ModelInstancingBackend {
    override fun canBatch(): Boolean = IrisHelper.currentPipeline() != null

    override fun flush(batches: Map<PipelineRenderer, List<SubmittedInstance>>) {
        if (batches.isEmpty()) return
        val materialBatches = groupByMaterial(batches)

        withInstancingRenderState {
            renderOpaque(materialBatches, SHADER) { renderer, instances, _ ->
                renderRenderer(renderer, instances)
            }
            renderTranslucent(materialBatches, SHADER) { renderer, instances, _ ->
                renderRenderer(renderer, instances)
            }
        }
    }

    private fun renderRenderer(renderer: PipelineRenderer, instances: List<SubmittedInstance>) {
        val material = instances.first().material
        val isTranslucent = material.blend == Material.Blend.BLEND
        if (!renderer.shouldUseInstancing(instances.size, material)) {
            drawWithShader(SHADER, if (isTranslucent) translucentShaderState() else opaqueShaderState()) {
                fallbackToCapturedDraws(
                    renderer,
                    instances,
                    layoutMode = InstancedShaderLayoutMode.RUNTIME,
                )
            }
            return
        }

        val shader = IrisInstancingPrograms.shaderFor(
            translucent = isTranslucent,
            shadow = IrisHelper.isShadowRendering()
        )

        if (shader != null) {
            drawWithShader(shader, if (isTranslucent) translucentShaderState() else opaqueShaderState()) {
                renderer.renderInstanced(instances, shader, InstancedShaderLayoutMode.RUNTIME)
            }
        } else {
            drawWithShader(SHADER, if (isTranslucent) translucentShaderState() else opaqueShaderState()) {
                fallbackToCapturedDraws(
                    renderer,
                    instances,
                    layoutMode = InstancedShaderLayoutMode.RUNTIME,
                )
            }
        }
    }
}
