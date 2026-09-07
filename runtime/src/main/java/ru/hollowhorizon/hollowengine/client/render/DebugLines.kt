package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import ru.hollowhorizon.hollowengine.client.utils.color
import ru.hollowhorizon.hollowengine.client.utils.normal
import ru.hollowhorizon.hollowengine.client.utils.vertex
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.OptionalDouble
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wireframe shapes for the debug views: skeletons, physics bodies, colliders.
 */
@ClientOnly
object DebugLines {
    val OVERLAY: RenderType = RenderType.create(
        "hollowengine:debug_overlay_lines",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES,
        1536,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .createCompositeState(false),
    )

    fun batch(buffers: MultiBufferSource, poseStack: PoseStack): Batch =
        Batch(buffers.getBuffer(OVERLAY), poseStack.last())

    class Batch(private val consumer: VertexConsumer, private val pose: PoseStack.Pose) {
        fun line(start: Vec3f, end: Vec3f, color: Int) {
            val direction = end - start
            if (direction.length() < EPSILON) return

            val normal = direction.normed()
            vertex(start, normal, color)
            vertex(end, normal, color)
        }

        fun bone(start: Vec3f, end: Vec3f, up: Vec3f, color: Int) {
            val along = end - start
            val length = along.length()
            if (length < EPSILON) return

            val axis = along.normed()
            val right = perpendicular(axis, up)
            val forward = axis.cross(right, MutableVec3f()).norm()

            val shoulder = start + axis * (length * BONE_SHOULDER)
            val width = length * BONE_WIDTH
            val corners = listOf(
                shoulder + right * width,
                shoulder + forward * width,
                shoulder - right * width,
                shoulder - forward * width,
            )

            corners.forEachIndexed { index, corner ->
                line(start, corner, color)
                line(corner, end, color)
                line(corner, corners[(index + 1) % corners.size], color)
            }
        }

        fun capsule(start: Vec3f, end: Vec3f, radius: Float, color: Int) {
            val along = end - start
            val axis = if (along.length() < EPSILON) Vec3f.Y_AXIS else along.normed()
            val right = perpendicular(axis, Vec3f.Y_AXIS)
            val forward = axis.cross(right, MutableVec3f()).norm()

            ring(start, axis, radius, color)
            ring(end, axis, radius, color)

            listOf(right, forward, right * -1f, forward * -1f).forEach { side ->
                line(start + side * radius, end + side * radius, color)
            }

            halfCircle(start, axis * -1f, right, radius, color)
            halfCircle(start, axis * -1f, forward, radius, color)
            halfCircle(end, axis, right, radius, color)
            halfCircle(end, axis, forward, radius, color)
        }

        fun ring(centre: Vec3f, normal: Vec3f, radius: Float, color: Int) {
            val right = perpendicular(normal, Vec3f.Y_AXIS)
            val forward = normal.cross(right, MutableVec3f()).norm()
            arc(centre, right, forward, radius, TAU, RING_SEGMENTS, color)
        }

        private fun halfCircle(centre: Vec3f, over: Vec3f, across: Vec3f, radius: Float, color: Int) {
            arc(centre, across, over, radius, TAU / 2f, CAP_SEGMENTS, color)
        }

        private fun arc(
            centre: Vec3f,
            from: Vec3f,
            towards: Vec3f,
            radius: Float,
            sweep: Float,
            segments: Int,
            color: Int,
        ) {
            var previous = centre + from * radius
            (1..segments).forEach { step ->
                val angle = step * sweep / segments
                val point = centre + from * (cos(angle) * radius) + towards * (sin(angle) * radius)
                line(previous, point, color)
                previous = point
            }
        }

        private fun vertex(point: Vec3f, normal: Vec3f, color: Int) {
            consumer.vertex(pose.pose(), point.x, point.y, point.z)
                .color(color)
                .normal(pose.normal(), normal.x, normal.y, normal.z)
        }
    }

    private fun perpendicular(axis: Vec3f, preferred: Vec3f): Vec3f {
        val reference = if (abs(axis dot preferred) < 0.99f) preferred else fallbackFor(axis)
        val projected = reference - axis * (axis dot reference)
        return if (projected.length() > EPSILON) projected.normed() else fallbackFor(axis)
    }

    private fun fallbackFor(axis: Vec3f): Vec3f = if (abs(axis.y) < 0.9f) Vec3f.Y_AXIS else Vec3f.X_AXIS

    private const val EPSILON = 1.0e-6f
    private const val TAU = 2f * Math.PI.toFloat()
    private const val RING_SEGMENTS = 12
    private const val CAP_SEGMENTS = 6

    private const val BONE_SHOULDER = 0.15f
    private const val BONE_WIDTH = 0.08f
}
