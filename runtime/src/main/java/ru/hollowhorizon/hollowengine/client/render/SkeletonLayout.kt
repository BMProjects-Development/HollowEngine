package ru.hollowhorizon.hollowengine.client.render

import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.models.addedBones
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * One bone as it is drawn. From a joint to wherever the bone reaches.
 */
class SkeletonBone(
    val node: RuntimeNode,
    val head: Vec3f,
    val tail: Vec3f,
    val roll: Vec3f,
    val links: List<Vec3f>,
) {
    val name: String get() = node.name
}

/**
 * Lays bones out from the bind pose of the model, then follows the current pose: which child a bone reaches to
 * and where a bone without one ends are settled once at rest, so rotating a bone never makes its parent swap
 * the child it points at.
 */
object SkeletonLayout {
    fun of(attachment: ModelAttachment): List<SkeletonBone> {
        val bones = boneNodes(attachment)
        val layout = ArrayList<SkeletonBone>()
        attachment.nodes.forEach { root ->
            trace(root, root.parent?.globalMatrix ?: Mat4f.IDENTITY, parent = null, bones, layout)
        }
        return layout
    }

    private fun boneNodes(attachment: ModelAttachment): Set<Int>? {
        val all = attachment.nodes.flatMap { it.walk() }
        val joints = all.mapNotNull { it.definition.skin }.flatMap { it.jointsIds }.toSet()
        val added = attachment.rig.addedBones.keys
        if (joints.isNotEmpty()) return joints + all.filter { it.name in added }.map { it.definition.index }

        val withoutGeometry = all.filter { it.definition.mesh == null }.mapTo(HashSet()) { it.definition.index }
        return withoutGeometry.ifEmpty { null }
    }

    /** A bone in the bind pose: where it heads and how long it is there. */
    private class RestBone(val direction: Vec3f, val length: Float)

    private fun trace(
        node: RuntimeNode,
        parentRest: Mat4f,
        parent: RestBone?,
        bones: Set<Int>?,
        into: MutableList<SkeletonBone>,
    ) {
        val rest = parentRest.mul(node.definition.baseTransform.matrixF, MutableMat4f())
        if (bones != null && node.definition.index !in bones) {
            node.children.forEach { trace(it, rest, parent, bones, into) }
            return
        }

        val restHead = rest.origin()
        val heading = parent?.direction ?: rest.axis(Vec3f.Y_AXIS, restHead)
        val children = node.children
            .filter { bones == null || it.definition.index in bones }
            .map { it to rest.mul(it.definition.baseTransform.matrixF, MutableMat4f()).origin() }
        val chain = children.chainChild(restHead, heading)
        val restTail = chain?.second ?: (restHead + heading * stubLength(parent))

        val head = node.globalMatrix.origin()
        into += SkeletonBone(
            node = node,
            head = head,
            tail = chain?.first?.globalMatrix?.origin() ?: node.posed(restTail, rest),
            roll = node.globalMatrix.axis(Vec3f.Z_AXIS, head),
            links = children.filter { it !== chain }.map { it.first.globalMatrix.origin() },
        )

        val restBone = (restTail - restHead).let { RestBone(it.directionOr(heading), it.length()) }
        node.children.forEach { trace(it, rest, restBone, bones, into) }
    }

    /** Where [restPoint], held by this node in the bind pose, is now. */
    private fun RuntimeNode.posed(restPoint: Vec3f, rest: Mat4f): Vec3f {
        val inverse = MutableMat4f()
        if (!rest.invert(inverse)) return restPoint
        val local = inverse.transform(restPoint, 1f, MutableVec3f())
        return globalMatrix.transform(local, 1f, MutableVec3f())
    }

    private fun stubLength(parent: RestBone?): Float =
        (parent?.length ?: DEFAULT_LENGTH).coerceIn(MIN_STUB_LENGTH, MAX_STUB_LENGTH)

    private fun List<Pair<RuntimeNode, Vec3f>>.chainChild(head: Vec3f, heading: Vec3f): Pair<RuntimeNode, Vec3f>? {
        if (isEmpty()) return null

        val aligned = mapNotNull { child ->
            val offset = child.second - head
            if (offset.length() < MIN_LENGTH) null else child to (offset.normed() dot heading)
        }
        if (aligned.size == 1) return aligned[0].first

        val best = aligned.maxByOrNull { it.second } ?: return null
        if (best.second < MIN_ALIGNMENT) return null
        return aligned.first { it.second >= best.second - TIE }.first
    }

    private fun Mat4f.origin(): Vec3f = transform(Vec3f.ZERO, 1f, MutableVec3f())

    private fun Mat4f.axis(axis: Vec3f, origin: Vec3f): Vec3f =
        (transform(axis, 1f, MutableVec3f()) - origin).directionOr(axis)

    private fun Vec3f.directionOr(fallback: Vec3f): Vec3f = if (length() < MIN_LENGTH) fallback else normed()

    internal const val MIN_LENGTH = 1.0e-4f
    private const val DEFAULT_LENGTH = 0.1f
    private const val MIN_STUB_LENGTH = 0.03f
    private const val MAX_STUB_LENGTH = 0.12f
    private const val MIN_ALIGNMENT = 0.3f
    private const val TIE = 1.0e-3f
}

val SkeletonBone.length: Float get() = (tail - head).length()

val SkeletonBone.direction: Vec3f
    get() = (tail - head).let { if (it.length() < SkeletonLayout.MIN_LENGTH) Vec3f.Y_AXIS else it.normed() }
