package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import ru.hollowhorizon.hollowengine.client.models.gltf.GltfMesh
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper
import ru.hollowhorizon.hollowengine.client.models.internal.utils.toFloatBuffer
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import kotlin.math.min

private const val MaxMorphTargets = 64

class GpuDeformer(private val primitive: Primitive) {
    private var processingVao = -1

    private var srcPosBuffer: VboWrapper? = null
    private var srcNorBuffer: VboWrapper? = null
    private var srcTanBuffer: VboWrapper? = null

    private var srcJointsBuffer: VboWrapper? = null
    private var srcWeightsBuffer: VboWrapper? = null

    private var morphPosBuffer: VboWrapper? = null
    private var morphPosTexture = -1
    private var morphNorBuffer: VboWrapper? = null
    private var morphNorTexture = -1
    private var morphTanBuffer: VboWrapper? = null
    private var morphTanTexture = -1

    private var jointMatrixBuffer: VboWrapper? = null
    private var jointMatrixTexture = -1

    private var outPosBufferId = -1
    private var outNorBufferId = -1
    private var outTanBufferId = -1

    private var drawCount = 0

    private val activeMorphCount get() = min(primitive.morphTargets.size, MaxMorphTargets)

    fun init(dstPos: Int, dstNor: Int, dstTan: Int) {
        this.outPosBufferId = dstPos
        this.outNorBufferId = dstNor
        this.outTanBufferId = dstTan
        this.drawCount = primitive.positions?.size ?: 0

        processingVao = GL30.glGenVertexArrays()
        GL30.glBindVertexArray(processingVao)

        initSourceAttributes()

        if (primitive.morphTargets.isNotEmpty()) {
            initMorphTextures()
        }

        if (primitive.hasSkinning) {
            initJointMatrixTexture()
        }

        GL30.glBindVertexArray(0)
    }

    private fun initSourceAttributes() {
        primitive.positions?.let { positions ->
            srcPosBuffer = VboWrapper.createArrayBuffer().apply {
                val data = positions.toFloatBuffer(3) { v, b -> b.put(v.x).put(v.y).put(v.z) }
                uploadData(data)
                GL33.glVertexAttribPointer(2, 3, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(2)
            }
        }

        primitive.normals?.let { normals ->
            srcNorBuffer = VboWrapper.createArrayBuffer().apply {
                val data = normals.toFloatBuffer(3) { v, b -> b.put(v.x).put(v.y).put(v.z) }
                uploadData(data)
                GL33.glVertexAttribPointer(3, 3, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(3)
            }
        }

        primitive.tangents?.let { tangents ->
            srcTanBuffer = VboWrapper.createArrayBuffer().apply {
                val data = tangents.toFloatBuffer(4) { v, b -> b.put(v.x).put(v.y).put(v.z).put(v.w) }
                uploadData(data)
                GL33.glVertexAttribPointer(4, 4, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(4)
            }
        }

        if (primitive.hasSkinning) {
            primitive.joints?.let { joints ->
                srcJointsBuffer = VboWrapper.createArrayBuffer().apply {
                    val buffer = BufferUtils.createIntBuffer(joints.size * 4)
                    joints.forEach { j -> buffer.put(j.x).put(j.y).put(j.z).put(j.w) }
                    buffer.flip()
                    uploadData(buffer)
                    GL30.glVertexAttribIPointer(0, 4, GL33.GL_INT, 0, 0)
                    GL33.glEnableVertexAttribArray(0)
                }
            }

            primitive.jointWeights?.let { weights ->
                srcWeightsBuffer = VboWrapper.createArrayBuffer().apply {
                    val data = weights.toFloatBuffer(4) { v, b -> b.put(v.x).put(v.y).put(v.z).put(v.w) }
                    uploadData(data)
                    GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false, 0, 0)
                    GL33.glEnableVertexAttribArray(1)
                }
            }
        }
    }

    private fun initMorphTextures() {
        val morphCount = primitive.morphTargets.size

        val totalElements = drawCount * morphCount

        val posBuffer = BufferUtils.createFloatBuffer(totalElements * 4)
        val norBuffer = BufferUtils.createFloatBuffer(totalElements * 4)
        val tanBuffer = BufferUtils.createFloatBuffer(totalElements * 4)

        for (targetMap in primitive.morphTargets) {
            val posDeltas = targetMap[GltfMesh.Primitive.ATTRIBUTE_POSITION]
            val norDeltas = targetMap[GltfMesh.Primitive.ATTRIBUTE_NORMAL]
            val tanDeltas = targetMap[GltfMesh.Primitive.ATTRIBUTE_TANGENT]

            for (i in 0 until drawCount) {
                if (posDeltas != null) {
                    posBuffer.put(posDeltas[i * 3]).put(posDeltas[i * 3 + 1]).put(posDeltas[i * 3 + 2]).put(0f)
                } else {
                    posBuffer.put(0f).put(0f).put(0f).put(0f)
                }

                if (norDeltas != null) {
                    norBuffer.put(norDeltas[i * 3]).put(norDeltas[i * 3 + 1]).put(norDeltas[i * 3 + 2]).put(0f)
                } else {
                    norBuffer.put(0f).put(0f).put(0f).put(0f)
                }

                if (tanDeltas != null) {
                    tanBuffer.put(tanDeltas[i * 3]).put(tanDeltas[i * 3 + 1]).put(tanDeltas[i * 3 + 2]).put(0f)
                } else {
                    tanBuffer.put(0f).put(0f).put(0f).put(0f)
                }
            }
        }

        posBuffer.flip(); norBuffer.flip(); tanBuffer.flip()

        morphPosBuffer = VboWrapper.createTextureBuffer().apply {
            uploadData(posBuffer)
            morphPosTexture = createTextureFromBuffer(this.id)
        }

        morphNorBuffer = VboWrapper.createTextureBuffer().apply {
            uploadData(norBuffer)
            morphNorTexture = createTextureFromBuffer(this.id)
        }

        morphTanBuffer = VboWrapper.createTextureBuffer().apply {
            uploadData(tanBuffer)
            morphTanTexture = createTextureFromBuffer(this.id)
        }
    }

    private fun initJointMatrixTexture() {
        jointMatrixBuffer = VboWrapper.createTextureBuffer().apply {
            allocate(primitive.jointCount * 64L, GL33.GL_DYNAMIC_DRAW)
            jointMatrixTexture = createTextureFromBuffer(this.id)
        }
    }

    private fun createTextureFromBuffer(bufferId: Int): Int {
        val textureId = GL11.glGenTextures()
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId)
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, bufferId)
        return textureId
    }

