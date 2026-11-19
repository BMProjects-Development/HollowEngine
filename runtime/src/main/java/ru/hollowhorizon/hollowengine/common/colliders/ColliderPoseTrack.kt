package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ModelNodeEntry
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelNodes
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.withMontages
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.scene.hasIdentityPose
import ru.hollowhorizon.hollowengine.common.scene.hasSamePoseAs
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

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
    private val assetsOf: (String) -> ColliderPoseAssets?,
    private val logicalSide: LogicalSide,
) : AutoCloseable {
    /** The rig before montages; what they hang on it and animate is laid over it tick by tick. */
    private val rig = assets.rig.overlay(own)
    private var current = buildScene(rig)
    private val posed: PosedModel get() = current.posed

    /** Which attachments the montages hung on the model as it was last built; see [MontageRig.key]. */
    private var montageKey: List<Any> = emptyList()
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

        val local = AttachmentRegistry.attachmentsOrNull(entity)?.modelTransformOrNull
        current.place(local)
        posed.resetPose()
        animator.configure(assets.animator, node.animations)
        fillAnimationVariables(context, entity, 1f)
        context.modelToWorld = resolveNodeWorldTransform(entity, 1f)
        animator.applyTo(current.target, context)
        val laid = rig.withMontages(context.montages)
        if (laid.key != montageKey) {
            val replacement = buildScene(laid.rig)
            replacement.place(local)
            val previous = current
            current = replacement
            montageKey = laid.key
            previous.close()
            animator.applyTo(current.target, context)
        } else {
            posed.rig = laid.rig
        }
        posed.applyRigPoses()
        posed.applyRigConstraints(context)
        posed.updateMatrices()

        val placed = applyOverrides(entity, posed.placeColliders(hostLinearMatrix(entity, 1f), hostPosition(entity, 1f)))
        bounds = placed.map { it.volume.bounds }.reduceOrNull(AABB::minmax)
        history.addFirst(placed)
        while (history.size > historyTicks) history.removeLast()
    }

    private fun buildScene(rig: ModelRig): PosedScene {
        val scene = Scene(object : SceneContext { override val side = logicalSide })
        try {
            val root = scene.create("colliders")
            return PosedScene(root, PosedModel(assets.model, rig, root, "model", assetsOf))
        } catch (exception: Exception) {
            try {
                scene.close()
            } catch (cleanup: Exception) {
                if (cleanup !== exception) exception.addSuppressed(cleanup)
            }
            throw exception
        }
    }

    override fun close() = current.close()

    private class PosedScene(val root: SceneObject, val posed: PosedModel) : AutoCloseable {
        val target = posed.poseTarget()
        fun place(value: TrsTransformF?) {
            val local = root.localTransform
            if (value == null) {
                if (!local.hasIdentityPose()) local.setIdentity()
            } else if (!local.hasSamePoseAs(value)) {
                local.set(value)
            }
        }

        override fun close() = root.scene.close()
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
            track = runtime.getOrPut(this) {
                ColliderPoseTrack(assets, own, historyTicks, assetsOf, if (entity.level().isClientSide) LogicalSide.CLIENT else LogicalSide.SERVER)
            }
        }
        if (advance || track.history.isEmpty()) track.advance(entity, node)
        return track
    }
}
