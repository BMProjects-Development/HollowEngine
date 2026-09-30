package ru.hollowhorizon.hollowengine.client.vfx.render

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.renderer.ShaderInstance
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper
import ru.hollowhorizon.hollowengine.common.registry.ModShaders
import java.nio.FloatBuffer
import java.util.*

/**
 * Draws billboard particles, one instanced quad per particle, one draw call per batch.
 *
 * The engine program draws everything but the materials with a shader of their own, and
 * alpha-blended, additive and opaque particles share one draw through premultiplied alpha (see
 * [VfxBatchBlend]). With a shader pack the planes are drawn over the pack's finished image, so the
 * pack never replaces the program.
 */
object VfxQuadRenderer {
    private const val STRIDE = VfxQuadPacker.STRIDE
    private const val STRIDE_BYTES = STRIDE * Float.SIZE_BYTES

    private val packer = VfxQuadPacker()

    private val vaos = IdentityHashMap<ShaderInstance, Int>()
    private var quadBuffer: VboWrapper? = null
    private var indexBuffer: VboWrapper? = null
    private var instanceBuffer: VboWrapper? = null
    private var instanceCapacity = 0
    private var upload: FloatBuffer = BufferUtils.createFloatBuffer(STRIDE * 256)

    /** Frees the GL objects; the next draw builds them again. */
    fun invalidate() {
        vaos.values.forEach(GL33::glDeleteVertexArrays)
        vaos.clear()
        quadBuffer?.delete()
        indexBuffer?.delete()
        instanceBuffer?.delete()
        quadBuffer = null
        indexBuffer = null
        instanceBuffer = null
        instanceCapacity = 0
    }

    /** Draws everything in [draws] as seen from [view]. */
    fun render(draws: List<VfxQuadDraw>, view: VfxView) {
        val total = packer.pack(draws, view)
        if (total == 0) return
        draw(total, view)
    }

    /**
     * Draws the glow of the batches that have one, out of the instances [render] uploaded this frame.
     */
    fun renderGlow(view: VfxView) {
        val engine = ModShaders.VFX_PARTICLE ?: return
        val glowing = packer.batches.filter { it.glows }
        if (glowing.isEmpty()) return
        withInstanceState(upload = 0) {
            GL33.glDepthFunc(GL33.GL_LEQUAL)
            glowing.forEach { batch ->
                val shader = VfxMaterialStates.glowShader(batch.key.shader, engine, VfxSurface.PLANE)
                    ?: return@forEach
                drawBatch(shader, engine, batch, view, glow = true)
            }
        }
    }

    /**
     * The engine program draws every batch but the ones whose material names a shader of its own;
     * those get theirs, falling back to the engine program when it failed to load.
     */
    private fun draw(total: Int, view: VfxView) {
        val engine = ModShaders.VFX_PARTICLE ?: return
        withInstanceState(upload = total) {
            GL33.glDepthFunc(GL33.GL_LEQUAL)

            packer.batches.forEach { batch ->
                val shader = batch.key.shader?.let { VfxShaders.surface(it, VfxSurface.PLANE) } ?: engine
                drawBatch(shader, engine, batch, view, glow = false)
            }
        }
    }

    private fun drawBatch(shader: ShaderInstance, engine: ShaderInstance, batch: VfxQuadBatch, view: VfxView, glow: Boolean) {
        RenderSystem.setShader { shader }
        view.setDefaultUniforms(shader, VertexFormat.Mode.TRIANGLES)
        VfxMaterialStates.bindCommonSamplers(shader, VfxMaterialStates.texture(batch.key.texture))
        if (shader !== engine) batch.uniforms?.apply(shader)
        shader.safeGetUniform("GlowPass").set(if (glow) 1f else 0f)
        shader.apply()
        if (glow) {
            VfxMaterialStates.applyGlow(batch.key)
        } else {
            VfxMaterialStates.apply(batch.key, premultiplied = shader === engine || VfxGraphMaterials.isGraph(batch.key.shader))
        }
        drawInstances(shader, batch)
        shader.clear()
    }

    /** Runs [body] with the quad buffers bound, after uploading [upload] instances when there are any. */
    private inline fun withInstanceState(upload: Int, body: () -> Unit) {
        val previousVao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
        val previousBuffer = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING)
        val previousTexture = GL33.glGetInteger(GL33.GL_ACTIVE_TEXTURE)

