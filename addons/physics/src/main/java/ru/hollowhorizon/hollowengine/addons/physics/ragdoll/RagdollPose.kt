package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.utils.math.*

/**
 * Turns simulated bones into the pose that animator applies.
 */
internal object RagdollPose {
    /**
     * [pose] places each node of the model in the model space.
     *
     * @param order all model nodes, with parent nodes before child nodes.
     */
    fun globals(
        order: List<RuntimeNode>,
        pose: AnimationPose,
        into: MutableMap<Int, MutableMat4f>,
    ): MutableMap<Int, MutableMat4f> {
        into.clear()
        val local = MutableMat4f()

        order.forEach { node ->
            val index = node.definition.index
            val base = node.definition.baseTransform
            val bone = pose[index]

            local.setIdentity().translate(base.translation.add(bone?.translation ?: Vec3f.ZERO, MutableVec3f()))
                .rotate(MutableQuatF(base.rotation).mul(bone?.rotation ?: QuatF.IDENTITY).norm())
                .scale(base.scale.mul(bone?.scale ?: Vec3f.ONES, MutableVec3f()))

            val parent = (node.parent as? RuntimeNode)?.let { into[it.definition.index] }
            val global = into.getOrPut(index) { MutableMat4f() }
            if (parent == null) global.set(local) else parent.mul(local, global)
        }
        return into
    }

    /**
     * @param simulated: position to which physics engine places each simulated bone in the model space based on the node index.
     * @param animated: position to which animation places each bone of the model in the model space based on the node index.
     */
    fun write(
        plan: RagdollPlan,
        target: PoseTarget,
        simulated: Map<Int, Mat4f>,
        animated: Map<Int, Mat4f>,
    ): AnimationPose {
        val pose = AnimationPose()
        val translation = MutableVec3f()
        val rotation = MutableQuatF()

        val drawn = drawnGlobals(plan, simulated, animated)

        plan.bones.forEach { bone ->
            val global = simulated[bone.nodeIndex] ?: return@forEach
            val node = target.nodesByIndex[bone.nodeIndex] ?: return@forEach
            val local = localOf(bone, global, drawn) ?: return@forEach
            local.decompose(translation, rotation, null)

            val base = node.definition.baseTransform
            val bonePose = pose.bone(bone.nodeIndex)
            bonePose.translation = Vec3f(translation).subtract(base.translation, MutableVec3f())
            bonePose.rotation = MutableQuatF(base.rotation).invert().mul(rotation).norm()
        }
        return pose
    }

    /**
     * Where each node will end up after the renderer applies the pose and reorganizes the hierarchy.
     */
    private fun drawnGlobals(
        plan: RagdollPlan,
        simulated: Map<Int, Mat4f>,
        animated: Map<Int, Mat4f>,
    ): Map<Int, Mat4f> {
        val drawn = HashMap<Int, Mat4f>(plan.order.size)

        plan.order.forEach { node ->
            val index = node.definition.index
            simulated[index]?.let { drawn[index] = it; return@forEach }

            val parent = node.parent as? RuntimeNode
            val parentDrawn = parent?.definition?.index?.let { drawn[it] }
            val local = localInPose(node, parent, animated)
            drawn[index] = parentDrawn?.mul(local, MutableMat4f()) ?: local
        }
        return drawn
    }

    private fun localInPose(node: RuntimeNode, parent: RuntimeNode?, animated: Map<Int, Mat4f>): Mat4f {
        val global = animated[node.definition.index] ?: return node.definition.baseTransform.matrixF
        val parentGlobal = parent?.definition?.index?.let { animated[it] } ?: return global

        val inverse = MutableMat4f().set(parentGlobal)
        if (!inverse.invert()) return node.definition.baseTransform.matrixF
        return inverse.mul(global, MutableMat4f())
    }

    private fun localOf(bone: RagdollBone, global: Mat4f, drawn: Map<Int, Mat4f>): Mat4f? {
        val parent = bone.modelParent?.let { drawn[it] } ?: return global

        val inverse = MutableMat4f().set(parent)
        if (!inverse.invert()) return null
        return inverse.mul(global, MutableMat4f())
    }
}
