package ru.hollowhorizon.hollowengine.addons.physics.rig

import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollShape
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.shape
import ru.hollowhorizon.hollowengine.addons.physics.rotated
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigOverlay
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Bodies and joints attached to the rig drawn in the positions, they currently occupy on the model.
 */
object PhysicsRigOverlay : RigOverlay {
    const val ID = "hollowengine:physics/bodies"

    override fun draw(model: ModelAttachment, lines: DebugLines.Batch, selected: String?) {
        val bones = model.nodes.flatMap { it.walk() }
        val bodies = bones.filter { it.rigidBody() != null }
        if (bodies.isEmpty()) return

        val byName = bodies.associateBy { it.name }
        val position = MutableVec3f()
        val rotation = MutableQuatF()

        bodies.forEach { node ->
            val body = requireNotNull(node.rigidBody()).spec
            node.globalMatrix.decompose(position, rotation, null)
            val colour = if (node.name == selected) SELECTED_COLOR else BODY_COLOR
            lines.shape(RagdollShape.of(body.shape), Vec3f(position), QuatF(rotation), colour)

            node.joint()?.spec?.let { joint ->
                val parent = byName[joint.parent] ?: node.bodyAncestor(bodies.toSet()) ?: return@let
                lines.line(node.origin(), parent.origin(), JOINT_COLOR)

                if (node.name == selected) drawLimits(lines, node, joint.limits, QuatF(rotation))
            }
        }
    }

    private fun drawLimits(lines: DebugLines.Batch, node: RuntimeNode, limits: JointLimits, rotation: QuatF) {
        val origin = node.origin()
        val radius = node.children.firstOrNull()?.let { (it.origin() - origin).length() * LIMIT_REACH }
            ?.takeIf { it > MIN_LIMIT_RADIUS } ?: DEFAULT_LIMIT_RADIUS

        AXES.forEachIndexed { index, axis ->
            val limit = limits.axes[index]
            if (limit.isLocked) return@forEachIndexed

            lines.sector(
                center = origin,
                axis = axis.rotated(rotation),
                zero = AXES[(index + 1) % AXES.size].rotated(rotation),
                from = limit.min,
                to = limit.max,
                radius = radius,
                color = AXIS_COLORS[index],
            )
        }
    }

    private fun RuntimeNode.origin(): Vec3f = MutableVec3f().also { globalMatrix.transform(Vec3f.ZERO, 1f, it) }

    private fun RuntimeNode.bodyAncestor(bodies: Set<RuntimeNode>): RuntimeNode? {
        var current = parent as? RuntimeNode
        while (current != null) {
            if (current in bodies) return current
            current = current.parent as? RuntimeNode
        }
        return null
    }

    private val BODY_COLOR = 0xCC4DFF99.toInt()
    private val SELECTED_COLOR = 0xFFEB9433.toInt()
    private val JOINT_COLOR = 0xCCFF66CC.toInt()

    private val AXES = listOf(Vec3f.X_AXIS, Vec3f.Y_AXIS, Vec3f.Z_AXIS)
    private val AXIS_COLORS = listOf(0xAAFF4040.toInt(), 0xAA40FF40.toInt(), 0xAA5A8CFF.toInt())

    private const val LIMIT_REACH = 0.45f
    private const val MIN_LIMIT_RADIUS = 0.02f
    private const val DEFAULT_LIMIT_RADIUS = 0.12f
}