        ensureBuffers()
        if (upload > 0) uploadInstances(upload)
        try {
            body()
        } finally {
            VfxMaterialStates.restore()
            GlStateManager._glUseProgram(0)
            RenderSystem.activeTexture(previousTexture)
            RenderSystem.glBindVertexArray(previousVao)
            RenderSystem.glBindBuffer(GL33.GL_ARRAY_BUFFER, previousBuffer)
        }
    }

    private fun drawInstances(shader: ShaderInstance, batch: VfxQuadBatch) {
        RenderSystem.glBindVertexArray(vaoFor(shader))
        instanceBuffer?.bind()
        pointInstances(shader, batch.first.toLong() * STRIDE_BYTES)
        GL33.glDrawElementsInstanced(GL33.GL_TRIANGLES, 6, GL33.GL_UNSIGNED_INT, 0L, batch.count)
    }

    private fun uploadInstances(total: Int) {
        if (instanceCapacity < total || upload.capacity() < total * STRIDE) {
            val capacity = Integer.highestOneBit((total - 1).coerceAtLeast(255)) * 2
            upload = BufferUtils.createFloatBuffer(capacity * STRIDE)
            instanceCapacity = capacity
            instanceBuffer?.bind()
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, capacity.toLong() * STRIDE_BYTES, GL33.GL_STREAM_DRAW)
        }
        upload.clear()
        upload.put(packer.packed, 0, total * STRIDE)
        upload.flip()
        instanceBuffer?.bind()
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0L, upload)
    }

    private fun ensureBuffers() {
        if (quadBuffer != null) return
        RenderSystem.glBindVertexArray(0)

        quadBuffer = VboWrapper.createArrayBuffer().apply {
            val corners = BufferUtils.createFloatBuffer(4 * 5)
            corners.put(-0.5f).put(-0.5f).put(0f).put(0f).put(1f)
            corners.put(0.5f).put(-0.5f).put(0f).put(1f).put(1f)
            corners.put(0.5f).put(0.5f).put(0f).put(1f).put(0f)
            corners.put(-0.5f).put(0.5f).put(0f).put(0f).put(0f)
            corners.flip()
            uploadData(corners)
        }
        indexBuffer = VboWrapper.createElementBuffer().apply {
            val indices = BufferUtils.createIntBuffer(6)
            indices.put(0).put(1).put(2).put(0).put(2).put(3)
            indices.flip()
            uploadData(indices, bindingTarget = GL33.GL_ARRAY_BUFFER)
        }
        instanceBuffer = VboWrapper.createArrayBuffer()
        instanceCapacity = 0
    }

    private fun vaoFor(shader: ShaderInstance): Int = vaos.getOrPut(shader) {
        val vao = GL33.glGenVertexArrays()
        GL33.glBindVertexArray(vao)

        quadBuffer?.bind()
        bindFloats(shader, "Position", 3, 5 * Float.SIZE_BYTES, 0L, divisor = 0)
        bindFloats(shader, "UV0", 2, 5 * Float.SIZE_BYTES, 3L * Float.SIZE_BYTES, divisor = 0)

        instanceBuffer?.bind()
        pointInstances(shader, 0L)

        indexBuffer?.bind()
        vao
    }

    /** Points the per-particle attributes at [base] bytes into the instance buffer. */
    private fun pointInstances(shader: ShaderInstance, base: Long) {
        bindFloats(shader, "InstanceCenter", 3, STRIDE_BYTES, base)
        bindFloats(shader, "InstanceRight", 3, STRIDE_BYTES, base + 3L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceUp", 3, STRIDE_BYTES, base + 6L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceColor", 4, STRIDE_BYTES, base + 9L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceUv", 4, STRIDE_BYTES, base + 13L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceLight", 2, STRIDE_BYTES, base + 17L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceBlend", 1, STRIDE_BYTES, base + 19L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceMaterial", 2, STRIDE_BYTES, base + 20L * Float.SIZE_BYTES)
    }

    private fun bindFloats(
        shader: ShaderInstance,
        name: String,
        size: Int,
        stride: Int,
        offset: Long,
        divisor: Int = 1,
    ) {
        val location = GL33.glGetAttribLocation(shader.id, name)
        if (location == -1) return

        GL33.glVertexAttribPointer(location, size, GL33.GL_FLOAT, false, stride, offset)
        GL33.glEnableVertexAttribArray(location)
        if (divisor != 0) GL33.glVertexAttribDivisor(location, divisor)
    }
}
