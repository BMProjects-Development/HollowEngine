package ru.hollowhorizon.hollowengine.addons.physics.collider

import com.github.stephengold.joltjni.ConvexHullShapeSettings
import com.github.stephengold.joltjni.Vec3
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.client.models.internal.rig.BoneBounds
import ru.hollowhorizon.hollowengine.client.models.internal.rig.ColliderFitter
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeSpec
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Shapes a collider as the hull of a bone's geometry when that leaves much of the box empty.
 */
internal object HullFitter : ColliderFitter {
    const val ID = "hollowengine:physics/hulls"

    /** How much of its box the hull may fill and still be worth being a hull. */
    private const val MAX_FILL = 0.85f

    override fun fit(corners: List<Vec3f>, box: BoneBounds): ColliderShapeSpec? {
        if (!JoltNatives.isAvailable || corners.size < MIN_POINTS) return null
        val size = box.size
        val boxVolume = size.x * size.y * size.z
        if (boxVolume <= 0f) return null
        val center = box.center
        val result = ConvexHullShapeSettings(corners.map { Vec3(it.x - center.x, it.y - center.y, it.z - center.z) }, 0f).create()
        if (result.hasError()) return null
        val hull = result.get()
        // Jolt weighs a shape at its default density, so its mass says how much room it takes.
        val volume = hull.massProperties.mass / DEFAULT_DENSITY
        hull.close()
        if (volume >= boxVolume * MAX_FILL) return null

        val points = corners.map { corner ->
            Vec3f((corner.x - center.x) * 2f / size.x, (corner.y - center.y) * 2f / size.y, (corner.z - center.z) * 2f / size.z)
        }.distinct()
        return HullColliderShape(points)
    }

    private const val MIN_POINTS = 4
    private const val DEFAULT_DENSITY = 1000f
}
