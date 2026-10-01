package ru.hollowhorizon.hollowengine.client.models.internal.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** A box around the geometry a bone holds, in that bone's own space. */
class BoneBounds(val min: Vec3f, val max: Vec3f) {
    val center: Vec3f get() = Vec3f((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
    val size: Vec3f get() = Vec3f(max.x - min.x, max.y - min.y, max.z - min.z)
    val largestSide: Float get() = maxOf(max.x - min.x, max.y - min.y, max.z - min.z)
}

/**
 * Measures what geometry each bone of a model carries, in its bind pose: what rig generators start from
 * when they fit a box or a body to a bone.
 */
object BoneGeometry {
    /** Every bone that holds geometry, with the box around it, in skeleton order. */
    fun boundsPerBone(model: ModelAttachment): Map<RuntimeNode, BoneBounds> =
        boundsPerBone(model.nodes.flatMap { it.walk() }, model.model.boneBounds)

    /**
     * [geometry] is the local box of each node's mesh by node index. A mesh on a node that is no bone
     * counts towards the nearest bone above it.
     */
    fun boundsPerBone(nodes: List<RuntimeNode>, geometry: Map<Int, Pair<Vec3f, Vec3f>>): Map<RuntimeNode, BoneBounds> {
        if (nodes.isEmpty() || geometry.isEmpty()) return emptyMap()

        val bindGlobals = bindGlobalsOf(nodes)
        val bones = boneNodes(nodes)
        val boxes = LinkedHashMap<RuntimeNode, Accumulator>()
        val corner = MutableVec3f()
        val inBone = MutableVec3f()

        nodes.forEach { node ->
            val (min, max) = geometry[node.definition.index] ?: return@forEach
            val bone = if (node in bones) node else node.boneAncestor(bones) ?: return@forEach
            val toBone = intoBoneSpace(node, bone, bindGlobals) ?: return@forEach
            val box = boxes.getOrPut(bone) { Accumulator() }

            repeat(CORNERS) { index ->
                corner.set(
                    if (index and 1 == 0) min.x else max.x,
                    if (index and 2 == 0) min.y else max.y,
                    if (index and 4 == 0) min.z else max.z,
                )
                toBone.transform(corner, 1f, inBone)
                box.add(inBone)
            }
        }
        return boxes.mapValues { (_, box) -> box.bounds() }
    }

    /** The nodes that are bones: the joints of a skin, or for a model without one, every node that only groups others. */
    fun boneNodes(nodes: List<RuntimeNode>): Set<RuntimeNode> {
        val joints = nodes.mapNotNull { it.definition.skin }.flatMap { it.jointsIds }.toSet()
        if (joints.isNotEmpty()) return nodes.filterTo(LinkedHashSet()) { it.definition.index in joints }

        return nodes.filterTo(LinkedHashSet()) { it.definition.mesh == null && it.children.isNotEmpty() }
    }

    private fun intoBoneSpace(node: RuntimeNode, bone: RuntimeNode, bindGlobals: Map<Int, Mat4f>): Mat4f? {
        if (node === bone) return IDENTITY

        val nodeGlobal = bindGlobals[node.definition.index] ?: return null
        val boneGlobal = bindGlobals[bone.definition.index] ?: return null
        val inverse = MutableMat4f().set(boneGlobal)
        if (!inverse.invert()) return null
        return inverse.mul(nodeGlobal, MutableMat4f())
    }

    private fun bindGlobalsOf(nodes: List<RuntimeNode>): Map<Int, Mat4f> {
        val globals = HashMap<Int, Mat4f>(nodes.size)
        nodes.forEach { node ->
            val local = node.definition.baseTransform.matrixF
            val parent = (node.parent as? RuntimeNode)?.let { globals[it.definition.index] }
            globals[node.definition.index] = parent?.mul(local, MutableMat4f()) ?: local
        }
        return globals
    }

    private class Accumulator {
        private val min = MutableVec3f(Float.POSITIVE_INFINITY)
        private val max = MutableVec3f(Float.NEGATIVE_INFINITY)

        fun add(point: Vec3f) {
            min.x = minOf(min.x, point.x)
            min.y = minOf(min.y, point.y)
            min.z = minOf(min.z, point.z)
            max.x = maxOf(max.x, point.x)
            max.y = maxOf(max.y, point.y)
            max.z = maxOf(max.z, point.z)
        }

        fun bounds() = BoneBounds(Vec3f(min.x, min.y, min.z), Vec3f(max.x, max.y, max.z))
    }

    private val IDENTITY: Mat4f = MutableMat4f().setIdentity()
    private const val CORNERS = 8
}

/** The nearest bone above this node. */
fun RuntimeNode.boneAncestor(bones: Set<RuntimeNode>): RuntimeNode? {
    var current = parent as? RuntimeNode
    while (current != null) {
        if (current in bones) return current
        current = current.parent as? RuntimeNode
    }
    return null
}
