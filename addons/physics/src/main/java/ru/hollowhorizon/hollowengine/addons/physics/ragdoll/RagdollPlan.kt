package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.rig.*
import ru.hollowhorizon.hollowengine.addons.physics.rotatedInverse
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
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
)

/**
 * The skeleton a [RagdollStateSpec] describes for one model.
 */
class RagdollPlan(
    val bones: List<RagdollBone>,
    /** Every node of the model, parents first. */
    val order: List<SceneObject>,
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
            if (order.none { target.bodyOf(it) != null }) return fromSkeleton(target, spec, order, bindGlobals, allowed)

            order.filter { target.bodyOf(it) != null && target.bodyShapeOf(it) == null }.takeIf { it.isNotEmpty() }?.let { bare ->
                HollowEngine.LOGGER.warn("Bodies on {} have no colliders to be made of and are left out", bare.joinToString { it.poseName })
            }
            return fromRig(target, order, bindGlobals, allowed)
        }

        private fun fromRig(
            target: PoseTarget,
            order: List<SceneObject>,
            bindGlobals: Map<Int, Mat4f>,
            allowed: Set<Int>,
        ): RagdollPlan? {
            val shapes = order.filter { it.poseIndex in allowed }
                .mapNotNull { node -> target.bodyShapeOf(node)?.let { node to it } }.toMap()
            val bodies = order.filter { it in shapes }
            if (bodies.isEmpty()) return null

            val byName = bodies.associateBy { it.poseName }
            val parents = bodies.associateWith { node -> node.physicalParent(target.jointOf(node), byName, bodies.toSet()) }
            val sorted = parentsFirst(bodies, parents)

            val positionByNode = HashMap<Int, Int>(sorted.size)
            sorted.forEachIndexed { position, node -> positionByNode[node.poseIndex] = position }

            val bones = sorted.mapIndexed { position, node ->
                val body = requireNotNull(target.bodyOf(node))
                val joint = target.jointOf(node)
                val (bindPosition, bindRotation) = bindGlobals.decomposeOf(node)

                RagdollBone(
                    nodeIndex = node.poseIndex,
                    name = node.poseName,
                    parent = parents[node]?.let { positionByNode[it.poseIndex] ?: -1 } ?: -1,
                    modelParent = node.poseParent?.poseIndex,
                    bindPosition = bindPosition,
                    bindRotation = bindRotation,
                    shape = shapes.getValue(node),
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
            order: List<SceneObject>,
            bindGlobals: Map<Int, Mat4f>,
            allowed: Set<Int>,
        ): RagdollPlan? {
            val root = spec.rootBone?.let { target.node(it) }
            if (spec.rootBone != null && root == null) return null

            val children = order.groupBy { it.poseParent?.poseIndex }
            val chosenByHand = root != null || allowed.size < target.poseNodesByIndex.size
            val candidates = if (chosenByHand) target.poseNodesByIndex.keys else boneNodes(target, children)
            val simulated = order.filter { node ->
                val index = node.poseIndex
                index in candidates && index in allowed && (root == null || node.isUnder(root)) && spec.boneSpec(node.poseName)?.simulated != false
            }
            if (simulated.isEmpty()) return null

            val boneByNode = HashMap<Int, Int>(simulated.size)
            simulated.forEachIndexed { position, node -> boneByNode[node.poseIndex] = position }

            val bones = simulated.mapIndexed { position, node ->
                buildBone(node, position, spec, bindGlobals, boneByNode, children)
            }
            return RagdollPlan(bones, order)
        }

        private fun buildBone(
            node: SceneObject,
            position: Int,
            spec: RagdollStateSpec,
            bindGlobals: Map<Int, Mat4f>,
            boneByNode: Map<Int, Int>,
            childrenByParent: Map<Int?, List<SceneObject>>,
        ): RagdollBone {
            val overrides = spec.boneSpec(node.poseName)
            val (bindPosition, bindRotation) = bindGlobals.decomposeOf(node)

            val allChildren = childrenByParent[node.poseIndex].orEmpty()
            val children = allChildren.filter { boneByNode.containsKey(it.poseIndex) }.ifEmpty { allChildren }
            val towards = MutableVec3f()
            children.forEach { child ->
                val childGlobal = bindGlobals[child.poseIndex] ?: return@forEach
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
                nodeIndex = node.poseIndex,
                name = node.poseName,
                parent = node.simulatedAncestor(boneByNode),
                modelParent = node.poseParent?.poseIndex,
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

        private fun boneNodes(target: PoseTarget, children: Map<Int?, List<SceneObject>>): Set<Int> {
            val joints = target.poseNodesByIndex.values.flatMap { it.source.skinJoints }.toSet()
            if (joints.isNotEmpty()) return joints

            val hierarchy = target.poseNodesByIndex.values.filter { it.poseIndex >= MODEL_ROOT }
            val jointsWithoutGeometry = hierarchy.filter { !it.source.hasMesh && !children[it.poseIndex].isNullOrEmpty() }
                .mapTo(HashSet()) { it.poseIndex }
            if (jointsWithoutGeometry.isNotEmpty()) return jointsWithoutGeometry

            return hierarchy.mapTo(HashSet()) { it.poseIndex }
        }

        private const val MODEL_ROOT = 0

        private fun bindGlobalsOf(order: List<SceneObject>): Map<Int, Mat4f> {
            val bindGlobals = HashMap<Int, Mat4f>(order.size)
            order.forEach { node ->
                val local = node.restTransform.matrixF
                val parent = node.poseParent?.let { bindGlobals[it.poseIndex] }
                bindGlobals[node.poseIndex] = parent?.mul(local, MutableMat4f()) ?: local
            }
            return bindGlobals
        }

        private fun Map<Int, Mat4f>.decomposeOf(node: SceneObject): Pair<Vec3f, QuatF> {
            val position = MutableVec3f()
            val rotation = MutableQuatF()
            getValue(node.poseIndex).decompose(position, rotation, null)
            return Vec3f(position) to QuatF(rotation)
        }

        private fun walkParentsFirst(target: PoseTarget): List<SceneObject> {
            val nodes = target.poseNodesByIndex.values
            val depths = HashMap<Int, Int>(nodes.size)

            fun depth(node: SceneObject): Int = depths.getOrPut(node.poseIndex) {
                node.poseParent?.let { depth(it) + 1 } ?: 0
            }

            return nodes.sortedBy(::depth)
        }

        private fun parentsFirst(
            bodies: List<SceneObject>,
            parents: Map<SceneObject, SceneObject?>,
        ): List<SceneObject> {
            val sorted = ArrayList<SceneObject>(bodies.size)
            val placed = HashSet<SceneObject>(bodies.size)
            val visiting = HashSet<SceneObject>()

            fun place(node: SceneObject) {
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

/**
 * The body the rig puts on [node]. It is read from the rig itself, so the server, which builds no attachments
 * for the nodes it poses, plans the same skeleton the client does.
 */
private fun PoseTarget.bodyOf(node: SceneObject): RigidBodyAttachmentSpec? =
    rig.bone(node.poseName)?.attachments?.firstNotNullOfOrNull { it as? RigidBodyAttachmentSpec }

private fun PoseTarget.jointOf(node: SceneObject): JointAttachmentSpec? =
    rig.bone(node.poseName)?.attachments?.firstNotNullOfOrNull { it as? JointAttachmentSpec }

/** What the body on [node] is made of: the colliders it takes from its bone. Null with no body, or none to make it of. */
private fun PoseTarget.bodyShapeOf(node: SceneObject): RagdollShape? {
    val body = bodyOf(node) ?: return null
    val parts = body.partsOn(rig.bone(node.poseName)?.attachments.orEmpty()).map(RagdollPart::of)
    return parts.takeIf { it.isNotEmpty() }?.let(::RagdollShape)
}

private fun SceneObject.physicalParent(joint: JointAttachmentSpec?, byName: Map<String, SceneObject>, bodies: Set<SceneObject>): SceneObject? {
    joint?.parent?.takeIf { it.isNotBlank() }?.let { named ->
        return byName[named]?.takeIf { it !== this }
    }

    var current = poseParent
    while (current != null) {
        if (current in bodies) return current
        current = current.poseParent
    }
    return null
}

private fun SceneObject.isUnder(ancestor: SceneObject): Boolean {
    var current: SceneObject? = this
    while (current != null) {
        if (current === ancestor) return true
        current = current.poseParent
    }
    return false
}

private fun SceneObject.simulatedAncestor(boneByNode: Map<Int, Int>): Int {
    var current = poseParent
    while (current != null) {
        boneByNode[current.poseIndex]?.let { return it }
        current = current.poseParent
    }
    return -1
}
