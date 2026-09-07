package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.rotatedInverse
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.utils.math.*

/**
 * One simulated bone: what it looks like and where it is positioned.
 */
class RagdollBone(
    val nodeIndex: Int,
    val name: String,
    /** Position in [RagdollPlan.bones] of the nearest simulated ancestor, or -1 for a root. */
    val parent: Int,
    /** Node, which is attached to this bone in the model. */
    val modelParent: Int?,
    val bindPosition: Vec3f,
    val bindRotation: QuatF,
    val axis: Vec3f,
    val length: Float,
    val radius: Float,
    val density: Float,
    val twistAngle: Float,
    val swingAngle: Float,
) {
    /** Where the capsule's center of mass sits relative to the joint, in the bone's own space. */
    val centreOfMass: Vec3f = axis * (length * 0.5f)
}

/**
 * The skeleton a [RagdollStateSpec] describes for one model.
 */
class RagdollPlan(
    val bones: List<RagdollBone>,
    /** Every node of the model, parents first. */
    val order: List<RuntimeNode>,
) {
    companion object {
        /**
         * Defines which bones of the [target] object this ragdoll model simulates and what each of them looks like.
         *
         * [allowed] is what remains after applying the layer mask, so a controller responsible only for the upper body
         * passes only the upper body to the physics system.
         *
         * Returns null if there is nothing left to simulate: an empty mask, a nonexistent root bone,
         * or a model that has no skeleton at all.
         */
        fun build(target: PoseTarget, spec: RagdollStateSpec, allowed: Set<Int>): RagdollPlan? {
            val order = walkParentsFirst(target)
            if (order.isEmpty()) return null

            val root = spec.rootBone?.let { target.node(it) }
            if (spec.rootBone != null && root == null) return null

            val bindGlobals = HashMap<Int, Mat4f>(order.size)
            order.forEach { node ->
                val local = node.definition.baseTransform.matrixF
                val parent = node.parentNode()?.let { bindGlobals[it.definition.index] }
                bindGlobals[node.definition.index] = parent?.mul(local, MutableMat4f()) ?: local
            }

            val chosenByHand = root != null || allowed.size < target.nodesByIndex.size
            val candidates = if (chosenByHand) target.nodesByIndex.keys else boneNodes(target)
            val simulated = order.filter { node ->
                val index = node.definition.index
                index in candidates && index in allowed && (root == null || node.isUnder(root)) && spec.boneSpec(node.name)?.simulated != false
            }
            if (simulated.isEmpty()) return null

            val boneByNode = HashMap<Int, Int>(simulated.size)
            simulated.forEachIndexed { position, node -> boneByNode[node.definition.index] = position }

            val bones = simulated.mapIndexed { position, node ->
                buildBone(node, position, spec, bindGlobals, boneByNode)
            }
            return RagdollPlan(bones, order)
        }

        private fun buildBone(
            node: RuntimeNode,
            position: Int,
            spec: RagdollStateSpec,
            bindGlobals: Map<Int, Mat4f>,
            boneByNode: Map<Int, Int>,
        ): RagdollBone {
            val overrides = spec.boneSpec(node.name)
            val global = bindGlobals.getValue(node.definition.index)
            val bindPosition = MutableVec3f()
            val bindRotation = MutableQuatF()
            global.decompose(bindPosition, bindRotation, null)

            val children =
                node.children.filter { boneByNode.containsKey(it.definition.index) }.ifEmpty { node.children }
            val towards = MutableVec3f()
            children.forEach { child ->
                val childGlobal = bindGlobals[child.definition.index] ?: return@forEach
                val childPosition = MutableVec3f()
                childGlobal.decompose(childPosition, null, null)
                towards.add(childPosition.subtract(bindPosition))
            }
            if (children.isNotEmpty()) towards.mul(1f / children.size)

            val inBoneSpace = towards.rotatedInverse(bindRotation)
            val measured = inBoneSpace.length()
            val length = if (measured > MIN_BONE_LENGTH) measured else spec.leafBoneLength
            val axis = if (measured > MIN_BONE_LENGTH) inBoneSpace.normed() else Vec3f.Y_AXIS

            val radius =
                overrides?.radius ?: (length * spec.boneRadiusRatio).coerceIn(spec.minBoneRadius, spec.maxBoneRadius)

            return RagdollBone(
                nodeIndex = node.definition.index,
                name = node.name,
                parent = node.simulatedAncestor(boneByNode),
                modelParent = node.parentNode()?.definition?.index,
                bindPosition = Vec3f(bindPosition),
                bindRotation = QuatF(bindRotation),
                axis = Vec3f(axis),
                length = length,
                radius = radius.coerceAtLeast(spec.minBoneRadius),
                density = overrides?.density ?: spec.density,
                twistAngle = overrides?.twistAngle ?: spec.twistAngle,
                swingAngle = overrides?.swingAngle ?: spec.swingAngle,
            ).also { check(it.parent < position) { "Bone ${it.name} is ordered before its parent" } }
        }

        private fun boneNodes(target: PoseTarget): Set<Int> {
            val joints = target.nodesByIndex.values.mapNotNull { it.definition.skin }.flatMap { it.jointsIds }.toSet()
            if (joints.isNotEmpty()) return joints

            val hierarchy = target.nodesByIndex.values.filter { it.definition.index >= MODEL_ROOT }
            val jointsWithoutGeometry = hierarchy.filter { it.definition.mesh == null && it.children.isNotEmpty() }
                .mapTo(HashSet()) { it.definition.index }
            if (jointsWithoutGeometry.isNotEmpty()) return jointsWithoutGeometry

            return hierarchy.mapTo(HashSet()) { it.definition.index }
        }

        private const val MODEL_ROOT = 0

        private fun walkParentsFirst(target: PoseTarget): List<RuntimeNode> {
            val nodes = target.nodesByIndex.values
            val depths = HashMap<Int, Int>(nodes.size)

            fun depth(node: RuntimeNode): Int = depths.getOrPut(node.definition.index) {
                node.parentNode()?.let { depth(it) + 1 } ?: 0
            }

            return nodes.sortedBy(::depth)
        }

        private const val MIN_BONE_LENGTH = 1.0e-4f
    }
}

private fun RuntimeNode.parentNode(): RuntimeNode? = parent as? RuntimeNode

private fun RuntimeNode.isUnder(ancestor: RuntimeNode): Boolean {
    var current: RuntimeNode? = this
    while (current != null) {
        if (current === ancestor) return true
        current = current.parentNode()
    }
    return false
}

private fun RuntimeNode.simulatedAncestor(boneByNode: Map<Int, Int>): Int {
    var current = parentNode()
    while (current != null) {
        boneByNode[current.definition.index]?.let { return it }
        current = current.parentNode()
    }
    return -1
}

