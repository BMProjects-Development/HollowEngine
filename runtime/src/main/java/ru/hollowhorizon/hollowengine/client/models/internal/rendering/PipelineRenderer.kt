package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.systems.RenderSystem
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.packedLight
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper
import ru.hollowhorizon.hollowengine.client.models.internal.utils.toFloatBuffer
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.client.utils.InstancingEntityInfo
import ru.hollowhorizon.hollowengine.client.utils.areShadersEnabled
import ru.hollowhorizon.hollowengine.client.utils.instancingEntityInfo
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper

/**
 * Draws one skinned or morphed primitive: [GpuDeformer] writes its vertices for the frame, and the primitive is drawn
 * on its own with the shader of the pass. Unmoving primitives are merged into [StaticGeometry] instead.
 */
class PipelineRenderer(private val primitive: Primitive) {
    private var vao = -1

    private var posBuffer: VboWrapper? = null
    private var norBuffer: VboWrapper? = null
    private var tanBuffer: VboWrapper? = null
    private var uvBuffer: VboWrapper? = null
    private var midBuffer: VboWrapper? = null
    private var indexBuffer: VboWrapper? = null

    private var deformer: GpuDeformer? = null

    fun init() {
        val previousVao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
        val previousArrayBuffer = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING)
        try {
            vao = GL33.glGenVertexArrays()
            GL33.glBindVertexArray(vao)

            initDeformedBuffers()
            deformer = GpuDeformer(primitive).also {
                it.init(dstPos = posBuffer!!.id, dstNor = norBuffer!!.id, dstTan = tanBuffer!!.id)
            }
            GL33.glBindVertexArray(vao)

            initCommonBuffers()
        } finally {
            GL33.glBindVertexArray(previousVao)
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, previousArrayBuffer)
        }
    }

    private fun initDeformedBuffers() {
        val vertexCount = primitive.positionsCount / 3

        posBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(vertexCount * 3 * 4L, GL33.GL_DYNAMIC_COPY)
            GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 0, 0)
            GL33.glEnableVertexAttribArray(0)
        }

        norBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(vertexCount * 3 * 4L, GL33.GL_DYNAMIC_COPY)
            GL33.glVertexAttribPointer(5, 3, GL33.GL_FLOAT, false, 0, 0)
            GL33.glEnableVertexAttribArray(5)
        }

        tanBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(vertexCount * 4 * 4L, GL33.GL_DYNAMIC_COPY)
            bindFloatAttribute(TANGENT_LOCATION, 4)
        }
    }

    private fun initCommonBuffers() {
        primitive.texCoords?.let { uvs ->
            uvBuffer = VboWrapper.createArrayBuffer().apply {
                val data = uvs.toFloatBuffer(2) { v, b -> b.put(v.x).put(v.y) }
                uploadData(data)
                GL33.glVertexAttribPointer(2, 2, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(2)
            }
        }

        primitive.midCoords?.let { midCoords ->
            midBuffer = VboWrapper.createArrayBuffer().apply {
                val data = midCoords.toFloatBuffer(2) { v, b -> b.put(v.x).put(v.y) }
                uploadData(data)
                bindFloatAttribute(MID_TEX_COORD_LOCATION, 2)
            }
        }

        primitive.indices?.let { indices ->
            indexBuffer = VboWrapper.createElementBuffer().apply {
                val buffer = BufferUtils.createIntBuffer(indices.size)
                buffer.put(indices)
                buffer.flip()
                uploadData(buffer)
            }
        }
    }

    private fun bindFloatAttribute(location: Int, size: Int) {
        if (location == -1) return
        GL33.glVertexAttribPointer(location, size, GL33.GL_FLOAT, false, 0, 0)
        GL33.glEnableVertexAttribArray(location)
    }

    fun setupPipeline(pipeline: RenderPipeline, instance: MeshAttachment) {
        val deformer = deformer ?: return
        pipeline.addSkinnable {
            if (instance.isVisible) deformer.compute(instance)
        }
        pipeline.addVAORenderable {
            if (instance.isVisible) renderVAO(instance)
        }
    }

    private fun RenderContext.renderVAO(instance: MeshAttachment) {
        val shader = RenderSystem.getShader() ?: return
        val nodeMatrix = instance.matrix.asMatrix4f()

        instance.material.bind(MaterialBindContext(shader, COLOR_LOCATION))

        val packedLight = instance.material.packedLight(light)
        GL33.glVertexAttribI2i(4, packedLight and FFFF, packedLight shr 16 and FFFF)
        setEntityInfo(instancingEntityInfo)

        RenderSystem.glBindVertexArray(vao)

        indexBuffer?.bind()

        val modelView = Matrix4f(RenderSystem.getModelViewMatrix()).mul(stack.last().pose())
        modelView.mul(nodeMatrix)
        shader.MODEL_VIEW_MATRIX?.set(modelView)
        shader.MODEL_VIEW_MATRIX?.upload()

        shader.getUniform("NormalMat")?.let {
            val modelNormal = Matrix3f(stack.last().normal())
                .mul(normalMatrixOf(nodeMatrix))
            val normal = if (areShadersEnabled) {
                normalMatrixOf(RenderSystem.getModelViewMatrix()).mul(modelNormal)
            } else {
                modelNormal
            }
            it.set(normal)
            it.upload()
        }

        val count = primitive.indices?.size ?: (primitive.positionsCount / 3)
        if (indexBuffer != null) {
            GL33.glDrawElements(GL33.GL_TRIANGLES, count, GL33.GL_UNSIGNED_INT, 0L)
        } else {
            GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, count)
        }

        GL33.glBindVertexArray(0)
    }

    private fun setEntityInfo(info: InstancingEntityInfo) {
        if (ENTITY_LOCATION == -1) return
        GL33.glVertexAttribI3i(ENTITY_LOCATION, info.entity, info.blockEntity, info.item)
    }

    fun destroy() {
        GL33.glDeleteVertexArrays(vao)
        posBuffer?.delete()
        norBuffer?.delete()
        tanBuffer?.delete()
        uvBuffer?.delete()
        midBuffer?.delete()
        indexBuffer?.delete()
        deformer?.destroy()
    }

    private companion object {
        const val FFFF = '￿'.code
        const val COLOR_LOCATION = 1
        val TANGENT_LOCATION = IrisHelper.entityAttributeLocation("at_tangent")
        val MID_TEX_COORD_LOCATION = IrisHelper.entityAttributeLocation("mc_midTexCoord")
        val ENTITY_LOCATION = IrisHelper.entityAttributeLocation("iris_Entity")
    }
}
