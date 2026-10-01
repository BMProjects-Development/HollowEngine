package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.colliders.ClientColliderPoses
import ru.hollowhorizon.hollowengine.client.colliders.ClientColliderTickPoses
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import java.util.function.Predicate

/** Where this entity's colliders are now, as this side sees them. */
val Entity.colliders: List<EntityCollider> get() = EntityColliders.of(this)

/**
 * The colliders of entities, whichever side asks: the server places them from its own animator, a client
 * takes them from the pose it last drew.
 */
object EntityColliders {
    /** How far a collider may reach out of its entity's box and still be found by a search around the box. */
    private const val SEARCH_MARGIN = 4.0

    fun of(entity: Entity): List<EntityCollider> =
        if (entity.level().isClientSide) ClientColliderPoses.of(entity) else ServerColliderPoses.current(entity)

    /**
     * The colliders of [entity] that act on bodies, over the last ticks, newest first: posed tick by tick
     * on either side, so a client bumps into what the server moves mobs by.
     */
    fun physical(entity: Entity): List<List<EntityCollider>> =
        if (entity.level().isClientSide) ClientColliderTickPoses.recent(entity) else ServerColliderPoses.recent(entity)

    /** The entities in [level] whose colliders act on bodies. */
    fun physicalHosts(level: Level): List<Entity> =
        if (level.isClientSide) ClientColliderTickPoses.physical else ServerColliderPoses.physicalIn(level)

    fun rig(entity: Entity): ModelRig? =
        if (entity.level().isClientSide) ClientColliderPoses.rig(entity) else ServerColliderPoses.rig(entity)

    /** Whether [entity] has colliders of the [modes] that stand in for its box. */
    fun hasTargets(entity: Entity, modes: (ColliderModes) -> Boolean = ColliderModes::isTarget): Boolean =
        rig(entity)?.hasColliders(modes) == true

    /**
     * The nearest of [vanilla] and the colliders of the entities around [search] along the segment from
     * [start] to [end], counting the colliders of the [modes]. Entities that have such colliders are
     * expected to be left out of [vanilla].
     */
    fun pick(
        level: Level,
        source: Entity?,
        start: Vec3,
        end: Vec3,
        search: AABB,
        predicate: Predicate<Entity>,
        maxDistanceSquared: Double,
        vanilla: EntityHitResult?,
        modes: (ColliderModes) -> Boolean = ColliderModes::isTarget,
    ): EntityHitResult? {
        var nearest = vanilla
        var nearestDistance = vanilla?.let { start.distanceToSqr(surfaceOf(it, start, end)) } ?: maxDistanceSquared

        level.getEntities(source, search.inflate(SEARCH_MARGIN)) { predicate.test(it) && hasTargets(it, modes) }
            .forEach { entity ->
                if (source != null && entity.rootVehicle === source.rootVehicle) return@forEach
                of(entity).forEach { collider ->
                    if (!modes(collider.spec.modes)) return@forEach
                    val location = collider.box.clip(start, end) ?: return@forEach
                    val distance = start.distanceToSqr(location)
                    if (distance < nearestDistance) {
                        nearest = ColliderHitResult(entity, location, collider)
                        nearestDistance = distance
                    }
                }
            }
        return nearest
    }

    /** Where the segment meets the entity's box; some vanilla searches report the entity's position instead. */
    private fun surfaceOf(result: EntityHitResult, start: Vec3, end: Vec3): Vec3 {
        val entity = result.entity
        return entity.boundingBox.inflate(entity.pickRadius.toDouble()).clip(start, end).orElse(result.location)
    }
}
