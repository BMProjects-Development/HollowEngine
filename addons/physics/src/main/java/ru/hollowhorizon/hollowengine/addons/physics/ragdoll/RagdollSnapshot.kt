package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.matrixInto
import ru.hollowhorizon.hollowengine.addons.physics.rotatedInverse
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Where every body of a ragdoll is in the world at one moment.
 */
class RagdollSnapshot(
    val originX: Double,
    val originY: Double,
    val originZ: Double,
    val nodes: IntArray,
    val values: FloatArray,
) {
    /** Writes each body into [store] in the space of the model [placement] puts in the world, keyed by node index. */
    fun readInto(placement: ModelPlacement, store: MutableMap<Int, MutableMat4f>) {
        val inverse = MutableQuatF(placement.rotation).invert()
        nodes.forEachIndexed { body, node ->
            val at = body * STRIDE
            val world = Vec3f(
                (originX + values[at] - placement.origin.x).toFloat(),
                (originY + values[at + 1] - placement.origin.y).toFloat(),
                (originZ + values[at + 2] - placement.origin.z).toFloat(),
            )
            val rotation = MutableQuatF(values[at + 3], values[at + 4], values[at + 5], values[at + 6])
            val modelRotation = MutableQuatF(inverse).mul(rotation).norm()
            matrixInto(store.getOrPut(node) { MutableMat4f() }, modelRotation, world.rotatedInverse(placement.rotation))
        }
    }

    /**
     * The pose [t] of the way from this snapshot to [next]: bodies are matched by node, moved in a straight line
     * and turned the shorter way round. A body [next] does not have stays where it is here.
     */
    fun lerp(next: RagdollSnapshot, t: Float): RagdollSnapshot {
        val values = values.copyOf()
        val shift = floatArrayOf((next.originX - originX).toFloat(), (next.originY - originY).toFloat(), (next.originZ - originZ).toFloat())
        nodes.forEachIndexed { body, node ->
            val other = next.nodes.indexOf(node).takeIf { it >= 0 } ?: return@forEachIndexed
            val at = body * STRIDE
            val to = other * STRIDE
            for (axis in 0 until 3) values[at + axis] += (next.values[to + axis] + shift[axis] - values[at + axis]) * t
            val from = MutableQuatF(values[at + 3], values[at + 4], values[at + 5], values[at + 6])
            val target = MutableQuatF(next.values[to + 3], next.values[to + 4], next.values[to + 5], next.values[to + 6])
            val blended = from.mix(target, t)
            values[at + 3] = blended.x
            values[at + 4] = blended.y
            values[at + 5] = blended.z
            values[at + 6] = blended.w
        }
        return RagdollSnapshot(originX, originY, originZ, nodes, values)
    }

    companion object {
        const val STRIDE = 7
    }
}
