package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigConstraints
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.models.MAX_NESTING
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.attachments.components.Model as ModelComponent

/**
 * One model of an entity as its colliders see it: the nodes as posed, the rig laid over them, and the models
 * hung on it, which carry colliders of their own.
 */
internal interface ColliderScope {
    val roots: List<RuntimeNode>
    val rig: ModelRig

    /** Where this model stands in the outermost model's space; null for the outermost model. */
    val modelMatrix: Mat4f?
    val nested: List<ColliderScope>
}

/** Every collider of this model and of the models nested in it. */
internal fun ColliderScope.placeColliders(toEntity: Mat4f, origin: Vec3): List<EntityCollider> =
    rig.placeColliders(roots, toEntity, origin, modelMatrix) + nested.flatMap { it.placeColliders(toEntity, origin) }

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
 * The rig of an entity's model with what the entity hangs on it, colliders of nested models gathered in;
 * worked out again only when the model's rig file or the entity's own rig is replaced.
 */
internal class EntityRigs(private val fileRigOf: (String) -> ModelRig) {
    private class Cached(val file: ModelRig, val own: ModelRig, val rig: ModelRig)

    fun of(entity: Entity, model: ModelComponent): ModelRig {
        val file = fileRigOf(model.model)
        val runtime = AttachmentRegistry.attachmentsOrNull(entity)?.runtime
        runtime?.getOrNull<Cached>(this)?.takeIf { it.file === file && it.own === model.rig }?.let { return it.rig }

        val rig = file.overlay(model.rig).withNestedColliders(fileRigOf)
        runtime?.remove(this)
        runtime?.getOrPut(this) { Cached(file, model.rig, rig) }
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
    override val rig: ModelRig,
    private val holder: Attachment?,
    assetsOf: (String) -> ColliderPoseAssets?,
    depth: Int = 0,
) : ColliderScope {
    override val roots: List<RuntimeNode> = model.scenes.getOrNull(model.scene)?.nodes.orEmpty().map { RuntimeNode(it, holder) }
    private val nodes = roots.flatMap { it.walk() }

    /** The models hung on this model itself, which no bone carries. */
    private val onModel = ArrayList<NestHolder>()

    override val modelMatrix: Mat4f? get() = holder?.globalMatrix

    override val nested: List<PosedModel> = if (depth >= MAX_NESTING) emptyList() else rig.allAttachments().mapNotNull { (bone, spec) ->
        val attachment = spec as? ModelAttachmentSpec ?: return@mapNotNull null
        val assets = assetsOf(attachment.model) ?: return@mapNotNull null
        val host = bone?.let { name -> nodes.firstOrNull { it.name == name } ?: return@mapNotNull null }
        val nest = NestHolder(host ?: holder, attachment)
        if (host != null) host.attachments += nest else onModel += nest
        PosedModel(assets.model, assets.rig.overlay(attachment.rig), nest, assetsOf, depth + 1).also { nest.model = it }
    }

    /** Puts every node of this model and of the models nested in it back at rest. */
    fun resetPose() {
        nodes.forEach(RuntimeNode::resetPose)
        nested.forEach(PosedModel::resetPose)
    }

    /** Lays what the rigs pose over whatever the animator left, here and in every nested model. */
    fun applyRigPoses() {
        applyRigPose(roots, rig)
        nested.forEach(PosedModel::applyRigPoses)
    }

    /**
     * Bends the IK chains of this model. Nested models stand still in their rest pose, so only the outermost
     * one, which the animator moves, has anything to bend.
     */
    fun applyRigConstraints(context: AnimatorEvaluationContext) = applyRigConstraints(roots, rig, context)

    /** Turns the posed nodes into matrices, the nested models' with them. */
    fun updateMatrices() {
        roots.forEach(RuntimeNode::updateHierarchyMatrices)
        onModel.forEach(Attachment::updateGlobalMatrix)
    }

    private class NestHolder(parent: Attachment?, spec: ModelAttachmentSpec) : Attachment(parent) {
        lateinit var model: PosedModel

        init {
            transform.set(spec.localTransform())
        }

        override fun updateGlobalMatrix() {
            super.updateGlobalMatrix()
            model.updateMatrices()
        }
    }
}
