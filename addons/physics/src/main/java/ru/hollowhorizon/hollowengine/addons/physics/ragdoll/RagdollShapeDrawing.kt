package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.rotated
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Draws one body where it currently is.
 */
fun DebugLines.Batch.shape(shape: RagdollShape, origin: Vec3f, orientation: QuatF, color: Int) {
    val centre = origin + shape.center.rotated(orientation)
    val rotation = MutableQuatF(orientation).mul(shape.rotation).norm()

    when (shape) {
        is RagdollShape.Capsule -> {
            val along = Vec3f.Y_AXIS.rotated(rotation)
            val half = (shape.length * 0.5f - shape.radius).coerceAtLeast(0f)
            capsule(centre - along * half, centre + along * half, shape.radius, color)
        }

        is RagdollShape.Box -> box(
            centre,
            Vec3f.X_AXIS.rotated(rotation) * shape.halfExtents.x,
            Vec3f.Y_AXIS.rotated(rotation) * shape.halfExtents.y,
            Vec3f.Z_AXIS.rotated(rotation) * shape.halfExtents.z,
            color,
        )

        is RagdollShape.Sphere -> sphere(centre, shape.radius, color)
    }
}
