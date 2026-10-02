package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.ModelAnimator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.byIndex
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ModelNodeEntry
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelNodes
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/** What a side poses an entity's colliders from: the rig, the animator the model wears, and the model. */
class ColliderPoseAssets(val rig: ModelRig, val animator: Animator?, val model: Model)

/**
 * The skeleton of one entity's model, posed once a tick by the animator the client draws with, and
 * where its colliders were over the last ticks, newest first.
 */
internal class ColliderPoseTrack(val assets: ColliderPoseAssets, private val historyTicks: Int) {
    private val roots = restPose(assets.model)
    private val nodes = roots.flatMap { it.walk() }
    private val target = PoseTarget(roots.byIndex(), assets.model.animationsByName, assets.rig.boneByAlias)
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

        nodes.forEach(RuntimeNode::resetPose)
        animator.configure(assets.animator, node.animations)
        fillAnimationVariables(context, entity, 1f)
        animator.applyTo(target, context)
        roots.forEach(RuntimeNode::updateHierarchyMatrices)

        val placed = assets.rig.placeColliders(roots, entityModelMatrix(entity, node.transform, 1f), hostPosition(entity, 1f))
        bounds = placed.map { it.box.bounds }.reduceOrNull(AABB::minmax)
        history.addFirst(placed)
        while (history.size > historyTicks) history.removeLast()
    }
}

/**
 * A track per entity, kept in its runtime attachments: made from what [assetsOf] finds for the entity's
 * model, and made again when that changes, as after a reload.
 */
internal class ColliderPoseTracks(
    private val historyTicks: Int,
    private val assetsOf: (model: String) -> ColliderPoseAssets?,
) {
    /**
     * The track of [entity], or null when its model gives it nothing to pose. Only the tick poses it again,
     * with [advance]: whatever asks during a tick sees the poses the last one ended with.
     */
    fun track(entity: Entity, advance: Boolean = false): ColliderPoseTrack? {
        val runtime = AttachmentRegistry.attachmentsOrNull(entity)?.runtime ?: return null
        val node = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelNodes()?.firstOrNull()
        val assets = node?.let { assetsOf(it.model.model) }
        if (node == null || assets == null) {
            runtime.remove(this)
            return null
        }

        var track = runtime.getOrNull<ColliderPoseTrack>(this)
        if (track == null || track.assets !== assets) {
            runtime.remove(this)
            track = runtime.getOrPut(this) { ColliderPoseTrack(assets, historyTicks) }
        }
        if (advance || track.history.isEmpty()) track.advance(entity, node)
        return track
    }
}
