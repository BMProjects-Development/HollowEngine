package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.Supplier
import kotlin.math.abs

/**
 * Solid colliders in vanilla's movement: a moving entity stops at them the way it stops at blocks, steps
 * up onto low ones and walks up tilted ones a step at a time.
 */
internal object SolidColliders {
    /** Steps tried on top of vanilla's when walking up a tilted collider, as fine as a model's pixel. */
    private const val SLOPE_STEP = 1f / 16f

    /** The box around a player vanilla's floating check looks for blocks in. */
    private const val FLOATING_MARGIN = 0.0625
    private const val FLOATING_DEPTH = 0.55

    private val active = ThreadLocal<List<ColliderBox>?>()

    /** Runs [move], the move of [entity] by [movement], with the solid colliders around it in effect. */
    fun during(entity: Entity, movement: Vec3, move: Supplier<Vec3>): Vec3 {
        if (entity.noPhysics || !ColliderContacts.isSimulatedHere(entity)) return move.get()
        val boxes = around(entity, movement)
        if (boxes.isEmpty()) return move.get()

        val previous = active.get()
        active.set(boxes)
        try {
            return move.get()
        } finally {
            active.set(previous)
        }
    }

    /** Vanilla's collision of [box] moving by [movement] with [shapes], with the colliders in effect as well. */
    fun collide(movement: Vec3, box: AABB, shapes: List<VoxelShape>, vanilla: Supplier<Vec3>): Vec3 {
        val boxes = active.get() ?: return vanilla.get()
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
     * The heights vanilla tries to step up by, and more where solid colliders are: the top of each, and
     * fine steps, since the lowest step that gets the entity further is the one taken and a tilted
     * collider has no single top.
     */
    fun stepHeights(box: AABB, limit: Float, vanilla: FloatArray): FloatArray {
        val boxes = active.get() ?: return vanilla
        val reach = box.inflate(1.0, 0.0, 1.0).expandTowards(0.0, limit.toDouble(), 0.0)
        val near = boxes.filter { it.bounds.intersects(reach) }
        if (near.isEmpty()) return vanilla

        val heights = vanilla.toMutableSet()
        near.forEach { collider ->
            val top = (collider.bounds.maxY - box.minY).toFloat()
            if (top > 0f && top <= limit) heights += top
        }
        var step = SLOPE_STEP
        while (step < limit) {
            heights += step
            step += SLOPE_STEP
        }
        return heights.sorted().toFloatArray()
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
            EntityColliders.physical(host).firstOrNull().orEmpty()
                .filter { it.spec.modes.solid && it.box.bounds.intersects(reach) }
                .map { it.box }
        }
    }
}
