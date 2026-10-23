package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.ModelAnimator
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.api.RuntimeAttachment
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelNodes
import ru.hollowhorizon.hollowengine.common.attachments.components.AnimationsComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.MaterialsComponent
import ru.hollowhorizon.hollowengine.common.attachments.tracking.MCEntity
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.ikChains
import ru.hollowhorizon.hollowengine.common.models.MontageBoneProperty
import ru.hollowhorizon.hollowengine.common.models.MontageRig
import ru.hollowhorizon.hollowengine.common.models.withMontages
import java.util.*

/**
 * One model node of one entity.
 */
class ModelInstance(val attachment: ModelAttachment, private val valid: () -> Boolean = { true }) : RuntimeAttachment {
    override val isValid: Boolean get() = valid()
    override fun close() = attachment.close()
    val animator = ModelAnimator()
    private val grounding = RigGroundingState()
    private var posedFrame = Long.MIN_VALUE

    /** Whether the model has been posed at least once, so its nodes and bounds mean something. */
    val hasPose: Boolean get() = posedFrame != Long.MIN_VALUE

    /** The stretch of time the renderer last posed the model in, when it poses it at a capped rate. */
    var posePeriod = Long.MIN_VALUE
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
        baseRig = asset.overlay(instance)
        attachment.rig = baseRig
        montageKey = emptyList()
        pendingRig = null
    }

    /** The rig before montages: the model's own with what the entity hangs on it. */
    private var baseRig: ModelRig = attachment.rig

    /** Which attachments the montages hung on the rig last frame; see [MontageRig.key]. */
    private var montageKey: List<Any> = emptyList()

    /** A rig the montages built that needs the model built again, put in place before the next pose. */
    private var pendingRig: ModelRig? = null

    private fun layMontages(context: AnimatorEvaluationContext) {
        showBones(context)
        if (context.montages.isEmpty() && montageKey.isEmpty()) return
        val laid = baseRig.withMontages(context.montages)
        if (laid.key == montageKey) attachment.rig = laid.rig else pendingRig = laid.rig
        montageKey = laid.key
    }

    /** The bones montages hid or showed last frame, with whether each was drawn before they did. */
    private val shownBones = HashMap<RuntimeNode, Boolean>()

    /** Shows or hides the bones the montages playing key, and gives the ones they let go of back. */
    private fun showBones(context: AnimatorEvaluationContext) {
        if (context.montages.isEmpty() && shownBones.isEmpty()) return
        val keyed = HashMap<RuntimeNode, Boolean>()
        context.montages.forEach { moment ->
            moment.montage.bones.forEach { track ->
                if (track.property != MontageBoneProperty.VISIBLE) return@forEach
                val node = attachment.findNode(track.bone) ?: return@forEach
                keyed[node] = track.valueAt(moment.time).x >= 0.5f
            }
        }
        shownBones.keys.filter { it !in keyed }.forEach { node -> node.isVisible = shownBones.remove(node) ?: true }
        keyed.forEach { (node, visible) ->
            shownBones.getOrPut(node) { node.isVisible }
            if (node.isVisible != visible) node.isVisible = visible
        }
    }


    /**
     * Advances the animation and leaves the nodes ready to draw, once per frame.
     */
    fun update(context: AnimatorEvaluationContext, frame: Long = TickHandler.renderFrame) {
        if (posedFrame == frame) return
        posedFrame = frame
        if (isSettled()) return

        pendingRig?.let { rig ->
            pendingRig = null
            attachment.rig = rig
        }
        attachment.beginPose()
        animator.applyTo(attachment, context)
        layMontages(context)
        attachment.evaluateConditions(context)
        applyRigPose(attachment.nodes, attachment.rig)
        attachment.applyAuthoredConstraints()
        applyRigConstraints(attachment.nodes, attachment.rig, context, grounding)
        attachment.endPose()

        val still = animator.isEmpty && !attachment.carriesEffects && attachment.rig.ikChains().isEmpty()
        settledRig = if (still) attachment.rig else null
        settledModel = attachment.model
    }

    /** The rig and model of the last pose, when nothing in it moves by itself; see [isSettled]. */
    private var settledRig: ModelRig? = null
    private var settledModel: Model? = null

    /**
     * Whether the last pose is the one the next would give: no animation plays, nothing reads the entity or the world,
     * and the rig and the model are the same. A static model then costs nothing until one of those changes.
     */
    private fun isSettled(): Boolean =
        settledRig != null && pendingRig == null && animator.isEmpty &&
            settledRig === attachment.rig && settledModel === attachment.model

    private fun dress() = attachment.applyMaterials(materials?.materials ?: emptyMap())
}

/** Identifies one model node of an entity; two nodes of the same entity are two instances. */
private data class ModelInstanceKey(val nodeId: UUID, val model: String)

/**
 * The instance of [model] on [nodeId], created on first use.
 */
fun MCEntity.modelInstance(nodeId: UUID, model: String): ModelInstance =
    AttachmentRegistry.attachments(this).runtime.getOrPut(ModelInstanceKey(nodeId, model)) {
        ModelInstance(ModelAttachment(model).apply { instanceId = nodeId.toString() }) {
            AttachmentRegistry.entitySnapshot(level(), uuid)?.modelNodes()
                ?.any { it.nodeId == nodeId && it.model.model == model } == true
        }
    }

/** The instance already drawn for this node, or null when nothing has drawn it yet. */
fun MCEntity.modelInstanceOrNull(nodeId: UUID, model: String): ModelInstance? =
    AttachmentRegistry.attachmentsOrNull(this)?.runtime?.getOrNull(ModelInstanceKey(nodeId, model))
