package ru.hollowhorizon.hollowengine.fabric.internal

import net.irisshaders.iris.Iris
import net.irisshaders.iris.api.v0.IrisApi
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline
import net.irisshaders.iris.pipeline.WorldRenderingPipeline
import net.irisshaders.iris.uniforms.CapturedRenderingState
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.ModelInstancingBackend
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.PipelineRenderer
import ru.hollowhorizon.hollowengine.client.utils.InstancingEntityInfo
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.fabric.internal.rendering.IrisInstancingBackend
import ru.hollowhorizon.hollowengine.fabric.internal.rendering.IrisInstancingPrograms

object IrisHelper {
    @JvmStatic
    fun shouldOverrideShaders() =
        hasIris && (Iris.getPipelineManager().pipelineNullable as? ShaderRenderingPipeline)?.shouldOverrideShaders() == true

    val hasIris = ModList.isLoaded("iris") || ModList.isLoaded("oculus")

    fun areShadersEnabled() = hasIris && IrisApi.getInstance().config.areShadersEnabled()

    fun isShadowRendering() = hasIris && IrisApi.getInstance().isRenderingShadowPass

    fun currentPipeline(): WorldRenderingPipeline? = if (hasIris) Iris.getPipelineManager().pipelineNullable else null

    fun instancingBackend(): ModelInstancingBackend = IrisInstancingBackend

    fun capturedEntityInfo(): InstancingEntityInfo = InstancingEntityInfo(
        entity = CapturedRenderingState.INSTANCE.currentRenderedEntity,
        blockEntity = CapturedRenderingState.INSTANCE.currentRenderedBlockEntity,
        item = CapturedRenderingState.INSTANCE.currentRenderedItem
    )

    @JvmStatic
    fun invalidateInstancingPrograms() {
        IrisInstancingPrograms.invalidate()
        PipelineRenderer.invalidateRuntimeInstancedBindings()
    }
}
