package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Skeleton of a model, drawn on the same key as vanilla hitboxes (F3+B).
 */
@ClientOnly
object DebugSkeletonRenderer {
    val isEnabled: Boolean
        get() = Minecraft.getInstance().entityRenderDispatcher.shouldRenderHitBoxes()

    fun render(attachment: ModelAttachment, poseStack: PoseStack, buffers: MultiBufferSource) {
        val lines = DebugLines.batch(buffers, poseStack)
        val origin = MutableVec3f()
        val target = MutableVec3f()

        attachment.nodes.forEach { root ->
            root.walk().forEach { node ->
                node.globalMatrix.transform(ZERO, 1f, origin)
                val roll = axisOf(node, Vec3f.Z_AXIS, origin)

                node.children.forEach { child ->
                    child.globalMatrix.transform(ZERO, 1f, target)
                    lines.bone(origin, target, roll, BONE_COLOR)
                }

                if (node.children.isEmpty()) {
                    val tip = axisOf(node, Vec3f.Y_AXIS, origin) * TIP_LENGTH + origin
                    lines.bone(origin, tip, roll, TIP_COLOR)
                }

                drawAxes(lines, node, origin)
            }
        }
    }

    private fun drawAxes(lines: DebugLines.Batch, node: RuntimeNode, origin: Vec3f) {
        AXES.forEachIndexed { index, axis ->
            val direction = axisOf(node, axis, origin)
            lines.line(origin, direction * AXIS_LENGTH + origin, AXIS_COLORS[index])
        }
    }

    private fun axisOf(node: RuntimeNode, axis: Vec3f, origin: Vec3f): Vec3f {
        val end = MutableVec3f()
        node.globalMatrix.transform(axis, 1f, end)
        end.subtract(origin)
        return if (end.length() < 1.0e-6f) axis else end.norm()
    }

    private const val AXIS_LENGTH = 0.06f
    private const val TIP_LENGTH = 0.08f

    private val ZERO = Vec3f.ZERO
    private val AXES = listOf(Vec3f.X_AXIS, Vec3f.Y_AXIS, Vec3f.Z_AXIS)
    private val AXIS_COLORS = listOf(0xFFFF4040.toInt(), 0xFF40FF40.toInt(), 0xFF5A8CFF.toInt())
    private val BONE_COLOR = 0xFFFFFFFF.toInt()
    private val TIP_COLOR = 0xFFFFD94D.toInt()
}
