package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.rig.RigVector
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyShape
import ru.hollowhorizon.hollowengine.addons.physics.rotated
import ru.hollowhorizon.hollowengine.addons.physics.rotationFromYTo
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg

/**
 * The shape of one simulated bone, in the bone's own space.
 */
sealed interface RagdollShape {
    val center: Vec3f
    val rotation: QuatF

    class Capsule(
        val radius: Float,
        val length: Float,
        override val center: Vec3f,
        override val rotation: QuatF,
    ) : RagdollShape

    class Box(
        val halfExtents: Vec3f,
        override val center: Vec3f,
        override val rotation: QuatF,
    ) : RagdollShape

    class Sphere(
        val radius: Float,
        override val center: Vec3f,
        override val rotation: QuatF,
    ) : RagdollShape

    companion object {
        /**
         * The smallest a body may be.
         *
         * If zero size is passed, Jolt will throw a native error without providing any details in the logs
         */
        const val MIN_EXTENT = 0.01f

        /** Capsule extending from site of attachment to the bone along [axis]. */
        fun alongBone(axis: Vec3f, length: Float, radius: Float): Capsule {
            val safeRadius = radius.coerceAtLeast(MIN_EXTENT)
            val safeLength = length.coerceAtLeast(safeRadius * 2f + MIN_EXTENT)
            return Capsule(
                radius = safeRadius,
                length = safeLength,
                center = axis * (safeLength * 0.5f),
                rotation = rotationFromYTo(axis),
            )
        }

        fun of(shape: RigidBodyShape): RagdollShape {
            val centre = shape.offset.toVec3f()
            val rotation = shape.rotation.toRotation()
            return when (shape) {
                is RigidBodyShape.Capsule -> {
                    val radius = shape.radius.coerceAtLeast(MIN_EXTENT)
                    Capsule(radius, shape.length.coerceAtLeast(radius * 2f + MIN_EXTENT), centre, rotation)
                }

                is RigidBodyShape.Box -> Box(shape.halfExtents.toVec3f().atLeast(MIN_EXTENT), centre, rotation)
                is RigidBodyShape.Sphere -> Sphere(shape.radius.coerceAtLeast(MIN_EXTENT), centre, rotation)
            }
        }

        private fun Vec3f.atLeast(minimum: Float) = Vec3f(
            x.coerceAtLeast(minimum),
            y.coerceAtLeast(minimum),
            z.coerceAtLeast(minimum),
        )
    }
}

val RagdollShape.axis: Vec3f get() = Vec3f.Y_AXIS.rotated(rotation)

internal fun RigVector.toRotation(): QuatF =
    QuatF(z.deg, Vec3f.Z_AXIS) * QuatF(y.deg, Vec3f.Y_AXIS) * QuatF(x.deg, Vec3f.X_AXIS)
