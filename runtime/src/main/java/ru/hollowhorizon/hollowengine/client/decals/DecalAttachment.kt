package ru.hollowhorizon.hollowengine.client.decals

import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RespecAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentFactories
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.decals.DecalAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec

/**
 * A decal hung on a bone: each time the model is drawn, it hands [Decals] where it stands then and the light the
 * model is drawn with, and is drawn after the models, over everything its box reaches.
 */
class DecalAttachment(
    spec: DecalAttachmentSpec,
    private val node: RuntimeNode,
) : Attachment(node), RespecAttachment {
    override var spec: DecalAttachmentSpec = spec
        private set

    /** The frame it last handed itself over in: a model is drawn by several passes, a decal only once. */
    private var submittedIn = Long.MIN_VALUE

    /** How an editor finds this decal: `bone/id`, the bone blank when it hangs on the model itself. */
    private val key = "${node.name.takeUnless { it == ModelAttachment.MODEL_ROOT }.orEmpty()}/${spec.id}"

    override fun respec(spec: RigAttachmentSpec) {
        if (spec is DecalAttachmentSpec) this.spec = spec
    }

    override fun collectCommands(pipeline: RenderPipeline) {
        pipeline.addBatchedRenderable {
            if (!node.isVisible) return@addBatchedRenderable
            val frame = TickHandler.renderFrame
            if (frame == submittedIn) return@addBatchedRenderable
            submittedIn = frame
            val placement = Matrix4f(stack.last().pose())
                .mul(globalMatrix.asMatrix4f())
                .mul(spec.localTransform().matrixF.asMatrix4f())
            Decals.submit(DecalDraw(spec.decal, placement, light, key = key))
        }
    }

    companion object {
        fun register() {
            RigAttachmentFactories.register("hollowengine:rig/decal") { spec, context ->
                DecalAttachment(spec as DecalAttachmentSpec, context.node)
            }
        }
    }
}
