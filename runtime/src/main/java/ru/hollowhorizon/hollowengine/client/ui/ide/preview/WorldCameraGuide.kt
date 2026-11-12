package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Quaternionf
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.max

internal data class WorldCameraGuide(val pose: CameraPose, val aspect: Float, val path: List<Vec3f>)

internal fun drawWorldCameraGuides(camera: Camera, projection: Matrix4f, guides: List<WorldCameraGuide>) {
    if (guides.isEmpty()) return
    val minecraft = Minecraft.getInstance()
    val modelView = RenderSystem.getModelViewStack()
    val previousProjection = Matrix4f(RenderSystem.getProjectionMatrix())
    val previousSorting = RenderSystem.getVertexSorting()
    val buffers = minecraft.renderBuffers().bufferSource()
    val cameraPosition = camera.position
    fun relative(point: Vec3f) = Vec3f(
        (point.x - cameraPosition.x).toFloat(),
        (point.y - cameraPosition.y).toFloat(),
        (point.z - cameraPosition.z).toFloat(),
    )

    modelView.pushMatrix().set(Matrix4f().rotation(camera.rotation().conjugate(Quaternionf())))
    RenderSystem.applyModelViewMatrix()
    RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN)
    try {
        minecraft.mainRenderTarget.bindWrite(true)
        val lines = DebugLines.batch(buffers, PoseStack(), DebugLines.PANEL_DEPTH)
        guides.forEach { (pose, aspect, path) ->
            for (index in 1 until path.size) {
                lines.line(relative(path[index - 1]), relative(path[index]), PATH_COLOR)
            }
            val center = relative(pose.position)
            val halfHeight = pose.halfVerticalExtent
            val depth = GUIDE_SIZE / max(1f, halfHeight * max(1f, aspect))
            val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (x, y) ->
                center + pose.direction(x * halfHeight * aspect, y * halfHeight, -1f) * depth
            }
            corners.indices.forEach { index ->
                lines.line(center, corners[index], CAMERA_COLOR)
                lines.line(corners[index], corners[(index + 1) % corners.size], CAMERA_COLOR)
            }
        }
        buffers.endBatch(DebugLines.PANEL_DEPTH)
    } finally {
        modelView.popMatrix()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.setProjectionMatrix(previousProjection, previousSorting)
    }
}

private const val GUIDE_SIZE = 0.75f
private const val PATH_COLOR = 0xDDFFBD63.toInt()
private const val CAMERA_COLOR = 0xFFFFDF99.toInt()
