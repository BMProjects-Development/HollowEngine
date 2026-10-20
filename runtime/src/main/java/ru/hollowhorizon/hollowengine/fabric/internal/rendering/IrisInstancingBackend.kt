package ru.hollowhorizon.hollowengine.fabric.internal.rendering

import ru.hollowhorizon.hollowengine.client.models.internal.SHADER
import ru.hollowhorizon.hollowengine.client.models.internal.drawWithShader
import ru.hollowhorizon.hollowengine.client.models.internal.opaqueShaderState
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.InstancedShaderLayoutMode
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.ModelInstancingBackend
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticBatchKey
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticInstance
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.drawOrder
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.withInstancingRenderState
import ru.hollowhorizon.hollowengine.client.models.internal.translucentShaderState
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper

object IrisInstancingBackend : ModelInstancingBackend {
    override fun canBatch(): Boolean = IrisHelper.currentPipeline() != null

    override fun flush(batches: Map<StaticBatchKey, List<StaticInstance>>) {
        if (batches.isEmpty()) return
        withInstancingRenderState {
            for (translucent in listOf(false, true)) {
                drawOrder(batches, translucent).forEach { (key, instances) -> draw(key, instances, translucent) }
            }
        }
    }

    /**
     * Draws one batch with the shader pack's program patched to read the matrices of the slots, or slot by slot
     * with the pack's own entity program when that patch could not be compiled.
     */
    private fun draw(key: StaticBatchKey, instances: List<StaticInstance>, translucent: Boolean) {
        val state = if (translucent) translucentShaderState() else opaqueShaderState()
        val geometry = key.part.geometry
        val shader = IrisInstancingPrograms.shaderFor(translucent = translucent, shadow = IrisHelper.isShadowRendering())

        if (shader != null) {
            drawWithShader(shader, state) {
                geometry.drawInstanced(key.part, key.material, shader, instances, InstancedShaderLayoutMode.RUNTIME)
            }
        } else {
            val fallback = SHADER
            drawWithShader(fallback, state) {
                geometry.drawEach(key.part, key.material, fallback, instances, InstancedShaderLayoutMode.RUNTIME)
            }
        }
    }
}
