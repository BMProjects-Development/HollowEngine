package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix3f
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.client.utils.*
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat3f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
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
            val pose = stack.last().pose()
            val normal = stack.last().normal()
            val color = material.color

            val packedLight = material.packedLight(light)
            for (i in iterator) {
                putVertex(
                    instance, i, vertexConsumer, pose, normal, color, overlay, packedLight,
                    posArray, normArray, texArray,
                )
            }
        }
    }

    private fun putVertex(
        instance: MeshAttachment,
        index: Int,
        consumer: VertexConsumer,
        pose: Matrix4f,
        normalMat: Matrix3f,
        color: Color,
        overlayCoords: Int,
        packedLight: Int,
        posArray: Array<Vec3f>,
        normArray: Array<Vec3f>,
        texArray: Array<Vec2f>,
    ) {
        if (index !in posArray.indices || index !in normArray.indices || index !in texArray.indices) return

        val global = instance.matrix
        val pos = global.transform(posArray[index], 1f, MutableVec3f())
        val normal = global.getUpperLeft(MutableMat3f()).transform(normArray[index], MutableVec3f())

        consumer
            .vertex(pose, pos.x, pos.y, pos.z)
            .color(color.r, color.g, color.b, color.a)
            .uv(texArray[index].x, texArray[index].y)
            .overlayCoords(overlayCoords)
            .uv2(packedLight)
            .normal(normalMat, normal.x, normal.y, normal.z)
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
