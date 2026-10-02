package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Solid colliders in vanilla's movement: a moving entity stops at them the way it stops at blocks and
 * steps up onto them, tilted ones included, as high as it steps up onto blocks.
 */
internal object SolidColliders {
    /** How far above a collider a step leaves the entity, so the step itself does not end in a touch. */
    private const val STEP_CLEARANCE = 1.0e-5

    /** The box around a player vanilla's floating check looks for blocks in. */
    private const val FLOATING_MARGIN = 0.0625
    private const val FLOATING_DEPTH = 0.55

    private const val MIN_MOVE = 1.0e-7

    /** How far above its feet a block still counts as the floor under an entity in the air. */
    private const val FLOOR_TOLERANCE = 1.0e-3

    private class Move(val boxes: List<ColliderBox>, val movement: Vec3)

    private val active = ThreadLocal<Move?>()

    /** Runs [move], the move of [entity] by [movement], with the solid colliders around it in effect. */
    fun during(entity: Entity, movement: Vec3, move: Supplier<Vec3>): Vec3 {
        if (entity.noPhysics || !ColliderContacts.isSimulatedHere(entity)) return move.get()
        val boxes = around(entity, movement)
        if (boxes.isEmpty()) return move.get()

        val previous = active.get()
        active.set(Move(boxes, movement))
        try {
            return move.get()
        } finally {
            active.set(previous)
        }
    }

    /** Vanilla's collision of [box] moving by [movement] with [shapes], with the colliders in effect as well. */
    fun collide(movement: Vec3, box: AABB, shapes: List<VoxelShape>, vanilla: Supplier<Vec3>): Vec3 {
        val boxes = active.get()?.boxes ?: return vanilla.get()
        var moved = box
        var dx = movement.x
        var dy = movement.y
        var dz = movement.z

        if (dy != 0.0) {
            dy = clamp(Direction.Axis.Y, moved, shapes, boxes, dy)
            if (dy != 0.0) moved = moved.move(0.0, dy, 0.0)
        }
        val zFirst = abs(dx) < abs(dz)
        if (zFirst && dz != 0.0) {
            dz = clamp(Direction.Axis.Z, moved, shapes, boxes, dz)
            if (dz != 0.0) moved = moved.move(0.0, 0.0, dz)
        }
        if (dx != 0.0) {
            dx = clamp(Direction.Axis.X, moved, shapes, boxes, dx)
            if (!zFirst && dx != 0.0) moved = moved.move(dx, 0.0, 0.0)
        }
        if (!zFirst && dz != 0.0) dz = clamp(Direction.Axis.Z, moved, shapes, boxes, dz)
        return Vec3(dx, dy, dz)
    }

    /**
     * The heights vanilla tries to step up by, and the ones that clear the solid colliders in the way of
     * the step: vanilla takes the lowest that gets the entity further, and a tilted collider has no top of
     * its own, only the height that clears it where the entity is going.
     */
    fun stepHeights(box: AABB, limit: Float, vanilla: FloatArray): FloatArray {
        val move = active.get() ?: return vanilla
        val target = box.move(move.movement.x, 0.0, move.movement.z)
        val path = box.expandTowards(move.movement.x, 0.0, move.movement.z)

        val heights = vanilla.toMutableSet()
        move.boxes.forEach { collider ->
            for (candidate in arrayOf(target, path)) {
                val height = (collider.lift(candidate) ?: continue) + STEP_CLEARANCE
                if (height <= limit) heights += height.toFloat()
            }
        }
        return if (heights.size == vanilla.size) vanilla else heights.sorted().toFloatArray()
    }

    /**
     * [moved], what vanilla let [entity] move by with its box, cut short where its own solid colliders would
     * go into blocks: the entity is as big as they are. They meet walls but not the floor the box walks on,
     * so a collider that reaches down to the feet does not drag along the ground.
     */
    fun keepOutOfBlocks(entity: Entity, moved: Vec3): Vec3 {
        if (moved.lengthSqr() < MIN_MOVE * MIN_MOVE || !EntityColliders.hasTargets(entity, ColliderModes::solid)) return moved
        var own = solidBoxes(entity)
        if (own.isEmpty()) return moved

        val reach = own.map { it.bounds.expandTowards(moved) }.reduce(AABB::minmax)
        val blocks = entity.level().getBlockCollisions(entity, reach).flatMap { it.toAabbs() }
        if (blocks.isEmpty()) return moved

        var dx = moved.x
        var dy = moved.y
        var dz = moved.z
        dy = clampOwn(own, blocks, Direction.Axis.Y, dy)
        own = own.map { it.move(0.0, dy, 0.0) }

        val floor = floorOf(entity, min(dy, 0.0))
        val walls = blocks.filter { it.maxY > floor }
        if (abs(dx) < abs(dz)) {
            dz = clampOwn(own, walls, Direction.Axis.Z, dz)
            own = own.map { it.move(0.0, 0.0, dz) }
            dx = clampOwn(own, walls, Direction.Axis.X, dx)
        } else {
            dx = clampOwn(own, walls, Direction.Axis.X, dx)
            own = own.map { it.move(dx, 0.0, 0.0) }
            dz = clampOwn(own, walls, Direction.Axis.Z, dz)
        }
        return Vec3(dx, dy, dz)
    }

