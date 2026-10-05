package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.ModelAnimator
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.common.attachments.components.AnimationsComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.MaterialsComponent
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.tracking.MCEntity
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import java.util.UUID

/**
 * One model node of one entity.
 */
class ModelInstance(val attachment: ModelAttachment) {
    val animator = ModelAnimator()
    private var posedFrame = Long.MIN_VALUE
    private var materials: MaterialsComponent? = null
    private var instanceRig: ModelRig? = null
    private var assetRig: ModelRig? = null

    init {
        // A reloaded model may bring new materials
        attachment.onModelChange(::dress)
    }

    /**
     * Sets what this instance plays, what it wears and what this entity hangs on it over the model's own rig.
     */
    fun configure(animations: AnimationsComponent?, materials: MaterialsComponent?, rig: ModelRig = ModelRig.EMPTY) {
        animator.configure(HollowModelManager.animatorOf(attachment.location), animations)
        rig(rig)
        if (this.materials == materials) return

        this.materials = materials
        dress()
    }

    /** Lays [instance] over the model's rig, again only when either of them changed. */
    private fun rig(instance: ModelRig) {
        val asset = RigAssets.of(attachment.location)
        if (instance === instanceRig && asset === assetRig) return
        instanceRig = instance
        assetRig = asset
        attachment.rig = asset.overlay(instance)
    }


    /**
     * Advances the animation and leaves the nodes ready to draw, once per frame.
     */
    fun update(context: AnimatorEvaluationContext, frame: Long = TickHandler.renderFrame) {
        if (posedFrame == frame) return
        posedFrame = frame

        attachment.beginPose()
        animator.applyTo(attachment, context)
        applyRigPose(attachment.nodes, attachment.rig)
        attachment.endPose()
    }

    private fun dress() = attachment.applyMaterials(materials?.materials ?: emptyMap())
}

/** Identifies one model node of an entity; two nodes of the same entity are two instances. */
private data class ModelInstanceKey(val nodeId: UUID, val model: String)

/**
 * The instance of [model] on [nodeId], created on first use.
 */
fun MCEntity.modelInstance(nodeId: UUID, model: String): ModelInstance =
    AttachmentRegistry.attachments(this).runtime.getOrPut(ModelInstanceKey(nodeId, model)) {
        ModelInstance(ModelAttachment(model))
    }

/** The instance already drawn for this node, or null when nothing has drawn it yet. */
fun MCEntity.modelInstanceOrNull(nodeId: UUID, model: String): ModelInstance? =
    AttachmentRegistry.attachmentsOrNull(this)?.runtime?.getOrNull(ModelInstanceKey(nodeId, model))
