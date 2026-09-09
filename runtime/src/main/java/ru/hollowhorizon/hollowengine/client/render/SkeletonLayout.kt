package ru.hollowhorizon.hollowengine.client.render

import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
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

object SkeletonLayout {
    fun of(attachment: ModelAttachment): List<SkeletonBone> {
        val bones = boneNodes(attachment)
        val layout = ArrayList<SkeletonBone>()
        attachment.nodes.forEach { root -> trace(root, parent = null, bones, layout) }
        return layout
    }

    private fun boneNodes(attachment: ModelAttachment): Set<Int>? {
        val all = attachment.nodes.flatMap { it.walk() }
        val joints = all.mapNotNull { it.definition.skin }.flatMap { it.jointsIds }.toSet()
        if (joints.isNotEmpty()) return joints

        val withoutGeometry = all.filter { it.definition.mesh == null }.mapTo(HashSet()) { it.definition.index }
        return withoutGeometry.ifEmpty { null }
    }

    private fun trace(node: RuntimeNode, parent: SkeletonBone?, bones: Set<Int>?, into: MutableList<SkeletonBone>) {
        if (bones != null && node.definition.index !in bones) {
            node.children.forEach { trace(it, parent, bones, into) }
            return
        }

        val head = node.origin()
        val heading = parent?.direction ?: node.axis(Vec3f.Y_AXIS, head)
        val children = node.children.filter { bones == null || it.definition.index in bones }
        val chain = children.chainChild(head, heading)

        val bone = SkeletonBone(
            node = node,
            head = head,
            tail = chain?.origin() ?: (head + heading * stubLength(parent)),
            roll = node.axis(Vec3f.Z_AXIS, head),
            links = children.filter { it !== chain }.map { it.origin() },
        )
        into += bone

        node.children.forEach { trace(it, bone, bones, into) }
    }

    private fun stubLength(parent: SkeletonBone?): Float =
        (parent?.length ?: DEFAULT_LENGTH).coerceIn(MIN_STUB_LENGTH, MAX_STUB_LENGTH)

    private fun List<RuntimeNode>.chainChild(head: Vec3f, heading: Vec3f): RuntimeNode? {
        if (isEmpty()) return null

        val aligned = mapNotNull { child ->
            val offset = child.origin() - head
            if (offset.length() < MIN_LENGTH) null else child to (offset.normed() dot heading)
        }
        if (aligned.size == 1) return aligned[0].first

        val best = aligned.maxByOrNull { it.second } ?: return null
        if (best.second < MIN_ALIGNMENT) return null
        return aligned.first { it.second >= best.second - TIE }.first
    }

    private fun RuntimeNode.origin(): Vec3f = MutableVec3f().also { globalMatrix.transform(Vec3f.ZERO, 1f, it) }

    internal fun RuntimeNode.axis(axis: Vec3f, origin: Vec3f): Vec3f {
        val end = MutableVec3f()
        globalMatrix.transform(axis, 1f, end)
        end.subtract(origin)
        return if (end.length() < MIN_LENGTH) axis else end.norm()
    }

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
