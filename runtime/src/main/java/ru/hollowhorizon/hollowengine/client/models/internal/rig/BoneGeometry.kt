package ru.hollowhorizon.hollowengine.client.models.internal.rig

import ru.hollowhorizon.hollowengine.common.models.ModelPoseNode
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
    /** The corners of every piece of geometry each bone holds, in the bone's own space. */
    fun cornersPerBone(nodes: Collection<ModelPoseNode>, geometry: Map<Int, Pair<Vec3f, Vec3f>>): Map<ModelPoseNode, List<Vec3f>> {
        if (nodes.isEmpty() || geometry.isEmpty()) return emptyMap()

        val bindGlobals = bindGlobalsOf(nodes)
        val bones = boneNodes(nodes)
        val corners = LinkedHashMap<ModelPoseNode, MutableList<Vec3f>>()
        val corner = MutableVec3f()

        nodes.forEach { node ->
            val (min, max) = geometry[node.poseIndex] ?: return@forEach
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
    fun boneNodes(nodes: Collection<ModelPoseNode>): Set<ModelPoseNode> {
        val joints = nodes.flatMap { it.source.skinJoints }.toSet()
        if (joints.isNotEmpty()) return nodes.filterTo(LinkedHashSet()) { it.poseIndex in joints }

        val parents = nodes.mapNotNullTo(HashSet()) { it.poseParent }
        return nodes.filterTo(LinkedHashSet()) { !it.source.hasMesh && it in parents }
    }

    private fun intoBoneSpace(node: ModelPoseNode, bone: ModelPoseNode, bindGlobals: Map<Int, Mat4f>): Mat4f? {
        if (node === bone) return IDENTITY

        val nodeGlobal = bindGlobals[node.poseIndex] ?: return null
        val boneGlobal = bindGlobals[bone.poseIndex] ?: return null
        val inverse = MutableMat4f().set(boneGlobal)
        if (!inverse.invert()) return null
        return inverse.mul(nodeGlobal, MutableMat4f())
    }

    /** Where every node stands in model space in the bind pose, by node index. */
    fun bindGlobalsOf(nodes: Collection<ModelPoseNode>): Map<Int, Mat4f> {
        val globals = HashMap<Int, Mat4f>(nodes.size)
        val byIndex = nodes.associateBy { it.poseIndex }
        require(byIndex.size == nodes.size) { "Duplicate model node indices" }
        val visiting = HashSet<Int>()
        fun resolve(node: ModelPoseNode): Mat4f {
            globals[node.poseIndex]?.let { return it }
            check(visiting.add(node.poseIndex)) { "Cycle in model pose hierarchy" }
            val local = node.restTransform.matrixF
            val parent = node.poseParent?.takeIf { byIndex[it.poseIndex] === it }?.let(::resolve)
            val result = parent?.mul(local, MutableMat4f()) ?: local
            globals[node.poseIndex] = result
            visiting.remove(node.poseIndex)
            return result
        }
        nodes.forEach(::resolve)
        return globals
    }

    private val IDENTITY: Mat4f = MutableMat4f().setIdentity()
    private const val CORNERS = 8
}

/** The nearest bone above this node. */
fun ModelPoseNode.boneAncestor(bones: Set<ModelPoseNode>): ModelPoseNode? {
    var current = poseParent
    while (current != null) {
        if (current in bones) return current
        current = current.poseParent
    }
    return null
}