    /**
     * How far [host] has to move sideways for its own solid colliders to leave the walls they went into over
     * the last tick, as when it turned or an animation swung them; null when they went into none. A wall a
     * collider was in already is left alone: one put there on purpose keeps the entity where it was put.
     */
    fun outOfWalls(host: Entity, colliders: List<Pair<ColliderBox, ColliderBox?>>): Vec3? {
        if (colliders.isEmpty()) return null
        val reach = colliders.map { it.first.bounds }.reduce(AABB::minmax)
        val floor = floorOf(host, 0.0)
        val walls = host.level().getBlockCollisions(host, reach).flatMap { it.toAabbs() }.filter { it.maxY > floor }
        if (walls.isEmpty()) return null

        var minX = 0.0
        var maxX = 0.0
        var minZ = 0.0
        var maxZ = 0.0
        for ((box, previous) in colliders) for (wall in walls) {
            val overlap = box.penetration(wall) ?: continue
            if (previous?.penetration(wall) != null) continue
            minX = min(minX, -overlap.x)
            maxX = max(maxX, -overlap.x)
            minZ = min(minZ, -overlap.z)
            maxZ = max(maxZ, -overlap.z)
        }
        val out = Vec3(minX + maxX, 0.0, minZ + maxZ)
        return out.takeIf { it.lengthSqr() >= MIN_MOVE * MIN_MOVE }
    }

    /**
     * Whether a solid collider of an entity other than [entity] reaches into [box]: vanilla asks this of
     * blocks to keep a sneaking player from walking off an edge.
     */
    fun overlaps(entity: Entity, box: AABB): Boolean = EntityColliders.physicalHosts(entity.level()).any { host ->
        host !== entity && host.rootVehicle !== entity.rootVehicle &&
            solidBoxes(host).any { it.bounds.intersects(box) && it.penetration(box) != null }
    }

    /**
     * The height below which blocks are the floor under [entity], lowered by [drop], not walls its own
     * colliders meet: on the ground, what its box steps onto; in the air, only what is under its feet, so an
     * entity knocked up still meets the walls it flies into.
     */
    private fun floorOf(entity: Entity, drop: Double): Double {
        val step = if (entity.onGround()) entity.maxUpStep().toDouble() else FLOOR_TOLERANCE
        return entity.boundingBox.minY + drop + step
    }

    /** How far of [distance] the colliders can move along [axis] before one of them runs into one of [blocks]. */
    private fun clampOwn(own: List<ColliderBox>, blocks: List<AABB>, axis: Direction.Axis, distance: Double): Double {
        var allowed = distance
        for (collider in own) for (block in blocks) {
            if (allowed == 0.0) return 0.0
            // The collider moving into the block is the block moving the other way into the collider.
            allowed = -collider.sweep(block, axis, -allowed)
        }
        return allowed
    }

    /**
     * Whether the solid colliders of the entities around [shape] reach into it: a block cannot be placed into
     * them, the same as into an entity's box.
     */
    fun obstructs(level: Level, shape: VoxelShape): Boolean {
        if (shape.isEmpty) return false
        val parts = shape.toAabbs()
        val bounds = shape.bounds()
        return EntityColliders.physicalHosts(level).any { host ->
            if (host.isRemoved) return@any false
            solidBoxes(host).any { collider ->
                collider.bounds.intersects(bounds) && parts.any { collider.penetration(it) != null }
            }
        }
    }

    /**
     * Whether [entity] has a solid collider around or right under its feet, in the box the server looks
     * for blocks in before it calls a player floating: standing on a collider over air is not flying.
     */
    fun supports(entity: Entity): Boolean {
        val feet = entity.boundingBox.inflate(FLOATING_MARGIN).expandTowards(0.0, -FLOATING_DEPTH, 0.0)
        return around(entity, Vec3.ZERO).any { it.penetration(feet) != null }
    }

    private fun clamp(axis: Direction.Axis, box: AABB, shapes: List<VoxelShape>, boxes: List<ColliderBox>, distance: Double): Double {
        var allowed = if (shapes.isEmpty()) distance else Shapes.collide(axis, box, shapes, distance)
        for (collider in boxes) {
            if (allowed == 0.0) break
            allowed = collider.sweep(box, axis, allowed)
        }
        return allowed
    }

    private fun around(entity: Entity, movement: Vec3): List<ColliderBox> {
        val reach = entity.boundingBox.expandTowards(movement).inflate(entity.maxUpStep().toDouble() + 0.5)
        return EntityColliders.physicalHosts(entity.level()).flatMap { host ->
            if (host === entity || host.rootVehicle === entity.rootVehicle) return@flatMap emptyList()
            solidBoxes(host).filter { it.bounds.intersects(reach) }
        }
    }

    /** Where the solid colliders of [host] are now, as this side sees them. */
    fun solidBoxes(host: Entity): List<ColliderBox> =
        EntityColliders.physical(host).firstOrNull().orEmpty().filter { it.spec.modes.solid }.map { it.box }
}