    fun compute(instance: MeshAttachment) {
        val shaderId: Int

        if (primitive.hasSkinning) {
            shaderId = HollowModelManager.glProgramSkinning
            GL20.glUseProgram(shaderId)

            updateJointMatrices(instance.skinMatrices())

            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, jointMatrixTexture)
            GL20.glUniform1i(GL20.glGetUniformLocation(shaderId, "jointMatrices"), 0)
        } else {
            shaderId = HollowModelManager.glProgramMorphing
            GL20.glUseProgram(shaderId)
        }

        setupMorphUniforms(shaderId, instance.morphWeights)

        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, outPosBufferId)
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 1, outNorBufferId)
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 2, outTanBufferId)

        GL30.glBeginTransformFeedback(GL11.GL_POINTS)
        GL30.glBindVertexArray(processingVao)
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
        GL11.glDrawArrays(GL11.GL_POINTS, 0, drawCount)
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
        GL30.glEndTransformFeedback()

        GL30.glBindVertexArray(0)
        GL20.glUseProgram(0)

        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0)
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 1, 0)
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 2, 0)
    }

    private fun updateJointMatrices(matrices: Array<Mat4f>) {
        val buffer = BufferUtils.createFloatBuffer(matrices.size * 16)
        for (m in matrices) {
            buffer.put(m.m00).put(m.m01).put(m.m02).put(m.m03)
            buffer.put(m.m10).put(m.m11).put(m.m12).put(m.m13)
            buffer.put(m.m20).put(m.m21).put(m.m22).put(m.m23)
            buffer.put(m.m30).put(m.m31).put(m.m32).put(m.m33)
        }
        buffer.flip()
        jointMatrixBuffer?.bind()
        GL33.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0, buffer)
        jointMatrixBuffer?.unbind()
    }

    private fun setupMorphUniforms(shaderId: Int, morphWeights: FloatArray) {
        if (primitive.morphTargets.isNotEmpty()) {
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, morphPosTexture)
            GL20.glUniform1i(GL20.glGetUniformLocation(shaderId, "morphDeltasPosition"), 1)

            GL13.glActiveTexture(GL13.GL_TEXTURE2)
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, morphNorTexture)
            GL20.glUniform1i(GL20.glGetUniformLocation(shaderId, "morphDeltasNormal"), 2)

            GL13.glActiveTexture(GL13.GL_TEXTURE3)
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, morphTanTexture)
            GL20.glUniform1i(GL20.glGetUniformLocation(shaderId, "morphDeltasTangent"), 3)

            GL20.glUniform1i(GL20.glGetUniformLocation(shaderId, "activeMorphCount"), activeMorphCount)
            GL20.glUniform1i(GL20.glGetUniformLocation(shaderId, "vertexCount"), drawCount)

            uploadMorphWeights(shaderId, morphWeights)
        } else {
            GL20.glUniform1i(GL20.glGetUniformLocation(shaderId, "activeMorphCount"), 0)
        }
    }

    private fun uploadMorphWeights(shaderId: Int, morphWeights: FloatArray) {
        for (index in 0 until min(morphWeights.size, activeMorphCount)) {
            val location = GL20.glGetUniformLocation(shaderId, "morphWeights[$index]")
            if (location == -1) continue
            GL20.glUniform1f(location, morphWeights[index])
        }
    }

    fun destroy() {
        GL30.glDeleteVertexArrays(processingVao)

        srcPosBuffer?.delete()
        srcNorBuffer?.delete()
        srcTanBuffer?.delete()
        srcJointsBuffer?.delete()
        srcWeightsBuffer?.delete()

        morphPosBuffer?.delete()
        morphNorBuffer?.delete()
        morphTanBuffer?.delete()
        jointMatrixBuffer?.delete()

        if (morphPosTexture != -1) GL11.glDeleteTextures(morphPosTexture)
        if (morphNorTexture != -1) GL11.glDeleteTextures(morphNorTexture)
        if (morphTanTexture != -1) GL11.glDeleteTextures(morphTanTexture)
        if (jointMatrixTexture != -1) GL11.glDeleteTextures(jointMatrixTexture)
    }
}
