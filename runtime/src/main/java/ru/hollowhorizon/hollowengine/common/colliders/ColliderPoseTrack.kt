package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.ModelAnimator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.byIndex
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ModelNodeEntry
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelNodes
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/** What a side poses a model's colliders from: the model's rig file, the animator it wears, and the model. */
class ColliderPoseAssets(val rig: ModelRig, val animator: Animator?, val model: Model)

/**
 * The skeleton of one entity's model and of the models hung on it, posed once a tick by the animator the
 * client draws with, and where its colliders were over the last ticks, newest first.
 */
internal class ColliderPoseTrack(
    val assets: ColliderPoseAssets,
    /** What the entity hangs on its model, over the rig file; a track is made again when it changes. */
    val own: ModelRig,
    private val historyTicks: Int,
    assetsOf: (String) -> ColliderPoseAssets?,
) {
    private val posed = PosedModel(assets.model, assets.rig.overlay(own), null, assetsOf)
    private val target = PoseTarget(posed.roots.byIndex(), assets.model.animationsByName, posed.rig.boneByAlias, posed.rig)
    private val animator = ModelAnimator()
    private val context = AnimatorEvaluationContext()
    private var posedAt = Long.MIN_VALUE

    val history = ArrayDeque<List<EntityCollider>>()

    /** Everything the colliders cover this tick, or null when the entity has none. */
    var bounds: AABB? = null
        private set

    fun advance(entity: Entity, node: ModelNodeEntry) {
        val now = entity.level().gameTime
        if (posedAt == now) return
        posedAt = now

        posed.resetPose()
        animator.configure(assets.animator, node.animations)
        fillAnimationVariables(context, entity, 1f)
        context.modelToWorld = resolveNodeWorldTransform(entity, node.transform, 1f)
        animator.applyTo(target, context)
        posed.applyRigPoses()
        posed.applyRigConstraints(context)
        posed.updateMatrices()

        val placed = applyOverrides(entity, posed.placeColliders(entityModelMatrix(entity, node.transform, 1f), hostPosition(entity, 1f)))
        bounds = placed.map { it.volume.bounds }.reduceOrNull(AABB::minmax)
        history.addFirst(placed)
        while (history.size > historyTicks) history.removeLast()
    }
}

/**
 * A track per entity, kept in its runtime attachments: made from what [assetsOf] finds for the entity's
 * model and what the entity hangs on it, and made again when either changes, as after a reload or an edit.
 */
internal class ColliderPoseTracks(
    private val historyTicks: Int,
    private val assetsOf: (model: String) -> ColliderPoseAssets?,
    private val posedAnyway: (model: String) -> Boolean = { false },
) {
    /**
     * The track of [entity], or null when nothing on its model needs posing. Only the tick poses it again,
     * with [advance]: whatever asks during a tick sees the poses the last one ended with.
     */
    fun track(entity: Entity, advance: Boolean = false): ColliderPoseTrack? {
        val runtime = AttachmentRegistry.attachmentsOrNull(entity)?.runtime ?: return null
        val node = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelNodes()?.firstOrNull()
        val needed = node != null && (EntityColliders.rig(entity)?.hasColliders() == true || posedAnyway(node.model.model))
        val assets = node?.takeIf { needed }?.let { assetsOf(it.model.model) }
        if (node == null || assets == null) {
            runtime.remove(this)
            return null
        }

        val own = node.model.rig
        var track = runtime.getOrNull<ColliderPoseTrack>(this)
        if (track == null || track.assets !== assets || track.own != own) {
            runtime.remove(this)
            track = runtime.getOrPut(this) { ColliderPoseTrack(assets, own, historyTicks, assetsOf) }
        }
        if (advance || track.history.isEmpty()) track.advance(entity, node)
        return track
    }
}
