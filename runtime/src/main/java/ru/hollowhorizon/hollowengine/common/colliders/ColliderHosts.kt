package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB

/**
 * An entity with colliders, as the last tick left them: [bounds] is everything they covered, so the entity
 * is found by its colliders however far they reach out of its box.
 */
class ColliderHost(val entity: Entity, val bounds: AABB)

/**
 * An entity with some of its colliders: where they are this tick, where they were the tick before, and
 * [bounds] around both, which is all one tick of their motion swept.
 */
class PosedHost private constructor(
    val entity: Entity,
    val now: List<EntityCollider>,
    private val before: List<EntityCollider>,
    val bounds: AABB,
) {
    /** Where [collider] was the tick before, or null when it was not there yet. */
    fun previousOf(collider: EntityCollider): ColliderVolume? =
        before.firstOrNull { it.name == collider.name && it.bone == collider.bone }?.volume

    companion object {
        /**
         * The colliders of [entity] of the [modes] over its [history], newest first, or null when none of
         * them is anywhere now.
         */
        fun of(
            entity: Entity,
            history: List<List<EntityCollider>>,
            modes: (ColliderModes) -> Boolean = ColliderModes::isPhysical,
        ): PosedHost? {
            val now = history.firstOrNull()?.filter { modes(it.spec.modes) }.orEmpty()
            if (now.isEmpty()) return null
            val before = history.getOrNull(1).orEmpty().filter { modes(it.spec.modes) }
            val bounds = (now + before).map { it.volume.bounds }.reduce(AABB::minmax)
            return PosedHost(entity, now, before, bounds)
        }
    }
}
