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
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wireframe shapes for the debug views: skeletons, physics bodies, colliders.
 */
@ClientOnly
object DebugLines {
    val OVERLAY: RenderType = overlayLines("hollowengine:debug_overlay_lines", RenderStateShard.ITEM_ENTITY_TARGET)
    val PANEL: RenderType = overlayLines("hollowengine:debug_panel_lines", RenderStateShard.MAIN_TARGET)

    private fun overlayLines(name: String, target: RenderStateShard.OutputStateShard) = RenderType.create(
        name,
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
            .setOutputState(target)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .createCompositeState(false),
    )

    fun batch(buffers: MultiBufferSource, poseStack: PoseStack, type: RenderType = OVERLAY): Batch =
        Batch(buffers.getBuffer(type), poseStack.last())

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

        fun box(center: Vec3f, x: Vec3f, y: Vec3f, z: Vec3f, color: Int) {
            val corners = List(CORNERS) { index ->
                center + x * index.sign(X_BIT) + y * index.sign(Y_BIT) + z * index.sign(Z_BIT)
            }
            corners.indices.forEach { from ->
                listOf(X_BIT, Y_BIT, Z_BIT).forEach { bit ->
                    if (from and bit == 0) line(corners[from], corners[from or bit], color)
                }
            }
        }

        fun sphere(center: Vec3f, radius: Float, color: Int) {
            ring(center, Vec3f.X_AXIS, radius, color)
            ring(center, Vec3f.Y_AXIS, radius, color)
            ring(center, Vec3f.Z_AXIS, radius, color)
        }

        private fun Int.sign(bit: Int): Float = if (this and bit == 0) -1f else 1f

        fun ring(center: Vec3f, normal: Vec3f, radius: Float, color: Int) {
            val right = perpendicular(normal, Vec3f.Y_AXIS)
            val forward = normal.cross(right, MutableVec3f()).norm()
            arc(center, right, forward, radius, TAU, RING_SEGMENTS, color)
        }

        fun sector(
            center: Vec3f,
            axis: Vec3f,
            zero: Vec3f,
            from: Float,
            to: Float,
            radius: Float,
            color: Int,
        ) {
            val start = perpendicular(axis, zero)
            val across = axis.cross(start, MutableVec3f()).norm()
            val begin = from * RADIANS
            val sweep = ((to - from) * RADIANS).coerceIn(-TAU, TAU)

            fun at(angle: Float) = center + start * (cos(angle) * radius) + across * (sin(angle) * radius)

            line(center, at(begin), color)
            line(center, at(begin + sweep), color)

            var previous = at(begin)
            (1..SECTOR_SEGMENTS).forEach { step ->
                val point = at(begin + sweep * step / SECTOR_SEGMENTS)
                line(previous, point, color)
                previous = point
            }
        }

        private fun halfCircle(center: Vec3f, over: Vec3f, across: Vec3f, radius: Float, color: Int) {
            arc(center, across, over, radius, TAU / 2f, CAP_SEGMENTS, color)
        }

        private fun arc(
            center: Vec3f,
            from: Vec3f,
            towards: Vec3f,
            radius: Float,
            sweep: Float,
            segments: Int,
            color: Int,
        ) {
            var previous = center + from * radius
            (1..segments).forEach { step ->
                val angle = step * sweep / segments
                val point = center + from * (cos(angle) * radius) + towards * (sin(angle) * radius)
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

    private const val X_BIT = 1
    private const val Y_BIT = 2
    private const val Z_BIT = 4
    private const val CORNERS = 8

    private const val EPSILON = 1.0e-6f
    private const val TAU = 2f * Math.PI.toFloat()
    private const val RING_SEGMENTS = 12
    private const val SECTOR_SEGMENTS = 16
    private const val RADIANS = (Math.PI / 180.0).toFloat()
    private const val CAP_SEGMENTS = 6

    private const val BONE_SHOULDER = 0.15f
    private const val BONE_WIDTH = 0.08f
}
