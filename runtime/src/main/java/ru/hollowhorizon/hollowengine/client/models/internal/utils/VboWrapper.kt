package ru.hollowhorizon.hollowengine.client.models.internal.utils

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33
import java.nio.FloatBuffer
import java.nio.IntBuffer

class VboWrapper(val id: Int, val target: Int = GL33.GL_ARRAY_BUFFER) {

    fun bind() {
        GL33.glBindBuffer(target, id)
    }

    fun unbind() {
        GL33.glBindBuffer(target, 0)
    }

    /**
     * Загружает данные в буфер (для статических данных)
     */
    fun uploadData(data: FloatBuffer, usage: Int = GL33.GL_STATIC_DRAW) {
        bind()
        GL33.glBufferData(target, data, usage)
    }

    fun uploadData(data: IntBuffer, usage: Int = GL33.GL_STATIC_DRAW) {
        uploadData(data, usage, target)
    }

    /** [bindingTarget] может загружать данные индекса через GL_ARRAY_BUFFER до того, как VAO будет создан */
    fun uploadData(data: IntBuffer, usage: Int = GL33.GL_STATIC_DRAW, bindingTarget: Int) {
        GL33.glBindBuffer(bindingTarget, id)
        GL33.glBufferData(bindingTarget, data, usage)
    }

    /**
     * Выделяет память без загрузки данных (для Transform Feedback output)
     */
    fun allocate(sizeBytes: Long, usage: Int = GL33.GL_DYNAMIC_COPY) {
        bind()
        GL33.glBufferData(target, sizeBytes, usage)
    }

    fun delete() {
        GL33.glDeleteBuffers(id)
    }

    companion object {
        fun createArrayBuffer() = VboWrapper(GL33.glGenBuffers(), GL33.GL_ARRAY_BUFFER)
        fun createElementBuffer() = VboWrapper(GL33.glGenBuffers(), GL33.GL_ELEMENT_ARRAY_BUFFER)
        fun createTextureBuffer() = VboWrapper(GL33.glGenBuffers(), GL31.GL_TEXTURE_BUFFER)
    }
}

fun <T> Array<T>.toFloatBuffer(elementsPerVertex: Int, putData: (T, FloatBuffer) -> Unit): FloatBuffer {
    val buffer = BufferUtils.createFloatBuffer(this.size * elementsPerVertex)
    for (item in this) {
        putData(item, buffer)
    }
    buffer.flip()
    return buffer
}
