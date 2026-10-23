package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL31
import ru.hollowhorizon.hollowengine.client.models.internal.SkinInfluence
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper

/** Sparse weights beyond the four primary influences, without discarding painted deformation data. */
internal class AdditionalSkinWeights(private var weights: Array<List<SkinInfluence>>?) {
    private var ranges: VboWrapper? = null
    private var entries: VboWrapper? = null
    private var texture = -1

    /** Called with the deformation VAO bound. */
    fun init() {
        val vertices = weights ?: return
        val rangeData = BufferUtils.createIntBuffer(vertices.size * 2)
        val weightData = BufferUtils.createFloatBuffer(vertices.sumOf { it.size } * 2)
        var offset = 0
        vertices.forEach { influences ->
            rangeData.put(offset).put(influences.size)
            influences.forEach { weightData.put(it.joint.toFloat()).put(it.weight) }
            offset += influences.size
        }
        rangeData.flip()
        weightData.flip()
        ranges = VboWrapper.createArrayBuffer().apply {
            uploadData(rangeData)
            GL31.glVertexAttribIPointer(5, 2, GL31.GL_INT, 0, 0)
            GL31.glEnableVertexAttribArray(5)
        }
        entries = VboWrapper.createTextureBuffer().apply {
            uploadData(weightData)
            texture = GL31.glGenTextures()
            GL31.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture)
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL31.GL_RG32F, id)
        }
        weights = null
    }

    fun bind(shader: Int) {
        GL31.glUniform1i(GL31.glGetUniformLocation(shader, "hasAdditionalWeights"), if (texture == -1) 0 else 1)
        if (texture == -1) return
        GL31.glActiveTexture(GL31.GL_TEXTURE4)
        GL31.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture)
        GL31.glUniform1i(GL31.glGetUniformLocation(shader, "additionalWeights"), 4)
    }

    fun destroy() {
        ranges?.delete()
        entries?.delete()
        if (texture != -1) GL31.glDeleteTextures(texture)
    }
}
