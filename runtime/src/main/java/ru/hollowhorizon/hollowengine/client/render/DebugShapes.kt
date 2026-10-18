package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import ru.hollowhorizon.hollowengine.client.utils.color
import ru.hollowhorizon.hollowengine.client.utils.vertex
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

@ClientOnly
object DebugShapes {
    val PANEL: RenderType = shapes("hollowengine:debug_panel_shapes", RenderStateShard.MAIN_TARGET)

    /** Over the world, as the skeleton shown with hitboxes is. */
    val OVERLAY: RenderType = shapes("hollowengine:debug_overlay_shapes", RenderStateShard.ITEM_ENTITY_TARGET)

    private fun shapes(name: String, target: RenderStateShard.OutputStateShard): RenderType = RenderType.create(
        name,
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.TRIANGLES,
        1536,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setOutputState(target)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .createCompositeState(false),
    )

    fun batch(buffers: MultiBufferSource, poseStack: PoseStack, type: RenderType = PANEL): Batch =
        Batch(buffers.getBuffer(type), poseStack.last())

    class Batch(private val consumer: VertexConsumer, private val pose: PoseStack.Pose) {
        /** A bone as the solid octahedron [DebugLines.Batch.bone] outlines. */
        fun bone(start: Vec3f, end: Vec3f, up: Vec3f, color: Int) {
            val corners = DebugLines.boneCorners(start, end, up) ?: return
            corners.forEachIndexed { index, corner ->
                val next = corners[(index + 1) % corners.size]
                triangle(start, corner, next, color)
                triangle(corner, end, next, color)
            }
        }

        /** One face, lit from a fixed light above so the sides of a shape read apart. */
        fun triangle(a: Vec3f, b: Vec3f, c: Vec3f, color: Int) {
            val normal = (b - a).cross(c - a, MutableVec3f())
            val light = if (normal.length() < EPSILON) 1f else AMBIENT + (1f - AMBIENT) * abs(normal.norm() dot LIGHT)
            val shaded = shade(color, light)
            vertex(a, shaded)
            vertex(b, shaded)
            vertex(c, shaded)
        }

        private fun vertex(point: Vec3f, color: Int) {
            consumer.vertex(pose.pose(), point.x, point.y, point.z).color(color)
        }
    }

    private fun shade(color: Int, light: Float): Int {
        fun channel(shift: Int) = (((color shr shift) and 0xFF) * light).toInt().coerceIn(0, 255) shl shift
        return (color and ALPHA_MASK) or channel(16) or channel(8) or channel(0)
    }

    private val LIGHT = Vec3f(0.4f, 1f, 0.6f).normed()
    private const val AMBIENT = 0.55f
    private val ALPHA_MASK = 0xFF000000.toInt()
    private const val EPSILON = 1.0e-6f
}
