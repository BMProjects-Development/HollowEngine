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

    private val displacedBy = ThreadLocal<Entity?>()

    /** Whether the move being made now is a collider moving an entity rather than the entity moving itself. */
    val isDisplacing: Boolean get() = displacedBy.get() != null

    /** The entity whose colliders are moving another one right now: they let it through on its way out of them. */
    val displacer: Entity? get() = displacedBy.get()

    fun isSimulatedHere(entity: Entity): Boolean =
        if (entity.level().isClientSide) entity.isControlledByLocalInstance && entity is Player else entity !is Player

    fun resolve(entity: Entity) {
        if (entity.noPhysics || entity.isSpectator || !isSimulatedHere(entity)) return
        val hosts = EntityColliders.physicalHosts(entity.level())
        hosts.firstOrNull { it.entity === entity }?.let { own ->
            leaveWalls(own)
            if (EntityBodies.isMovedByOthers(entity)) pushBack(own)
        }

        val near = entity.boundingBox.inflate(NEAR)
        hosts.forEach { host ->
            if (host.entity === entity || host.entity.rootVehicle === entity.rootVehicle || !host.bounds.intersects(near)) {
                return@forEach
            }
            host.now.forEach { collider ->
                val previous = host.previousOf(collider)
                val swept = previous?.bounds?.minmax(collider.volume.bounds) ?: collider.volume.bounds
                if (!swept.intersects(near)) return@forEach

                if (collider.spec.modes.solid) touchSolid(entity, host.entity, collider, previous)
                else touchPushing(entity, collider, previous)
            }
        }
    }

    /** Moves [own]'s entity out of the walls its solid colliders turned or swung into over the last tick. */
    private fun leaveWalls(own: PosedHost) {
        val solid = own.now.filter { it.spec.modes.solid }
        val moved = solid.any { collider -> own.previousOf(collider)?.sameAs(collider.volume) == false }
        if (!moved) return
        val out = SolidColliders.outOfWalls(own.entity, solid.map { it.volume to own.previousOf(it) }) ?: return
        displace(own.entity, own.entity, out)
    }

    private fun touchSolid(entity: Entity, host: Entity, collider: EntityCollider, previous: ColliderVolume?) {
        val box = collider.volume
        val support = previous ?: box
        if (entity.onGround() && support.penetration(entity.boundingBox.move(0.0, -SUPPORT_DEPTH, 0.0)) != null) {
            ride(entity, host, box, previous)
            return
        }
        val touch = box.firstTouch(previous, entity.boundingBox) ?: return
        val center = entity.boundingBox.center

        val out = center.add(touch.overlap)
        val target = if (touch.pose === box) out else touch.pose.carry(out, box) ?: out
        displace(entity, host, target.subtract(center))
        box.penetration(entity.boundingBox)?.let { displace(entity, host, it) }
        passOn(entity, touch.overlap, motionAt(center, previous, box), collider.spec.modes.force)
    }

    /**
     * Carries [entity], which stands on the collider, along with it and lifts it out where the collider rose
     * into it. What rides a collider takes none of its speed: it stops when the collider stops.
     */
    private fun ride(entity: Entity, host: Entity, box: ColliderVolume, previous: ColliderVolume?) {
        val feet = Vec3(entity.x, entity.boundingBox.minY, entity.z)
        motionAt(feet, previous, box)?.takeIf { it.lengthSqr() >= EPSILON * EPSILON }?.let { displace(entity, host, it) }

        val lift = box.lift(entity.boundingBox) ?: return
        if (lift <= entity.maxUpStep()) displace(entity, host, Vec3(0.0, lift, 0.0))
        else box.penetration(entity.boundingBox)?.let { displace(entity, host, it) }
    }

    private fun touchPushing(entity: Entity, collider: EntityCollider, previous: ColliderVolume?) {
        if (!EntityBodies.isMovedByOthers(entity)) return
        val box = collider.volume
        val motion = motionAt(entity.boundingBox.center, previous, box)
        if (motion == null || motion.lengthSqr() < EPSILON * EPSILON) {
            val overlap = box.penetration(entity.boundingBox) ?: return
            stillPush(box, entity, overlap)?.let { entity.push(it.x, 0.0, it.z) }
            return
        }
        val touch = box.firstTouch(previous, entity.boundingBox) ?: return
        passOn(entity, touch.overlap, motion, collider.spec.modes.force)
    }

    /**
     * Pushes [own]'s entity back from what stands in its still pushing colliders, the way mobs push each
     * other both ways. A collider swung by an animation does not push its own entity back.
     */
    private fun pushBack(own: PosedHost) {
        val host = own.entity
        own.now.forEach { collider ->
            if (!collider.spec.modes.pushes) return@forEach
            val box = collider.volume
            val motion = motionAt(box.center, own.previousOf(collider), box)
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
    private fun stillPush(box: ColliderVolume, entity: Entity, overlap: Vec3): Vec3? {
        val direction = horizontal(overlap) ?: horizontal(entity.position().subtract(box.center)) ?: return null
        val speed = (horizontalLength(overlap) * PUSH_STRENGTH).coerceAtMost(MAX_PUSH)
        return direction.scale(speed)
    }

    /**
     * Moves [entity] by [motion] through vanilla's collision, except with the colliders of [by], which are
     * what it is being moved out of. Vanilla's move decides whether the entity is on the ground from that
     * move alone, and a collider's nudge is no fall: the entity keeps the ground it had, or it could not
     * jump and would glide as on ice for the rest of the tick.
     */
    private fun displace(entity: Entity, by: Entity, motion: Vec3) {
        val grounded = entity.onGround()
        val outer = displacedBy.get()
        displacedBy.set(by)
        try {
            entity.move(MoverType.SHULKER_BOX, motion)
        } finally {
            displacedBy.set(outer)
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
    private fun motionAt(point: Vec3, previous: ColliderVolume?, box: ColliderVolume): Vec3? =
        previous?.carry(point, box)?.subtract(point)

    private fun horizontal(vector: Vec3): Vec3? {
        val length = horizontalLength(vector)
        return if (length < EPSILON) null else Vec3(vector.x / length, 0.0, vector.z / length)
    }

    private fun horizontalLength(vector: Vec3): Double = sqrt(vector.x * vector.x + vector.z * vector.z)
}
