package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.collider.CapsuleColliderShape
import ru.hollowhorizon.hollowengine.addons.physics.rotated
import ru.hollowhorizon.hollowengine.addons.physics.rotationFromYTo
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeSpec
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * One piece of a body: a collider's shape filling its box, the box placed in the bone's own space.
 */
class RagdollPart(
    val shape: ColliderShapeSpec,
    val center: Vec3f,
    val rotation: QuatF,
    val halfExtents: Vec3f,
) {
    /** How much room the piece's box takes, which is what pieces are weighed against each other by. */
    val volume: Float get() = halfExtents.x * halfExtents.y * halfExtents.z * 8f

    companion object {
        /** The piece a collider is: its shape in its box, wherever the collider puts that box on the bone. */
        fun of(collider: ColliderAttachmentSpec) = RagdollPart(
            shape = collider.shape,
            center = collider.offset,
            rotation = collider.orientation,
            halfExtents = Vec3f(collider.size.x / 2f, collider.size.y / 2f, collider.size.z / 2f).atLeast(RagdollShape.MIN_EXTENT),
        )
    }
}

/**
 * The shape of one simulated bone, in the bone's own space: the colliders its rig hangs on the bone, or, for a
 * model with no rig, a capsule along the bone.
 */
class RagdollShape(val parts: List<RagdollPart>) {
    init {
        require(parts.isNotEmpty()) { "A body needs at least one piece" }
    }

    /** The largest piece, which says which way the body runs. */
    val main: RagdollPart get() = parts.maxBy { it.volume }

    /** The middle of the body, its pieces weighed by size. Jolt works out the true center of mass when it builds it. */
    val center: Vec3f
        get() {
            val total = parts.sumOf { it.volume.toDouble() }.toFloat()
            if (total <= 0f) return main.center
            return parts.fold(Vec3f.ZERO) { sum, part -> sum + part.center * (part.volume / total) }
        }

    companion object {
        /**
         * The smallest a body may be.
         *
         * If zero size is passed, Jolt will throw a native error without providing any details in the logs
         */
        const val MIN_EXTENT = 0.01f

        /** Capsule extending from site of attachment to the bone along [axis]. */
        fun alongBone(axis: Vec3f, length: Float, radius: Float): RagdollShape {
            val safeRadius = radius.coerceAtLeast(MIN_EXTENT)
            val safeLength = length.coerceAtLeast(safeRadius * 2f + MIN_EXTENT)
            return RagdollShape(
                listOf(
                    RagdollPart(
                        shape = CapsuleColliderShape(),
                        center = axis * (safeLength * 0.5f),
                        rotation = rotationFromYTo(axis),
                        halfExtents = Vec3f(safeRadius, safeLength * 0.5f, safeRadius),
                    )
                )
            )
        }
    }
}

/** The way the body runs: the longest side of its largest piece, which is what it twists around. */
val RagdollShape.axis: Vec3f
    get() {
        val half = main.halfExtents
        val longest = when (maxOf(half.x, half.y, half.z)) {
            half.y -> Vec3f.Y_AXIS
            half.x -> Vec3f.X_AXIS
            else -> Vec3f.Z_AXIS
        }
        return longest.rotated(main.rotation)
    }

private fun Vec3f.atLeast(minimum: Float) = Vec3f(x.coerceAtLeast(minimum), y.coerceAtLeast(minimum), z.coerceAtLeast(minimum))
