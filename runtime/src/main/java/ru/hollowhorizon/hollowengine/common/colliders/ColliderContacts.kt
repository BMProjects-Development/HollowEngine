package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.EntityBodies
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

    private val displacing = ThreadLocal.withInitial { false }

    /** Whether the move being made now is a collider moving an entity rather than the entity moving itself. */
    val isDisplacing: Boolean get() = displacing.get()

    fun isSimulatedHere(entity: Entity): Boolean =
        if (entity.level().isClientSide) entity.isControlledByLocalInstance && entity is Player else entity !is Player

    fun resolve(entity: Entity) {
        if (entity.noPhysics || entity.isSpectator || !isSimulatedHere(entity)) return
        leaveWalls(entity)
        if (EntityBodies.isMovedByOthers(entity)) pushBack(entity)
        val near = entity.boundingBox.inflate(NEAR)
        EntityColliders.physicalHosts(entity.level()).forEach { host ->
            if (host === entity || host.rootVehicle === entity.rootVehicle) return@forEach
            val ticks = EntityColliders.physical(host)
            val now = ticks.firstOrNull() ?: return@forEach
            val before = ticks.getOrNull(1).orEmpty()
            now.forEach { collider ->
                if (!collider.spec.modes.isPhysical || !collider.box.bounds.intersects(near)) return@forEach
                val previous = previousOf(collider, before)

                if (collider.spec.modes.solid) touchSolid(entity, collider, previous)
                else touchPushing(entity, collider, previous)
            }
        }
    }

    /** Moves [host] out of the walls its own solid colliders turned or swung into over the last tick. */
    private fun leaveWalls(host: Entity) {
        if (!EntityColliders.hasTargets(host, ColliderModes::solid)) return
        val ticks = EntityColliders.physical(host)
        val before = ticks.getOrNull(1).orEmpty()
        val solid = ticks.firstOrNull().orEmpty().filter { it.spec.modes.solid }.map { it.box to previousOf(it, before) }
        SolidColliders.outOfWalls(host, solid)?.let { displace(host, it) }
    }

    private fun previousOf(collider: EntityCollider, before: List<EntityCollider>): ColliderBox? =
        before.firstOrNull { it.name == collider.name && it.bone == collider.bone }?.box

    private fun touchSolid(entity: Entity, collider: EntityCollider, previous: ColliderBox?) {
        val box = collider.box
        val support = previous ?: box
        if (entity.onGround() && support.penetration(entity.boundingBox.move(0.0, -SUPPORT_DEPTH, 0.0)) != null) {
            ride(entity, box, previous)
            return
        }
        val overlap = box.penetration(entity.boundingBox) ?: return
        displace(entity, overlap)
        passOn(entity, overlap, motionAt(entity.boundingBox.center, previous, box), collider.spec.modes.force)
    }

    /**
     * Carries [entity], which stands on the collider, along with it and lifts it out where the collider rose
     * into it. What rides a collider takes none of its speed: it stops when the collider stops.
     */
    private fun ride(entity: Entity, box: ColliderBox, previous: ColliderBox?) {
        val feet = Vec3(entity.x, entity.boundingBox.minY, entity.z)
        motionAt(feet, previous, box)?.takeIf { it.lengthSqr() >= EPSILON * EPSILON }?.let { displace(entity, it) }

        val lift = box.lift(entity.boundingBox) ?: return
        if (lift <= entity.maxUpStep()) displace(entity, Vec3(0.0, lift, 0.0))
        else box.penetration(entity.boundingBox)?.let { displace(entity, it) }
    }

    private fun touchPushing(entity: Entity, collider: EntityCollider, previous: ColliderBox?) {
        if (!EntityBodies.isMovedByOthers(entity)) return
        val box = collider.box
        val overlap = box.penetration(entity.boundingBox) ?: return
        val motion = motionAt(entity.boundingBox.center, previous, box)
        if (motion == null || motion.lengthSqr() < EPSILON * EPSILON) {
            stillPush(box, entity, overlap)?.let { entity.push(it.x, 0.0, it.z) }
            return
        }
        passOn(entity, overlap, motion, collider.spec.modes.force)
    }

    /**
     * Pushes [host] back from what stands in its still pushing colliders, the way mobs push each other both
     * ways. A collider swung by an animation does not push its own entity back.
     */
    private fun pushBack(host: Entity) {
        val ticks = EntityColliders.physical(host)
        val now = ticks.firstOrNull() ?: return
        val before = ticks.getOrNull(1).orEmpty()
        now.forEach { collider ->
            if (!collider.spec.modes.pushes) return@forEach
            val box = collider.box
            val previous = previousOf(collider, before)
            val motion = motionAt(box.center, previous, box)
            if (motion != null && motion.lengthSqr() >= EPSILON * EPSILON) return@forEach

            host.level().getEntities(host, box.bounds) { other ->
                other is LivingEntity && !other.isSpectator && other.rootVehicle !== host.rootVehicle
            }.forEach { other ->
                val overlap = box.penetration(other.boundingBox) ?: return@forEach
                stillPush(box, other, overlap)?.let { host.push(-it.x, 0.0, -it.z) }
            }
        }
    }

    /** The push a still collider gives [entity], which overlaps it by [overlap]: away from it, the deeper the stronger. */
    private fun stillPush(box: ColliderBox, entity: Entity, overlap: Vec3): Vec3? {
        val direction = horizontal(overlap) ?: horizontal(entity.position().subtract(box.center)) ?: return null
        val speed = (horizontalLength(overlap) * PUSH_STRENGTH).coerceAtMost(MAX_PUSH)
        return direction.scale(speed)
    }

    /**
     * Moves [entity] by [motion] through vanilla's collision. Vanilla's move decides whether the entity is
     * on the ground from that move alone, and a collider's nudge is no fall: the entity keeps the ground it
     * had, or it could not jump and would glide as on ice for the rest of the tick.
     */
    private fun displace(entity: Entity, motion: Vec3) {
        val grounded = entity.onGround()
        displacing.set(true)
        try {
            entity.move(MoverType.SHULKER_BOX, motion)
        } finally {
            displacing.set(false)
        }
        entity.setOnGround(grounded)
    }

    /**
     * Gives [entity] the speed [motion] has along [overlap], the direction the collider pushes it out in,
     * unless it is already moving away faster.
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

    private fun horizontalLength(vector: Vec3): Double = sqrt(vector.x * vector.x + vector.z * vector.z)
}
