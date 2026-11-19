package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.models.internal.createModelRoot
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.models.MAX_NESTING
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.ui.panels.UiPanelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

/**
 * A model hung on a bone of another. It stands still in its rest pose and is drawn as part of the model it
 * hangs on, whose draw commands take its meshes in, so it is placed by the same matrices the bone moves by.
 */
class NestedModelAttachment private constructor(
    spec: ModelAttachmentSpec,
    private var context: RigAttachmentContext,
    root: SceneObject,
) : Attachment, RespecAttachment {
    constructor(spec: ModelAttachmentSpec, context: RigAttachmentContext) :
        this(spec, context, context.node.sceneObject.createModelRoot(spec.id.ifBlank { spec.model }))

    override var spec: ModelAttachmentSpec = spec
        private set

    /** The nested model itself, posed in the space of the model it hangs on. */
    val model = loadModelAttachment(spec.model, parent = this, root = root)
    override val attachmentParent: Attachment get() = context.node
    override val transform: TrsTransformF get() = model.transform
    override val globalMatrix: Mat4f get() = model.globalMatrix
    override val globalVersion: Int get() = model.globalVersion

    init {
        respec(spec)
    }

    override fun canRespec(spec: Any): Boolean = spec is ModelAttachmentSpec && this.spec.model == spec.model

    /** Takes the attachment as it now is, after a change that only moved, posed or redressed the nested model. */
    override fun respec(spec: Any) {
        if (spec !is ModelAttachmentSpec) return
        this.spec = spec
        model.rig = RigAssets.of(model.location).overlay(spec.rig)
    }

    internal fun rebind(context: RigAttachmentContext, spec: ModelAttachmentSpec) {
        require(model.sceneRoot.physicalParent === context.node.sceneObject) { "Nested model owner changed" }
        this.context = context
        respec(spec)
    }

    /** Follows the bone, and swaps in the nested model once it has loaded. */
    override fun updateGlobalMatrix() {
        model.entity = context.entity()
        model.carrier = context.carrier()
        model.beginPose()
        model.applyRigPose()
        model.endPose()
    }

    override fun collectCommands(pipeline: RenderPipeline) = model.collectCommands(pipeline)

    override fun close() = model.close()

    companion object {
        /**
         * How many models [owner] is nested in. Rig files may hang a model on itself, or two on each other; past
         * [MAX_NESTING] nothing more is built, as the colliders stop there too.
         */
        private fun nesting(owner: ModelAttachment?): Int =
            generateSequence<Attachment>(owner) { it.attachmentParent }.count { it is NestedModelAttachment }

        fun register() {
            RigAttachmentFactories.register("hollowengine:rig/model") { spec, context ->
                if (nesting(context.owner) >= MAX_NESTING) null else NestedModelAttachment(spec as ModelAttachmentSpec, context)
            }
        }
    }
}

/** Whether a legacy draw extends beyond the rig's geometry, including nested rigs. */
internal fun ModelRig.carriesLegacyEffects(): Boolean =
    attachments.any(::carriesLegacyEffects) || bones.values.any { it.attachments.any(::carriesLegacyEffects) }

private fun carriesLegacyEffects(spec: Any): Boolean =
    spec is UiPanelAttachmentSpec || spec is ModelAttachmentSpec && spec.rig.carriesLegacyEffects()
