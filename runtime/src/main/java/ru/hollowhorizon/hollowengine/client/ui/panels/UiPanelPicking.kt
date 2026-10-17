package ru.hollowhorizon.hollowengine.client.ui.panels

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.sqrt

internal object UiPanelPicking {
    /** A front-facing hit, measured in world units and panel coordinates from 0 to 1. */
    fun hitOf(placement: Matrix4f, x: Double, y: Double, z: Double, look: Vector3f): Pair<Double, Pair<Float, Float>>? {
        val inverse = Matrix4f(placement).invert()
        val origin = inverse.transformPosition(Vector3f(x.toFloat(), y.toFloat(), z.toFloat()))
        val direction = inverse.transformDirection(Vector3f(look))
        if (origin.z <= 0f || direction.z >= 0f) return null
        val t = -origin.z / direction.z
        val u = origin.x + direction.x * t
        val v = origin.y + direction.y * t
        if (u < -0.5f || u > 0.5f || v < -0.5f || v > 0.5f) return null
        val world = placement.transformPosition(Vector3f(u, v, 0f))
        val distance = sqrt(
            (world.x - x) * (world.x - x) + (world.y - y) * (world.y - y) + (world.z - z) * (world.z - z)
        )
        return distance to ((u + 0.5f) to (0.5f - v))
    }

    fun isOccluded(distance: Double, blockDistance: Double?, entityDistance: Double?): Boolean =
        (blockDistance != null && blockDistance < distance - BLOCK_MARGIN) ||
            (entityDistance != null && entityDistance < distance)

    /** Panels may lie on a block face; entities get no such tolerance. */
    private const val BLOCK_MARGIN = 0.05
}
