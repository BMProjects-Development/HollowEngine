package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.rig.*
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
    val shape: RagdollShape,
    /** Where joint to the parent sits on this bone, in the bone's own space. */
    val pivot: Vec3f,
    val density: Float,
    /** How this body gets on with other bodies of rig; see [BodyCollision]. */
    val collision: BodyCollision,
    /** How far this bone may move relative to its parent; see [JointLimits]. */
    val limits: JointLimits,
) {
    /** Where the body's center of mass sits relative to the joint, in the bone's own space. */
    val centreOfMass: Vec3f get() = shape.center
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
         * Returns null if there is nothing left to simulate.
         */
        fun build(target: PoseTarget, spec: RagdollStateSpec, allowed: Set<Int>): RagdollPlan? {
            val order = walkParentsFirst(target)
            if (order.isEmpty()) return null

            val bindGlobals = bindGlobalsOf(order)
            return fromRig(order, bindGlobals, allowed) ?: fromSkeleton(target, spec, order, bindGlobals, allowed)
        }

        private fun fromRig(
            order: List<RuntimeNode>,
            bindGlobals: Map<Int, Mat4f>,
            allowed: Set<Int>,
        ): RagdollPlan? {
            val bodies = order.filter { it.definition.index in allowed && it.rigidBody() != null }
            if (bodies.isEmpty()) return null

            val byName = bodies.associateBy { it.name }
            val parents = bodies.associateWith { node -> node.physicalParent(byName, bodies.toSet()) }
            val sorted = parentsFirst(bodies, parents)

            val positionByNode = HashMap<Int, Int>(sorted.size)
            sorted.forEachIndexed { position, node -> positionByNode[node.definition.index] = position }

            val bones = sorted.mapIndexed { position, node ->
                val body = requireNotNull(node.rigidBody()).spec
                val joint = node.joint()?.spec
                val (bindPosition, bindRotation) = bindGlobals.decomposeOf(node)

                RagdollBone(
                    nodeIndex = node.definition.index,
                    name = node.name,
                    parent = parents[node]?.let { positionByNode[it.definition.index] ?: -1 } ?: -1,
                    modelParent = node.parentNode()?.definition?.index,
                    bindPosition = bindPosition,
                    bindRotation = bindRotation,
                    shape = RagdollShape.of(body.shape),
                    pivot = joint?.pivot?.toVec3f() ?: Vec3f.ZERO,
                    density = body.density,
                    collision = body.collision,
                    limits = joint?.limits ?: JointLimits.FIXED,
                ).also { check(it.parent < position) { "Bone ${it.name} is ordered before its parent" } }
            }
            return RagdollPlan(bones, order)
        }

        private fun fromSkeleton(
            target: PoseTarget,
            spec: RagdollStateSpec,
            order: List<RuntimeNode>,
            bindGlobals: Map<Int, Mat4f>,
            allowed: Set<Int>,
        ): RagdollPlan? {
            val root = spec.rootBone?.let { target.node(it) }
            if (spec.rootBone != null && root == null) return null

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
            val (bindPosition, bindRotation) = bindGlobals.decomposeOf(node)

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
            val axis = if (measured > MIN_BONE_LENGTH) Vec3f(inBoneSpace.normed()) else Vec3f.Y_AXIS

            val radius =
                overrides?.radius ?: (length * spec.boneRadiusRatio).coerceIn(spec.minBoneRadius, spec.maxBoneRadius)

            return RagdollBone(
                nodeIndex = node.definition.index,
                name = node.name,
                parent = node.simulatedAncestor(boneByNode),
                modelParent = node.parentNode()?.definition?.index,
                bindPosition = bindPosition,
                bindRotation = bindRotation,
                shape = RagdollShape.alongBone(axis, length, radius.coerceAtLeast(spec.minBoneRadius)),
                pivot = Vec3f.ZERO,
                density = overrides?.density ?: spec.density,
                collision = BodyCollision(),
                limits = JointLimits(
                    x = AxisLimit.of(overrides?.twistAngle ?: spec.twistAngle),
                    y = AxisLimit.of(overrides?.swingAngle ?: spec.swingAngle),
                    z = AxisLimit.of(overrides?.swingAngle ?: spec.swingAngle),
                ),
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

        private fun bindGlobalsOf(order: List<RuntimeNode>): Map<Int, Mat4f> {
            val bindGlobals = HashMap<Int, Mat4f>(order.size)
            order.forEach { node ->
                val local = node.definition.baseTransform.matrixF
                val parent = node.parentNode()?.let { bindGlobals[it.definition.index] }
                bindGlobals[node.definition.index] = parent?.mul(local, MutableMat4f()) ?: local
            }
            return bindGlobals
        }

        private fun Map<Int, Mat4f>.decomposeOf(node: RuntimeNode): Pair<Vec3f, QuatF> {
            val position = MutableVec3f()
            val rotation = MutableQuatF()
            getValue(node.definition.index).decompose(position, rotation, null)
            return Vec3f(position) to QuatF(rotation)
        }

        private fun walkParentsFirst(target: PoseTarget): List<RuntimeNode> {
            val nodes = target.nodesByIndex.values
            val depths = HashMap<Int, Int>(nodes.size)

            fun depth(node: RuntimeNode): Int = depths.getOrPut(node.definition.index) {
                node.parentNode()?.let { depth(it) + 1 } ?: 0
            }

            return nodes.sortedBy(::depth)
        }

        private fun parentsFirst(
            bodies: List<RuntimeNode>,
            parents: Map<RuntimeNode, RuntimeNode?>,
        ): List<RuntimeNode> {
            val sorted = ArrayList<RuntimeNode>(bodies.size)
            val placed = HashSet<RuntimeNode>(bodies.size)
            val visiting = HashSet<RuntimeNode>()

            fun place(node: RuntimeNode) {
                if (node in placed || node in visiting) return
                visiting += node
                parents[node]?.let(::place)
                visiting -= node
                if (placed.add(node)) sorted += node
            }

            bodies.forEach(::place)
            return sorted
        }

        private const val MIN_BONE_LENGTH = 1.0e-4f
    }
}

private fun RuntimeNode.parentNode(): RuntimeNode? = parent as? RuntimeNode

private fun RuntimeNode.physicalParent(byName: Map<String, RuntimeNode>, bodies: Set<RuntimeNode>): RuntimeNode? {
    joint()?.spec?.parent?.takeIf { it.isNotBlank() }?.let { named ->
        return byName[named]?.takeIf { it !== this }
    }

    var current = parentNode()
    while (current != null) {
        if (current in bodies) return current
        current = current.parentNode()
    }
    return null
}

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
