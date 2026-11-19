package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSceneInstance
import ru.hollowhorizon.hollowengine.client.models.internal.createModelRoot
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.EntityConditions
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ModelNodeEntry
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f

/**
 * One model of an entity as its colliders see it: the nodes as posed, the rig laid over them, and the models
 * hung on it, which carry colliders of their own.
 */
internal interface ColliderScope {
    val poseNodes: Collection<SceneObject>
    val rig: ModelRig
    val isActive: Boolean get() = true

    /** Placement of the model root in the same space as [poseNodes]. */
    val modelMatrix: Mat4f?
    val nested: List<ColliderScope>
}

/** Every collider of this model and of the models nested in it. */
internal fun ColliderScope.placeColliders(toEntity: Mat4f, origin: Vec3): List<EntityCollider> {
    val alignedScaleMatrix = modelMatrix?.let { toEntity.mul(it, MutableMat4f()) } ?: toEntity
    return placeColliders(toEntity, origin, alignedScaleMatrix)
}

private fun ColliderScope.placeColliders(
    toEntity: Mat4f,
    origin: Vec3,
    alignedScaleMatrix: Mat4f,
): List<EntityCollider> {
    if (!isActive) return emptyList()
    val own = if (rig.hasColliders()) rig.placeCollidersOnPose(poseNodes, toEntity, origin, modelMatrix, alignedScaleMatrix)
    else emptyList()
    return own + nested.flatMap { it.placeColliders(toEntity, origin, alignedScaleMatrix) }
}

/**
 * This rig with the colliders of every model nested in it gathered onto the model itself: all an entity's
 * colliders by name, for what only needs to know which there are and what they do.
 */
internal fun ModelRig.withNestedColliders(fileRigOf: (String) -> ModelRig, depth: Int = 0): ModelRig {
    if (depth >= MAX_NESTING) return this
    val nested = allAttachments().mapNotNull { (_, spec) -> spec as? ModelAttachmentSpec }
    if (nested.isEmpty()) return this
    val gathered = nested.flatMap { spec ->
        fileRigOf(spec.model).overlay(spec.rig).withNestedColliders(fileRigOf, depth + 1).colliders.map { it.second }
    }
    return if (gathered.isEmpty()) this else copy(attachments = attachments + gathered)
}

/**
 * The rig of an entity's model with what the entity hangs on it and what the montages playing on it hang
 * there now, colliders of nested models gathered in; worked out again only when one of those changes.
 */
internal class EntityRigs(private val fileRigOf: (String) -> ModelRig) {
    private class Cached(val file: ModelRig, val own: ModelRig, val montages: List<Any>, val rig: ModelRig)

    fun of(entity: Entity, node: ModelNodeEntry): ModelRig {
        val model = node.model
        val file = fileRigOf(model.model)
        val gameTime = entity.level().gameTime
        val conditions = EntityConditions(entity)
        val moments = node.animations?.montages.orEmpty().mapNotNull { it.momentAt(gameTime, entity.level().isClientSide, conditions) }
        val key = montageKey(moments)
        val runtime = AttachmentRegistry.attachmentsOrNull(entity)?.runtime
        runtime?.getOrNull<Cached>(this)?.takeIf { it.file === file && it.own === model.rig && it.montages == key }?.let { return it.rig }

        val rig = file.overlay(model.rig).withMontages(moments).rig.withNestedColliders(fileRigOf)
        runtime?.remove(this)
        runtime?.getOrPut(this) { Cached(file, model.rig, key, rig) }
        return rig
    }
}

/**
 * A model posed for its colliders apart from any drawing, as the server does it and as a client does it tick
 * by tick: the outermost model is animated, and every model hung on it stands at rest where its attachment
 * puts it, with what the rigs pose on top.
 */
internal class PosedModel(
    model: Model,
    rig: ModelRig,
    parent: SceneObject,
    name: String,
    assetsOf: (String) -> ColliderPoseAssets?,
    depth: Int = 0,
) : ColliderScope, AutoCloseable {
    private val instance = ModelSceneInstance.mount(parent.createModelRoot(name), model).also { it.setRigBones(rig) }
    override var rig: ModelRig = rig
        set(value) {
            instance.updateRigBoneRestTransforms(field, value)
            field = value
        }
    override val poseNodes: Collection<SceneObject> get() = instance.poseNodes
    fun poseTarget() = instance.poseTarget(rig)

    override val modelMatrix: Mat4f get() = instance.root.worldTransform
    override val isActive: Boolean get() = instance.root.isActive

    override val nested: List<PosedModel> = if (depth >= MAX_NESTING) emptyList() else rig.allAttachments().mapNotNull { (bone, spec) ->
        val attachment = spec as? ModelAttachmentSpec ?: return@mapNotNull null
        val assets = assetsOf(attachment.model) ?: return@mapNotNull null
        val owner = if (bone == null) instance.root else {
            instance.poseNodesByIndex.values.firstOrNull { it.poseName == bone } ?: return@mapNotNull null
        }
        PosedModel(assets.model, assets.rig.overlay(attachment.rig), owner, attachment.id.ifBlank { attachment.model }, assetsOf, depth + 1)
    }

    /** Puts every node of this model and of the models nested in it back at rest. */
    fun resetPose() {
        instance.resetPose()
        nested.forEach(PosedModel::resetPose)
    }

    /** Lays what the rigs pose over whatever the animator left, here and in every nested model. */
    fun applyRigPoses() {
        instance.applyRigPose(rig)
        nested.forEach(PosedModel::applyRigPoses)
    }

    /**
     * Bends the IK chains of this model. Nested models stand still in their rest pose, so only the outermost
     * one, which the animator moves, has anything to bend.
     */
    fun applyRigConstraints(context: AnimatorEvaluationContext) {
        instance.applyRigConstraints(rig, context)
    }

    /** Turns the posed nodes into matrices, the nested models' with them. */
    fun updateMatrices() {
        instance.applyAuthoredConstraints()
        nested.forEach(PosedModel::updateMatrices)
    }

    override fun close() = instance.close()
}
