package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelNodes
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import java.util.WeakHashMap

/**
 * Where the colliders of every entity are on the server, tick by tick.
 *
 * The server runs the animator the client draws with, so an animated limb is where players saw it, and
 * keeps the last [HISTORY_TICKS] ticks: a client sees the world late by its ping, and what it claims to
 * have hit is checked against the ticks it could have been looking at.
 */
object ServerColliderPoses {
    const val HISTORY_TICKS = 10

    private val tracks = ColliderPoseTracks(HISTORY_TICKS) { model -> ServerColliderAssets.of(model).pose }

    /** The entities of each level whose colliders act on bodies, as of the last tick. Server thread only. */
    private val physical = WeakHashMap<Level, List<Entity>>()

    /** The colliders of [entity] as of the last tick, placed now if it has none yet. */
    fun current(entity: Entity): List<EntityCollider> = recent(entity).firstOrNull().orEmpty()

    /** The colliders of [entity] over the last ticks, newest first. */
    fun recent(entity: Entity): List<List<EntityCollider>> =
        if (entity.level().isClientSide) emptyList() else tracks.track(entity)?.history.orEmpty()

    fun rig(entity: Entity): ModelRig? {
        val node = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelNodes()?.firstOrNull() ?: return null
        return ServerColliderAssets.of(node.model.model).rig
    }

    internal fun physicalIn(level: Level): List<Entity> = physical[level].orEmpty()

    internal fun tick(level: Level, entities: List<Entity>) {
        physical[level] = entities.filter { entity ->
            tracks.track(entity)?.assets?.rig?.hasColliders(ColliderModes::isPhysical) == true
        }
    }
}

@SubscribeEvent
fun onColliderServerTick(event: TickEvent.Server) {
    event.server.allLevels.forEach { level ->
        ServerColliderPoses.tick(level, AttachmentRegistry.entitySnapshots(level).map { it.first })
    }
}
