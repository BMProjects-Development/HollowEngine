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
 * The colliders of entities, whichever side asks: both place them from the animator tick by tick, the same
 * way, and a client takes them between its last two ticks, where it draws the entity.
 */
object EntityColliders {
    /** How far a collider may move between the tick that placed it and a frame or a projectile looking for it. */
    private const val TICK_MOTION = 1.0

    fun of(entity: Entity): List<EntityCollider> =
        if (entity.level().isClientSide) ClientColliderTickPoses.now(entity) else ServerColliderPoses.current(entity)

    /**
     * The colliders of [entity] that act on bodies, over the last ticks, newest first: posed tick by tick
     * on either side, so a client bumps into what the server moves mobs by.
     */
    fun physical(entity: Entity): List<List<EntityCollider>> =
        if (entity.level().isClientSide) ClientColliderTickPoses.recent(entity) else ServerColliderPoses.recent(entity)

    /** The entities in [level] whose colliders act on bodies. */
    fun physicalHosts(level: Level): List<PosedHost> =
        if (level.isClientSide) ClientColliderTickPoses.physical else ServerColliderPoses.physicalIn(level)

    /** The entities in [level] with colliders, found by where the colliders are rather than by their boxes. */
    fun hosts(level: Level): List<ColliderHost> =
        if (level.isClientSide) ClientColliderTickPoses.hosts else ServerColliderPoses.hostsIn(level)

    fun rig(entity: Entity): ModelRig? =
        if (entity.level().isClientSide) ClientColliderPoses.rig(entity) else ServerColliderPoses.rig(entity)

    /** Whether [entity] has colliders of the [modes] that stand in for its box. */
    fun hasTargets(entity: Entity, modes: (ColliderModes) -> Boolean = ColliderModes::isTarget): Boolean {
        val rig = rig(entity) ?: return false
        if (entity.collidersComponent == null) return rig.hasColliders(modes)
        return effectiveSpecs(entity, rig).any { modes(it.modes) }
    }

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

        candidates(level, source, search) { predicate.test(it) && hasTargets(it, modes) }
            .forEach { entity ->
                if (source != null && entity.rootVehicle === source.rootVehicle) return@forEach
                of(entity).forEach { collider ->
                    if (!modes(collider.spec.modes)) return@forEach
                    val location = collider.volume.clip(start, end) ?: return@forEach
                    val distance = start.distanceToSqr(location)
                    if (distance < nearestDistance) {
                        nearest = ColliderHitResult(entity, location, collider)
                        nearestDistance = distance
                    }
                }
            }
        return nearest
    }

    /**
     * The entities whose colliders may be in [search]: those whose colliders were around it on the last tick,
     * however big they are, and those whose boxes are in it, which covers an entity posed for the first time.
     */
    private fun candidates(level: Level, source: Entity?, search: AABB, predicate: (Entity) -> Boolean): Set<Entity> {
        val near = hosts(level).filter { it.entity !== source && it.bounds.inflate(TICK_MOTION).intersects(search) }.map { it.entity }
        return (near + level.getEntities(source, search)).filterTo(LinkedHashSet()) { !it.isRemoved && predicate(it) }
    }

    /** Where the segment meets the entity's box; some vanilla searches report the entity's position instead. */
    private fun surfaceOf(result: EntityHitResult, start: Vec3, end: Vec3): Vec3 {
        val entity = result.entity
        return entity.boundingBox.inflate(entity.pickRadius.toDouble()).clip(start, end).orElse(result.location)
    }
}
