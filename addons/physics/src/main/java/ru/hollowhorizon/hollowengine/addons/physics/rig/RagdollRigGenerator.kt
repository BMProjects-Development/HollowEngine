package ru.hollowhorizon.hollowengine.addons.physics.rig

import ru.hollowhorizon.hollowengine.client.models.internal.rig.BoneBounds
import ru.hollowhorizon.hollowengine.client.models.internal.rig.BoneGeometry
import ru.hollowhorizon.hollowengine.client.models.internal.rig.ColliderRigGenerator
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigGenerator
import ru.hollowhorizon.hollowengine.client.models.internal.rig.boneAncestor
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.sqrt

/**
 * Turns a model into a ragdoll to start from: a body on every bone whose geometry has some bulk, made of the bone's
 * colliders, and a joint holding it to the body it hangs from. A bone without a collider gets one fitted around its
 * geometry, the same one "colliders from geometry" would give it. A bone that already has a body keeps it, and its
 * joint, as they are.
 */
object RagdollRigGenerator : RigGenerator {
    const val ID = "hollowengine:physics/ragdoll_from_geometry"

    private const val BODY_ID = "body"
    private const val JOINT_ID = "joint"

    /** Geometry thinner than this on any side is a lid, a brow or a decal, which rides on the bone above it. */
    private const val MIN_THICKNESS = 0.05f

    override fun generate(model: ModelAttachment, current: ModelRig): ModelRig =
        generate(model.nodes.flatMap { it.walk() }, model.model.boneBounds, current)

    internal fun generate(
        nodes: List<RuntimeNode>,
        geometry: Map<Int, Pair<Vec3f, Vec3f>>,
        current: ModelRig,
    ): ModelRig {
        val corners = BoneGeometry.cornersPerBone(nodes, geometry)
        val bodies = corners.mapValues { (_, points) -> BoneBounds.around(points) }.filterValues { it.thinnestSide >= MIN_THICKNESS }
        if (bodies.isEmpty()) return current
        val parents = jointParents(bodies, BoneGeometry.bindGlobalsOf(nodes))
        val takenNames = current.allAttachments().filter { it.second is ColliderAttachmentSpec }.mapTo(HashSet()) { it.second.id }

        var rig = current
        bodies.keys.forEach { bone ->
            var holder = rig.bone(bone.name) ?: RigBone.EMPTY
            // A body already on the bone was set up by hand, its joint and limits with it, and is left as it is.
            if (holder.attachments.any { it is RigidBodyAttachmentSpec }) return@forEach
            if (holder.attachments.none { it is ColliderAttachmentSpec }) {
                val name = ColliderRigGenerator.freeName(bone.name, takenNames).also(takenNames::add)
                ColliderRigGenerator.fit(name, corners.getValue(bone))?.let { holder = holder.withAttachment(it) }
            }
            holder = holder.withAttachment(RigidBodyAttachmentSpec(id = BODY_ID))
            parents[bone]?.let { parent -> holder = holder.withAttachment(JointAttachmentSpec(id = JOINT_ID, parent = parent.name)) }
            rig = rig.withBone(bone.name, holder)
        }
        return rig
    }


    private fun jointParents(bodies: Map<RuntimeNode, BoneBounds>, globals: Map<Int, Mat4f>): Map<RuntimeNode, RuntimeNode> {
        val parents = HashMap<RuntimeNode, RuntimeNode>()
        val tops = ArrayList<RuntimeNode>()
        bodies.keys.forEach { bone ->
            val above = bone.boneAncestor(bodies.keys)
            if (above == null) tops += bone else parents[bone] = above
        }

        val main = tops.maxBy { bodies.getValue(it).volume }
        val joined = HashSet(bodies.keys.filter { it.isUnder(main) })
        val waiting = tops.filterTo(ArrayList()) { it !== main }
        while (waiting.isNotEmpty()) {
            val (top, nearest) = waiting.flatMap { top ->
                val pivot = globals[top.definition.index]?.origin() ?: return@flatMap emptyList()
                joined.map { body -> Triple(top, body, distance(pivot, body, bodies.getValue(body), globals)) }
            }.minByOrNull { it.third } ?: break
            parents[top] = nearest
            waiting.remove(top)
            joined += bodies.keys.filter { it.isUnder(top) }
        }
        return parents
    }

    /** How far [point], in model space, is from the geometry of [bone]. */
    private fun distance(point: Vec3f, bone: RuntimeNode, box: BoneBounds, globals: Map<Int, Mat4f>): Float {
        val inverse = MutableMat4f().set(globals[bone.definition.index] ?: return Float.MAX_VALUE)
        if (!inverse.invert()) return Float.MAX_VALUE
        val local = inverse.transform(point, 1f, MutableVec3f())
        val x = maxOf(box.min.x - local.x, 0f, local.x - box.max.x)
        val y = maxOf(box.min.y - local.y, 0f, local.y - box.max.y)
        val z = maxOf(box.min.z - local.z, 0f, local.z - box.max.z)
        return sqrt(x * x + y * y + z * z)
    }

    private val BoneBounds.volume: Float get() = size.x * size.y * size.z

    private fun Mat4f.origin(): Vec3f = Vec3f(transform(Vec3f.ZERO, 1f, MutableVec3f()))

    private fun RuntimeNode.isUnder(ancestor: RuntimeNode): Boolean {
        var current: RuntimeNode? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? RuntimeNode
        }
        return false
    }
}
