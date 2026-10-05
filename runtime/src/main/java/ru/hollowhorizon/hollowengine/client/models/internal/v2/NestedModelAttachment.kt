package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.models.MAX_NESTING
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxBoneAttachmentSpec

/**
 * A model hung on a bone of another. It stands still in its rest pose and is drawn as part of the model it
 * hangs on, whose draw commands take its meshes in, so it is placed by the same matrices the bone moves by.
 */
class NestedModelAttachment(spec: ModelAttachmentSpec, private val context: RigAttachmentContext) : Attachment(context.node), RespecAttachment {
    override var spec: ModelAttachmentSpec = spec
        private set

    /** The nested model itself, posed in the space of the model it hangs on. */
    val model = ModelAttachment(spec.model, parent = this)

    init {
        respec(spec)
        model.onModelChange { context.owner?.invalidatePipeline() }
    }

    /** Takes the attachment as it now is, after a change that only moved, posed or redressed the nested model. */
    override fun respec(spec: RigAttachmentSpec) {
        if (spec !is ModelAttachmentSpec) return
        this.spec = spec
        transform.set(spec.localTransform())
        model.rig = RigAssets.of(model.location).overlay(spec.rig)
    }

    /** Follows the bone, and swaps in the nested model once it has loaded. */
    override fun updateGlobalMatrix() {
        super.updateGlobalMatrix()
        model.entity = context.entity()
        model.beginPose()
        applyRigPose(model.nodes, model.rig)
        model.endPose()
    }

    override fun collectCommands(pipeline: RenderPipeline) {
        super.collectCommands(pipeline)
        model.collectCommands(pipeline)
    }

    companion object {
        /**
         * How many models [owner] is nested in. Rig files may hang a model on itself, or two on each other; past
         * [MAX_NESTING] nothing more is built, as the colliders stop there too.
         */
        private fun nesting(owner: ModelAttachment?): Int =
            generateSequence<Attachment>(owner) { it.parent }.count { it is NestedModelAttachment }

        fun register() {
            RigAttachmentFactories.register("hollowengine:rig/model") { spec, context ->
                if (nesting(context.owner) >= MAX_NESTING) null else NestedModelAttachment(spec as ModelAttachmentSpec, context)
            }
        }
    }
}

/** Whether an effect hangs anywhere on this rig, models nested in it included. */
internal fun ModelRig.carriesEffects(): Boolean = allAttachments().any { (_, spec) ->
    spec is VfxBoneAttachmentSpec || spec is ModelAttachmentSpec && spec.rig.carriesEffects()
}
