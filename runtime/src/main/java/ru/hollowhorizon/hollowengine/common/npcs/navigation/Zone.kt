package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A part of the world an NPC keeps out of on its way: a block counts as in it when its middle is. Zones are compared
 * by value, so one written down and read back is the same zone.
 */
@Serializable
sealed class Zone {
    /** What walking through costs, or null when the NPC never enters. */
    abstract val cost: Float?

    abstract fun contains(x: Double, y: Double, z: Double): Boolean

    fun contains(point: Vec3): Boolean = contains(point.x, point.y, point.z)

    /** Everything between two corners. */
    @Serializable
    @SerialName("box")
    data class Box(@Contextual val from: Vec3, @Contextual val to: Vec3, override val cost: Float? = null) : Zone() {
        @Transient
        val bounds = AABB(from, to)

        override fun contains(x: Double, y: Double, z: Double): Boolean = bounds.contains(x, y, z)
    }

    /** Everything within [radius] blocks of [center]. */
    @Serializable
    @SerialName("sphere")
    data class Sphere(@Contextual val center: Vec3, val radius: Double, override val cost: Float? = null) : Zone() {
        override fun contains(x: Double, y: Double, z: Double): Boolean =
            center.distanceToSqr(x, y, z) <= radius * radius
    }

    companion object {
        /** The box from [from] to [to]; [cost] null never enters it. */
        fun box(from: Vec3, to: Vec3, cost: Float? = null): Zone = Box(from, to, cost)

        /** Everything within [radius] blocks of [center]; [cost] null never enters it. */
        fun sphere(center: Vec3, radius: Double, cost: Float? = null): Zone = Sphere(center, radius, cost)
    }
}
