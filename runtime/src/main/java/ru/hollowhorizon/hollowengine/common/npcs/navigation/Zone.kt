package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A part of the world an NPC keeps out of on its way: a block counts as in it when its middle is.
 */
sealed class Zone(val cost: Float?) {
    abstract fun contains(x: Double, y: Double, z: Double): Boolean

    fun contains(point: Vec3): Boolean = contains(point.x, point.y, point.z)

    /** Everything between two corners. */
    class Box(val bounds: AABB, cost: Float? = null) : Zone(cost) {
        override fun contains(x: Double, y: Double, z: Double): Boolean = bounds.contains(x, y, z)
    }

    /** Everything within [radius] blocks of [center]. */
    class Sphere(val center: Vec3, val radius: Double, cost: Float? = null) : Zone(cost) {
        override fun contains(x: Double, y: Double, z: Double): Boolean =
            center.distanceToSqr(x, y, z) <= radius * radius
    }

    companion object {
        /** The box from [from] to [to]; [cost] null never enters it. */
        fun box(from: Vec3, to: Vec3, cost: Float? = null): Zone = Box(AABB(from, to), cost)

        /** Everything within [radius] blocks of [center]; [cost] null never enters it. */
        fun sphere(center: Vec3, radius: Double, cost: Float? = null): Zone = Sphere(center, radius, cost)
    }
}
