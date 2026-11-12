package ru.hollowhorizon.hollowengine.fabric.internal.rendering

import net.irisshaders.iris.pipeline.IrisRenderingPipeline
import ru.hollowhorizon.hollowengine.bridge.client.IrisPreviewScope
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper
import ru.hollowhorizon.hollowengine.fabric.internal.accessors.IrisRenderingPipelineAccessor

/** Shader targets, temporal uniforms and shadow resources belong to one view. */
internal class IrisPreviewPipeline : AutoCloseable {
    private var source: IrisRenderingPipeline? = null
    private var preview: IrisRenderingPipeline? = null

    fun bind() {
        if (!IrisHelper.hasIris) return
        val current = IrisHelper.currentPipeline() as? IrisRenderingPipeline
        if (source !== current) {
            close()
            if (current != null) {
                preview = IrisRenderingPipeline((current as IrisRenderingPipelineAccessor).programSet)
                source = current
            }
        }
        preview?.let(IrisPreviewScope::bind)
    }

    fun unbind() {
        if (preview != null) IrisPreviewScope.clear()
    }

    fun finish() {
        preview?.finalizeGameRendering()
    }

    override fun close() {
        preview?.let {
            IrisInstancingPrograms.forget(it)
            it.destroy()
        }
        preview = null
        source = null
    }
}
