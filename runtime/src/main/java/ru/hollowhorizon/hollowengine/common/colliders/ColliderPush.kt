package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Pushing colliders shove whatever stands in them sideways, the way mobs shove each other.
 */
internal object ColliderPush {
    /** Speed added per block of overlap, per tick. */
    private const val STRENGTH = 0.4

    /** The most one tick adds, so a deep overlap does not throw anything across the room. */
    private const val MAX_SPEED = 0.15

    /** Pushes everything but players out of the pushing colliders of [host]. */
    fun pushAround(host: Entity) {
        val colliders = pushing(host)
        if (colliders.isEmpty()) return

        val area = colliders.map { it.box.bounds }.reduce(AABB::minmax)
        host.level().getEntities(host, area) { it !is Player && canBePushed(it, host) }.forEach { other ->
            push(other, colliders)
        }
    }

    /** Pushes [player] out of the pushing colliders of the entities around it. */
    fun pushPlayer(player: Player) {
        player.level().getEntities(player, player.boundingBox.inflate(SEARCH_RADIUS)) {
            canBePushed(player, it) && EntityColliders.hasTargets(it, ColliderModes::push)
        }.forEach { host -> push(player, pushing(host)) }
    }

    private fun pushing(host: Entity): List<EntityCollider> =
        if (EntityColliders.hasTargets(host, ColliderModes::push)) EntityColliders.of(host).filter { it.spec.modes.push }
        else emptyList()

    private fun canBePushed(entity: Entity, host: Entity): Boolean =
        !entity.isSpectator && entity.isPushable && !entity.noPhysics && entity.rootVehicle !== host.rootVehicle

    private fun push(entity: Entity, colliders: List<EntityCollider>) {
        colliders.forEach { collider ->
            val overlap = collider.box.penetration(entity.boundingBox) ?: return@forEach
            val direction = horizontal(overlap) ?: horizontal(entity.position().subtract(collider.box.center)) ?: return@forEach
            val speed = (horizontalLength(overlap).coerceAtLeast(MIN_OVERLAP) * STRENGTH).coerceAtMost(MAX_SPEED)
            entity.push(direction.x * speed, 0.0, direction.z * speed)
        }
    }

    private fun horizontal(vector: Vec3): Vec3? {
        val length = horizontalLength(vector)
        return if (length < EPSILON) null else Vec3(vector.x / length, 0.0, vector.z / length)
    }

    private fun horizontalLength(vector: Vec3): Double = sqrt(vector.x * vector.x + vector.z * vector.z)

    private const val SEARCH_RADIUS = 4.0
    private const val MIN_OVERLAP = 0.05
    private const val EPSILON = 1.0e-4
}
