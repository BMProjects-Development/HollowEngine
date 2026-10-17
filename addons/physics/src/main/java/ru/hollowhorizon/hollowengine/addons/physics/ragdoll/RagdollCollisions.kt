package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.rotated
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

/**
 * Determines which bones can interact with each other.
 */
internal object RagdollCollisions {
    fun excludedPairs(bones: List<RagdollBone>): List<Pair<Int, Int>> {
        val boxes = bones.map(::boxesOf)
        val pairs = ArrayList<Pair<Int, Int>>()

        bones.indices.forEach { first ->
            (first + 1 until bones.size).forEach { second ->
                if (excluded(bones, boxes, first, second)) pairs += first to second
            }
        }
        return pairs
    }

    private fun excluded(bones: List<RagdollBone>, boxes: List<List<Box>>, first: Int, second: Int): Boolean {
        val one = bones[first]
        val other = bones[second]
        if (one.parent == second || other.parent == first) return true

        val settings = listOf(one.collision, other.collision)
        if (settings.any { !it.withRig }) return true
        if (other.name in one.collision.ignores || one.name in other.collision.ignores) return true

        return settings.none { it.pushesOut } && boxes[first].any { one -> boxes[second].any { overlap(one, it) } }
    }

    fun softBodies(bones: List<RagdollBone>): Map<Int, Float> =
        bones.withIndex().filterNot { (_, bone) -> bone.collision.isSolid }
            .associate { (index, bone) -> index to bone.collision.push.coerceIn(0f, 1f) }

    /** The box of every piece of [bone]'s body in the bind pose; each holds whatever shape fills it. */
    fun boxesOf(bone: RagdollBone): List<Box> = bone.shape.parts.map { part ->
        Box(
            centre = bone.bindPosition + part.center.rotated(bone.bindRotation),
            rotation = QuatF(MutableQuatF(bone.bindRotation).mul(part.rotation).norm()),
            half = part.halfExtents,
        )
    }

    fun overlap(first: Box, second: Box): Boolean {
        val offset = second.centre - first.centre
        val axes = first.axes + second.axes

        val crossAxes = first.axes.flatMap { a -> second.axes.map { b -> a.cross(b, MutableVec3f()) } }
            .filter { it.length() > EPSILON }.map { Vec3f(it.normed()) }

        return (axes + crossAxes).none { axis -> separatedAlong(axis, offset, first, second) }
    }

    private fun separatedAlong(axis: Vec3f, offset: Vec3f, first: Box, second: Box): Boolean {
        val distance = abs(offset dot axis)
        return distance - MARGIN > first.reachAlong(axis) + second.reachAlong(axis)
    }

    class Box(val centre: Vec3f, rotation: QuatF, val half: Vec3f) {
        val axes: List<Vec3f> = listOf(Vec3f.X_AXIS, Vec3f.Y_AXIS, Vec3f.Z_AXIS).map { it.rotated(rotation) }

        fun reachAlong(axis: Vec3f): Float =
            abs(axes[0] dot axis) * half.x + abs(axes[1] dot axis) * half.y + abs(axes[2] dot axis) * half.z
    }

    private const val MARGIN = 0.004f
    private const val EPSILON = 1.0e-5f
}
