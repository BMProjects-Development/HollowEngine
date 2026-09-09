package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * What is the volume occupied by the geometry of each bone in the space associated with that bone?
 */
object BoneGeometry {
    private const val WEIGHT_THRESHOLD = 0.3f

    fun of(model: Model): Map<Int, Pair<Vec3f, Vec3f>> {
        val bounds = HashMap<Int, Bounds>()

        model.walkNodes().forEach { node ->
            val mesh = node.mesh ?: return@forEach
            val skin = node.skin

            mesh.primitives.forEach { primitive ->
                if (skin != null && primitive.hasSkinning) skinned(primitive, skin, bounds)
                else rigid(node, primitive, bounds)
            }
        }

        return bounds.mapValues { (_, box) -> box.min to box.max }
    }

    private fun rigid(node: NodeDefinition, primitive: Primitive, bounds: MutableMap<Int, Bounds>) {
        val (min, max) = primitive.localBounds ?: return
        bounds.getOrPut(node.index, ::Bounds).add(min).add(max)
    }

    private fun skinned(primitive: Primitive, skin: Skin, bounds: MutableMap<Int, Bounds>) {
        val positions = primitive.positions ?: return
        val joints = primitive.joints ?: return
        val weights = primitive.jointWeights ?: return
        val inBone = MutableVec3f()

        positions.indices.forEach { vertex ->
            val influences = joints.getOrNull(vertex) ?: return@forEach
            val strengths = weights.getOrNull(vertex) ?: return@forEach
            val position = positions[vertex]

            add(influences.x, strengths.x, position, skin, bounds, inBone)
            add(influences.y, strengths.y, position, skin, bounds, inBone)
            add(influences.z, strengths.z, position, skin, bounds, inBone)
            add(influences.w, strengths.w, position, skin, bounds, inBone)
        }
    }

    private fun add(
        joint: Int,
        weight: Float,
        position: Vec3f,
        skin: Skin,
        bounds: MutableMap<Int, Bounds>,
        scratch: MutableVec3f,
    ) {
        if (weight < WEIGHT_THRESHOLD) return

        val node = skin.jointsIds.getOrNull(joint) ?: return
        skin.inverseBindMatrices[joint].transform(position, 1f, scratch)
        bounds.getOrPut(node, ::Bounds).add(scratch)
    }

    private class Bounds {
        private val minimum = MutableVec3f(Float.POSITIVE_INFINITY)
        private val maximum = MutableVec3f(Float.NEGATIVE_INFINITY)

        val min: Vec3f get() = Vec3f(minimum)
        val max: Vec3f get() = Vec3f(maximum)

        fun add(point: Vec3f) = apply {
            minimum.x = minOf(minimum.x, point.x)
            minimum.y = minOf(minimum.y, point.y)
            minimum.z = minOf(minimum.z, point.z)
            maximum.x = maxOf(maximum.x, point.x)
            maximum.y = maxOf(maximum.y, point.y)
            maximum.z = maxOf(maximum.z, point.z)
        }
    }
}
