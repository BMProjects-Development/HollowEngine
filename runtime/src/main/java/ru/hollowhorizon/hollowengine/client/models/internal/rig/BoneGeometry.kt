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
    val thinnestSide: Float get() = minOf(max.x - min.x, max.y - min.y, max.z - min.z)
    val center: Vec3f get() = Vec3f((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
    val size: Vec3f get() = Vec3f(max.x - min.x, max.y - min.y, max.z - min.z)
    val largestSide: Float get() = maxOf(max.x - min.x, max.y - min.y, max.z - min.z)

    companion object {
        /** The box around [points]. */
        fun around(points: List<Vec3f>) = BoneBounds(
            Vec3f(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }),
            Vec3f(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }),
        )
    }
}

/**
 * Measures what geometry each bone of a model carries, in its bind pose: what rig generators start from
 * when they fit a box or a body to a bone.
 */
object BoneGeometry {
    /** Every bone that holds geometry, with the surrounding box, in skeleton order. */
    fun boundsPerBone(model: ModelAttachment): Map<RuntimeNode, BoneBounds> =
        boundsPerBone(model.nodes.flatMap { it.walk() }, model.model.boneBounds)

    /**
     * [geometry] is the local box of each node's mesh by node index. A mesh on a node that is no bone
     * counts towards the nearest bone above it.
     */
    fun boundsPerBone(nodes: List<RuntimeNode>, geometry: Map<Int, Pair<Vec3f, Vec3f>>): Map<RuntimeNode, BoneBounds> =
        cornersPerBone(nodes, geometry).mapValues { (_, corners) ->
            BoneBounds.around(corners)
        }

    /** The corners of every piece of geometry each bone holds, in the bone's own space: what a hull is fitted around. */
    fun cornersPerBone(model: ModelAttachment): Map<RuntimeNode, List<Vec3f>> =
        cornersPerBone(model.nodes.flatMap { it.walk() }, model.model.boneBounds)

    fun cornersPerBone(nodes: List<RuntimeNode>, geometry: Map<Int, Pair<Vec3f, Vec3f>>): Map<RuntimeNode, List<Vec3f>> {
        if (nodes.isEmpty() || geometry.isEmpty()) return emptyMap()

        val bindGlobals = bindGlobalsOf(nodes)
        val bones = boneNodes(nodes)
        val corners = LinkedHashMap<RuntimeNode, MutableList<Vec3f>>()
        val corner = MutableVec3f()

        nodes.forEach { node ->
            val (min, max) = geometry[node.definition.index] ?: return@forEach
            val bone = if (node in bones) node else node.boneAncestor(bones) ?: return@forEach
            val toBone = intoBoneSpace(node, bone, bindGlobals) ?: return@forEach
            val ofBone = corners.getOrPut(bone) { ArrayList() }

            repeat(CORNERS) { index ->
                corner.set(
                    if (index and 1 == 0) min.x else max.x,
                    if (index and 2 == 0) min.y else max.y,
                    if (index and 4 == 0) min.z else max.z,
                )
                ofBone += Vec3f(toBone.transform(corner, 1f, MutableVec3f()))
            }
        }
        return corners
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

    /** Where every node stands in model space in the bind pose, by node index. */
    fun bindGlobalsOf(nodes: List<RuntimeNode>): Map<Int, Mat4f> {
        val globals = HashMap<Int, Mat4f>(nodes.size)
        nodes.forEach { node ->
            val local = node.definition.baseTransform.matrixF
            val parent = (node.parent as? RuntimeNode)?.let { globals[it.definition.index] }
            globals[node.definition.index] = parent?.mul(local, MutableMat4f()) ?: local
        }
        return globals
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
