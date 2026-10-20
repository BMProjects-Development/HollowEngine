package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The per-instance data of an instanced draw of [StaticGeometry], shared by every draw and filled again for each.
 *
 * The matrices go to a buffer texture. Each instance starts with [HEADER_TEXELS] texels, the four columns of its
 * model-view matrix and the three of its normal matrix, followed by [SLOT_TEXELS] texels per slot, the matrix of the
 * slot's node; the vertex shader puts the two together. Overlay, light and entity ids go to plain instanced attributes.
 */
internal object StaticInstanceData {
    const val HEADER_TEXELS = 7
    const val SLOT_TEXELS = 4
    private const val HEADER_FLOATS = HEADER_TEXELS * 4
    private const val INTS_PER_INSTANCE = 7
    private const val ATTRIBUTE_STRIDE = INTS_PER_INSTANCE * Int.SIZE_BYTES

    private val transforms by lazy { VboWrapper.createTextureBuffer() }
    private val attributes by lazy { VboWrapper.createArrayBuffer() }
    private val transformTexture by lazy { GL33.glGenTextures() }

    /** The last texture unit: Iris hands out units from the first, so a shader pack never takes this one. */
    private val transformUnit by lazy { GL33.glGetInteger(GL33.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) - 1 }

    private var floats: FloatBuffer = BufferUtils.createFloatBuffer(HEADER_FLOATS * 64)
    private var ints: IntBuffer = BufferUtils.createIntBuffer(INTS_PER_INSTANCE * 16)
    private val normal = Matrix3f()

    fun upload(part: StaticPart, instances: List<StaticInstance>, layout: InstancedShaderLayoutMode) {
        val slotFloats = part.slots.size * StaticInstance.MATRIX_FLOATS
        floats = ensure(floats, instances.size * (HEADER_FLOATS + slotFloats))
        ints = ensure(ints, instances.size * INTS_PER_INSTANCE)

        instances.forEach { instance ->
            instance.modelView.get(floats)
            floats.position(floats.position() + StaticInstance.MATRIX_FLOATS)
            instance.baseNormal(layout, normal)
            floats.put(normal.m00()).put(normal.m01()).put(normal.m02()).put(0f)
            floats.put(normal.m10()).put(normal.m11()).put(normal.m12()).put(0f)
            floats.put(normal.m20()).put(normal.m21()).put(normal.m22()).put(0f)
            floats.put(instance.slotMatrices, 0, slotFloats)
            ints.put(instance.overlay and FFFF).put(instance.overlay shr 16 and FFFF)
            ints.put(instance.light and FFFF).put(instance.light shr 16 and FFFF)
            ints.put(instance.entityInfo.entity).put(instance.entityInfo.blockEntity).put(instance.entityInfo.item)
        }
        floats.flip()
        ints.flip()

        GL33.glBindBuffer(GL33.GL_TEXTURE_BUFFER, transforms.id)
        GL33.glBufferData(GL33.GL_TEXTURE_BUFFER, floats, GL33.GL_STREAM_DRAW)
        GL33.glBindBuffer(GL33.GL_TEXTURE_BUFFER, 0)
        attributes.bind()
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER, ints, GL33.GL_STREAM_DRAW)
    }

    /** Points the instanced attributes [shader] declares at the shared buffer; called while a vertex array is built. */
    fun pointInstanceAttributes(shader: ShaderInstance) {
        attributes.bind()
        pointInts(firstAttributeLocation(shader, "InstanceUV1", "iris_UV1"), 2, 0)
        pointInts(firstAttributeLocation(shader, "InstanceUV2", "iris_UV2"), 2, 2)
        pointInts(GL33.glGetAttribLocation(shader.id, "iris_Entity"), 3, 4)
        pointInts(GL33.glGetAttribLocation(shader.id, "mc_Entity"), 3, 4)
    }

    /** Binds the matrices for the draw and tells the shader's sampler at [location] where they are. */
    fun bindTransforms(location: Int) {
        val previous = GlStateManager._getActiveTexture()
        GL33.glActiveTexture(GL33.GL_TEXTURE0 + transformUnit)
        GL33.glBindTexture(GL33.GL_TEXTURE_BUFFER, transformTexture)
        GL33.glTexBuffer(GL33.GL_TEXTURE_BUFFER, GL33.GL_RGBA32F, transforms.id)
        GL33.glActiveTexture(previous)
        if (location != -1) GL33.glUniform1i(location, transformUnit)
    }

    fun unbindTransforms() {
        val previous = GlStateManager._getActiveTexture()
        GL33.glActiveTexture(GL33.GL_TEXTURE0 + transformUnit)
        GL33.glBindTexture(GL33.GL_TEXTURE_BUFFER, 0)
        GL33.glActiveTexture(previous)
    }

    private fun pointInts(location: Int, size: Int, offset: Int) {
        if (location == -1) return
        GL33.glVertexAttribIPointer(location, size, GL33.GL_INT, ATTRIBUTE_STRIDE, offset * Int.SIZE_BYTES.toLong())
        GL33.glEnableVertexAttribArray(location)
        GL33.glVertexAttribDivisor(location, 1)
    }

    private fun ensure(buffer: FloatBuffer, size: Int): FloatBuffer =
        if (buffer.capacity() >= size) buffer.clear() else BufferUtils.createFloatBuffer(Integer.highestOneBit(size) * 2)

    private fun ensure(buffer: IntBuffer, size: Int): IntBuffer =
        if (buffer.capacity() >= size) buffer.clear() else BufferUtils.createIntBuffer(Integer.highestOneBit(size) * 2)

    private const val FFFF = '￿'.code
}
