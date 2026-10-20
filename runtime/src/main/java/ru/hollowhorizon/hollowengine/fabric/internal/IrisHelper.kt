package ru.hollowhorizon.hollowengine.fabric.internal

import net.irisshaders.iris.Iris
import net.irisshaders.iris.api.v0.IrisApi
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline
import net.irisshaders.iris.pipeline.WorldRenderingPipeline
import net.irisshaders.iris.uniforms.CapturedRenderingState
import net.irisshaders.iris.vertices.IrisVertexFormats
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.ModelInstancingBackend
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticGeometry
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

    /**
     * Location of an extended entity attribute ([name] such as `at_tangent`) in Iris entity programs, or -1 without Iris.
     * Iris binds locations by the attribute order of its entity vertex format, so they shift when that format changes.
     */
    fun entityAttributeLocation(name: String): Int =
        if (hasIris) IrisVertexFormats.ENTITY.elementAttributeNames.indexOf(name) else -1

    fun capturedEntityInfo(): InstancingEntityInfo = InstancingEntityInfo(
        entity = CapturedRenderingState.INSTANCE.currentRenderedEntity,
        blockEntity = CapturedRenderingState.INSTANCE.currentRenderedBlockEntity,
        item = CapturedRenderingState.INSTANCE.currentRenderedItem
    )

    @JvmStatic
    fun invalidateInstancingPrograms() {
        IrisInstancingPrograms.invalidate()
        StaticGeometry.invalidateBindings()
    }
}
