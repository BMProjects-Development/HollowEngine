package ru.hollowhorizon.hollowengine.addons.physics.rig

import ru.hollowhorizon.hollowengine.client.models.internal.rig.BoneBounds
import ru.hollowhorizon.hollowengine.client.models.internal.rig.BoneGeometry
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigGenerator
import ru.hollowhorizon.hollowengine.client.models.internal.rig.boneAncestor
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
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

    private const val MIN_HALF_EXTENT = 0.02f
    private const val MIN_BODY_SIZE = 0.04f
    private const val CAPSULE_RATIO = 1.8f

    override fun generate(model: ModelAttachment, current: ModelRig): ModelRig =
        generate(model.nodes.flatMap { it.walk() }, model.model.boneBounds, current)

    internal fun generate(
        nodes: List<RuntimeNode>,
        geometry: Map<Int, Pair<Vec3f, Vec3f>>,
        current: ModelRig,
    ): ModelRig {
        val worthSimulating = BoneGeometry.boundsPerBone(nodes, geometry).filterValues { it.largestSide >= MIN_BODY_SIZE }

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

    private fun BoneBounds.toShape(): RigidBodyShape {
        val centre = RigVector.of(center)
        val half = MutableVec3f(
            (size.x / 2f).coerceAtLeast(MIN_HALF_EXTENT),
            (size.y / 2f).coerceAtLeast(MIN_HALF_EXTENT),
            (size.z / 2f).coerceAtLeast(MIN_HALF_EXTENT),
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
}
