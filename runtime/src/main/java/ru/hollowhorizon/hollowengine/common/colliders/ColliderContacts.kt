package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * What colliders that act on bodies do to an entity, once a tick before it moves: a solid collider carries
 * what stands on it and puts out what it ran into, a pushing one shoves what stands in it, and a moving one
 * passes its speed on, so a swung club sends a player flying and the player keeps flying once it stops.
 */
internal object ColliderContacts {
    /** Speed a pushing collider adds per block of overlap, per tick, when it stands still. */
    private const val PUSH_STRENGTH = 0.4

    /** The most a still pushing collider adds in one tick, so a deep overlap does not throw anything across the room. */
    private const val MAX_PUSH = 0.15

    /** How far below its feet an entity still counts as standing on a collider. */
    private const val SUPPORT_DEPTH = 0.05

    private const val NEAR = 0.25
    private const val EPSILON = 1.0e-4

    fun isSimulatedHere(entity: Entity): Boolean =
        if (entity.level().isClientSide) entity.isControlledByLocalInstance && entity is Player else entity !is Player

    fun resolve(entity: Entity) {
        if (entity.noPhysics || entity.isSpectator || !isSimulatedHere(entity)) return
        val near = entity.boundingBox.inflate(NEAR)
        EntityColliders.physicalHosts(entity.level()).forEach { host ->
            if (host === entity || host.rootVehicle === entity.rootVehicle) return@forEach
            val ticks = EntityColliders.physical(host)
            val now = ticks.firstOrNull() ?: return@forEach
            val before = ticks.getOrNull(1).orEmpty()
            now.forEach { collider ->
                if (!collider.spec.modes.isPhysical || !collider.box.bounds.intersects(near)) return@forEach
                val previous = before.firstOrNull { it.name == collider.name && it.bone == collider.bone }?.box
                if (collider.spec.modes.solid) touchSolid(entity, collider, previous) else touchPushing(entity, collider, previous)
            }
        }
    }

    private fun touchSolid(entity: Entity, collider: EntityCollider, previous: ColliderBox?) {
        val box = collider.box
        val overlap = box.penetration(entity.boundingBox)
        if (overlap == null) {
            val feet = Vec3(entity.x, entity.boundingBox.minY, entity.z)
            val support = previous ?: box
            val standing = entity.onGround() && support.penetration(entity.boundingBox.move(0.0, -SUPPORT_DEPTH, 0.0)) != null
            if (standing) motionAt(feet, previous, box)?.let { entity.move(MoverType.SHULKER_BOX, it) }
            return
        }
        entity.move(MoverType.SHULKER_BOX, overlap)
        passOn(entity, overlap, motionAt(entity.boundingBox.center, previous, box), collider.spec.modes.force)
    }

    private fun touchPushing(entity: Entity, collider: EntityCollider, previous: ColliderBox?) {
        if (!entity.isPushable) return
        val box = collider.box
        val overlap = box.penetration(entity.boundingBox) ?: return
        val motion = motionAt(entity.boundingBox.center, previous, box)
        if (motion == null || motion.lengthSqr() < EPSILON * EPSILON) {
            val direction = horizontal(overlap) ?: horizontal(entity.position().subtract(box.center)) ?: return
            val speed = (horizontalLength(overlap) * PUSH_STRENGTH).coerceAtMost(MAX_PUSH)
            entity.push(direction.x * speed, 0.0, direction.z * speed)
            return
        }
        passOn(entity, overlap, motion, collider.spec.modes.force)
    }

    /**
     * Gives [entity] the speed [motion] has along [overlap], the direction the collider pushes it out in,
     * unless it is already moving away faster. What it has across stays, so it slides along the collider.
     */
    private fun passOn(entity: Entity, overlap: Vec3, motion: Vec3?, force: Float) {
        motion ?: return
        val length = overlap.length()
        if (length < EPSILON) return
        val normal = overlap.scale(1.0 / length)
        val pushing = motion.dot(normal) * force
        if (pushing <= 0.0) return

        val velocity = entity.deltaMovement
        val along = velocity.dot(normal)
        if (along >= pushing) return
        entity.deltaMovement = velocity.add(normal.scale(pushing - along))
        entity.hurtMarked = true
    }

    /** How far the collider moved the point [point] of itself over the last tick, or null when it has no last tick. */
    private fun motionAt(point: Vec3, previous: ColliderBox?, box: ColliderBox): Vec3? =
        previous?.carry(point, box)?.subtract(point)

    private fun horizontal(vector: Vec3): Vec3? {
        val length = horizontalLength(vector)
        return if (length < EPSILON) null else Vec3(vector.x / length, 0.0, vector.z / length)
    }

    private fun horizontalLength(vector: Vec3): Double = sqrt(vector.x * vector.x + vector.z * vector.z)}
