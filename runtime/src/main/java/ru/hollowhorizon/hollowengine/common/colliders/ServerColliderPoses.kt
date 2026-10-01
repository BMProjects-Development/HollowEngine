package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
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
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.ServerModelAnimationMetadata

/**
 * Where the colliders of every entity are on the server, tick by tick.
 */
object ServerColliderPoses {
    const val HISTORY_TICKS = 10

    /** The colliders of [entity] as of the last tick, placed now if it has none yet. */
    fun current(entity: Entity): List<EntityCollider> = pose(entity)?.history?.firstOrNull().orEmpty()

    /** The colliders of [entity] over the last ticks, newest first. */
    fun recent(entity: Entity): List<List<EntityCollider>> = pose(entity)?.history.orEmpty()

    fun rig(entity: Entity): ModelRig? {
        val node = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelNodes()?.firstOrNull() ?: return null
        return ServerColliderAssets.of(node.model.model).rig
    }

    internal fun tick(entities: List<Entity>) {
        entities.forEach(::pose)
    }

    private fun pose(entity: Entity): EntityPose? {
        if (entity.level().isClientSide) return null
        val runtime = AttachmentRegistry.attachmentsOrNull(entity)?.runtime ?: return null
        val node = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelNodes()?.firstOrNull()
        val assets = node?.let { ServerColliderAssets.of(it.model.model) }
        val model = node?.takeIf { assets?.rig?.hasColliders() == true }
            ?.let { ServerModelAnimationMetadata.model(it.model.model) }
        if (node == null || assets == null || model == null) {
            runtime.remove(PoseKey)
            return null
        }

        var pose = runtime.getOrNull<EntityPose>(PoseKey)
        if (pose == null || pose.model !== model || pose.assets !== assets) {
            runtime.remove(PoseKey)
            pose = runtime.getOrPut(PoseKey) { EntityPose(model, assets) }
        }
        pose.advance(entity, node)
        return pose
    }

    private data object PoseKey
}

@SubscribeEvent
fun onColliderServerTick(event: TickEvent.Server) {
    event.server.allLevels.forEach { level ->
        val entities = AttachmentRegistry.entitySnapshots(level).map { it.first }
        ServerColliderPoses.tick(entities)
        entities.forEach(ColliderPush::pushAround)
    }
}

/** The skeleton of one entity's model on the server, posed once a tick. */
private class EntityPose(val model: Model, val assets: ServerColliderAssets.Assets) {
    private val roots = restPose(model)
    private val nodes = roots.flatMap { it.walk() }
    private val target = PoseTarget(roots.byIndex(), model.animationsByName, assets.rig.boneByAlias)
    private val animator = ModelAnimator()
    private val context = AnimatorEvaluationContext()
    private var posedAt = Long.MIN_VALUE

    val history = ArrayDeque<List<EntityCollider>>()

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
        history.addFirst(placed)
        while (history.size > ServerColliderPoses.HISTORY_TICKS) history.removeLast()
    }
}
