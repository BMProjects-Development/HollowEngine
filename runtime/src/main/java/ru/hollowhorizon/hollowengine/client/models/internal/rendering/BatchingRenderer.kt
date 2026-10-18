package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.batchingRenderType
import ru.hollowhorizon.hollowengine.client.models.internal.packedLight
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.client.utils.*
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

class BatchingRenderer(
    private val primitive: Primitive
) : MeshRenderer {

    override fun init() {
        // No GL initialization needed for batching
    }

    override fun setupPipeline(
        pipeline: RenderPipeline,
        instance: MeshAttachment,
    ) {
        val indices = primitive.indices
        val positions = primitive.positions
        val normals = primitive.normals
        val texCoords = primitive.texCoords
        val iterator = indices?.asIterable() ?: (0 until primitive.positionsCount / 3)

        pipeline.addBatchedRenderable {
            if (!instance.isVisible) return@addBatchedRenderable
            val posArray = positions ?: return@addBatchedRenderable
            val normArray = normals ?: return@addBatchedRenderable
            val texArray = texCoords ?: return@addBatchedRenderable
            if (posArray.isEmpty() || normArray.isEmpty() || texArray.isEmpty()) return@addBatchedRenderable
            if (indices != null && indices.isEmpty()) return@addBatchedRenderable

            val material = instance.material
            val renderType = batchingRenderType.apply(material)
            openedBatchedRenderTypes?.add(renderType)
            val vertexConsumer = source.getBuffer(renderType)
            val nodeMatrix = instance.matrix.asMatrix4f()
            val pose = Matrix4f(stack.last().pose()).mul(nodeMatrix)
            val normal = Matrix3f(stack.last().normal()).mul(normalMatrixOf(nodeMatrix))
            val transformedNormal = Vector3f()
            val color = material.color

            val packedLight = material.packedLight(light)
            for (i in iterator) {
                putVertex(
                    i, vertexConsumer, pose, normal, transformedNormal, color, overlay, packedLight,
                    posArray, normArray, texArray,
                )
            }
        }
    }

    private fun putVertex(
        index: Int,
        consumer: VertexConsumer,
        pose: Matrix4f,
        normalMat: Matrix3f,
        transformedNormal: Vector3f,
        color: Color,
        overlayCoords: Int,
        packedLight: Int,
        posArray: Array<Vec3f>,
        normArray: Array<Vec3f>,
        texArray: Array<Vec2f>,
    ) {
        if (index !in posArray.indices || index !in normArray.indices || index !in texArray.indices) return

        val position = posArray[index]
        val sourceNormal = normArray[index]
        val normal = normalMat.transform(sourceNormal.x, sourceNormal.y, sourceNormal.z, transformedNormal).normalize()

        consumer
            .vertex(pose, position.x, position.y, position.z)
            .color(color.r, color.g, color.b, color.a)
            .uv(texArray[index].x, texArray[index].y)
            .overlayCoords(overlayCoords)
            .uv2(packedLight)
            .normal(normal.x, normal.y, normal.z)
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
