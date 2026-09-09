package ru.hollowhorizon.hollowengine.addons.physics.rig

import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigGenerator
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Turns a model into a ragdoll to start from: a body around whatever geometry each bone actually holds,
 * and a joint to the bone above it.
 */
object RagdollRigGenerator : RigGenerator {
    const val ID = "hollowengine:physics/ragdoll_from_geometry"

    private const val BODY_ID = "body"
    private const val JOINT_ID = "joint"

    override fun generate(model: ModelAttachment, current: ModelRig): ModelRig =
        generate(model.nodes.flatMap { it.walk() }, model.model.boneBounds, current)

    internal fun generate(
        nodes: List<RuntimeNode>,
        geometry: Map<Int, Pair<Vec3f, Vec3f>>,
        current: ModelRig,
    ): ModelRig {
        if (nodes.isEmpty() || geometry.isEmpty()) return current

        val bindGlobals = bindGlobalsOf(nodes)
        val bones = boneNodes(nodes)
        val boxes = geometryPerBone(nodes, bones, geometry, bindGlobals)
        if (boxes.isEmpty()) return current

        val worthSimulating = boxes.filterValues { it.isWorthSimulating }

        var rig = current
        worthSimulating.forEach { (bone, box) ->
            val existing = rig.bone(bone.name) ?: RigBone.EMPTY
            val parent = bone.boneAncestor(worthSimulating.keys)
            val body = RigidBodyAttachmentSpec(id = BODY_ID, shape = box.toShape())

            rig = rig.withBone(bone.name, existing.withAttachment(body).withJointTo(parent))
        }
        return rig
    }

    private fun RigBone.withJointTo(parent: RuntimeNode?): RigBone = if (parent == null) withoutAttachment(JOINT_ID)
    else withAttachment(JointAttachmentSpec(id = JOINT_ID, parent = parent.name))

    private fun boneNodes(nodes: List<RuntimeNode>): Set<RuntimeNode> {
        val joints = nodes.mapNotNull { it.definition.skin }.flatMap { it.jointsIds }.toSet()
        if (joints.isNotEmpty()) return nodes.filterTo(LinkedHashSet()) { it.definition.index in joints }

        return nodes.filterTo(LinkedHashSet()) { it.definition.mesh == null && it.children.isNotEmpty() }
    }

    private fun geometryPerBone(
        nodes: List<RuntimeNode>,
        bones: Set<RuntimeNode>,
        geometry: Map<Int, Pair<Vec3f, Vec3f>>,
        bindGlobals: Map<Int, Mat4f>,
    ): Map<RuntimeNode, Bounds> {
        val boxes = LinkedHashMap<RuntimeNode, Bounds>()
        val corner = MutableVec3f()
        val inBone = MutableVec3f()

        nodes.forEach { node ->
            val (min, max) = geometry[node.definition.index] ?: return@forEach
            val bone = if (node in bones) node else node.boneAncestor(bones) ?: return@forEach
            val toBone = intoBoneSpace(node, bone, bindGlobals) ?: return@forEach
            val box = boxes.getOrPut(bone) { Bounds() }

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
        return boxes
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

    private val IDENTITY: Mat4f = MutableMat4f().setIdentity()
    private const val CORNERS = 8
}

/** The nearest bone at or above this node. */
private fun RuntimeNode.boneAncestor(bones: Set<RuntimeNode>): RuntimeNode? {
    var current = parent as? RuntimeNode
    while (current != null) {
        if (current in bones) return current
        current = current.parent as? RuntimeNode
    }
    return null
}

/** A box being measured, in some bone's space. */
private class Bounds {
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

    val isWorthSimulating: Boolean
        get() = maxOf(max.x - min.x, max.y - min.y, max.z - min.z) >= MIN_BODY_SIZE

    fun toShape(): RigidBodyShape {
        val centre = RigVector((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
        val half = MutableVec3f(
            ((max.x - min.x) / 2f).coerceAtLeast(MIN_HALF_EXTENT),
            ((max.y - min.y) / 2f).coerceAtLeast(MIN_HALF_EXTENT),
            ((max.z - min.z) / 2f).coerceAtLeast(MIN_HALF_EXTENT),
        )

        val longest = listOf(half.x, half.y, half.z).max()
        val others = listOf(half.x, half.y, half.z).sorted().take(2)
        val slender = longest > others.max() * CAPSULE_RATIO
        if (!slender) return RigidBodyShape.Box(RigVector(half.x, half.y, half.z), centre)

        val radius = ((others[0] + others[1]) / 2f).coerceAtLeast(MIN_HALF_EXTENT)
        val rotation = when (longest) {
            half.x -> RigVector(z = -90f)
            half.z -> RigVector(x = 90f)
            else -> RigVector.ZERO
        }
        return RigidBodyShape.Capsule(
            radius = radius,
            length = (longest * 2f).coerceAtLeast(radius * 2f + MIN_HALF_EXTENT),
            offset = centre,
            rotation = rotation,
        )
    }

    private companion object {
        const val MIN_HALF_EXTENT = 0.02f
        const val MIN_BODY_SIZE = 0.04f
        const val CAPSULE_RATIO = 1.8f
    }
}
