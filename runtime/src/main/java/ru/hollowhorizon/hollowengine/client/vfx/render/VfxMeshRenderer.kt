package ru.hollowhorizon.hollowengine.client.vfx.render

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper
import ru.hollowhorizon.hollowengine.client.vfx.VfxParticleLook
import ru.hollowhorizon.hollowengine.common.registry.ModShaders
import ru.hollowhorizon.hollowengine.common.vfx.VfxLighting
import ru.hollowhorizon.hollowengine.common.vfx.VfxPrimitive
import java.nio.FloatBuffer

/**
 * Draws the built-in meshes, one instance per particle, one draw call per mesh and material.
 *
 * An instance carries its whole transform, so a mesh needs nothing per draw but its material.
 */
object VfxMeshRenderer {
    /** Floats per instance: three axes and an origin, color, texture window, light. */
    private const val STRIDE = 22
    private const val STRIDE_BYTES = STRIDE * Float.SIZE_BYTES
    private const val VERTEX_BYTES = VfxPrimitiveGeometry.FLOATS * Float.SIZE_BYTES

    private class GpuMesh(val vertices: VboWrapper, val indices: VboWrapper, val indexCount: Int)

    private class Batch(val primitive: VfxPrimitive, val key: VfxBatchKey, val shaded: Boolean) {
        val draws = ArrayList<VfxMeshDraw>()
        var first = 0
        var count = 0
        var depth = 0f
        val sorted: Boolean get() = key.blend == VfxBatchBlend.BLEND
    }

    private val meshes = HashMap<VfxPrimitive, GpuMesh>()
    private val vaos = HashMap<Pair<ShaderInstance, VfxPrimitive>, Int>()
    private var instanceBuffer: VboWrapper? = null
    private var instanceCapacity = 0
    private var packed = FloatArray(STRIDE * 64)
    private var upload: FloatBuffer = BufferUtils.createFloatBuffer(STRIDE * 64)

    private val batches = LinkedHashMap<Pair<VfxPrimitive, VfxBatchKey>, Batch>()

    /** The batches of the last [render], in the order they were drawn; the glow pass draws them again. */
    private var drawn: List<Batch> = emptyList()
    private val matrix = Matrix4f()
    private val rotation = Quaternionf()

    /** Frees the GL objects; the next draw builds them again. */
    fun invalidate() {
        vaos.values.forEach(GL33::glDeleteVertexArrays)
        vaos.clear()
        meshes.values.forEach {
            it.vertices.delete()
            it.indices.delete()
        }
        meshes.clear()
        instanceBuffer?.delete()
        instanceBuffer = null
        instanceCapacity = 0
    }

    fun render(draws: List<VfxMeshDraw>, view: VfxView) {
        drawn = emptyList()
        if (draws.isEmpty()) return
        val total = pack(draws, view)
        if (total == 0) return

        val order = batches.values.filter { !it.sorted } +
            batches.values.filter { it.sorted }.sortedByDescending { it.depth }
        drawn = order
        uploadInstances(total)
        drawInstanced(order, view, glow = false)
    }

    /** Draws the glow of the batches that have one, out of the instances [render] uploaded this frame. */
    fun renderGlow(view: VfxView) {
        val glowing = drawn.filter { it.key.glow > 0f }
        if (glowing.isNotEmpty()) drawInstanced(glowing, view, glow = true)
    }

    private fun pack(draws: List<VfxMeshDraw>, view: VfxView): Int {
        batches.clear()
        draws.forEach { draw ->
            val key = VfxBatchKey.of(draw.spec.material, mixed = false, owner = draw)
            batches.getOrPut(draw.primitive to key) {
                Batch(draw.primitive, key, draw.spec.material.lighting == VfxLighting.WORLD)
            }.draws += draw
        }

        var total = 0
        batches.values.forEach { batch ->
            batch.count = batch.draws.sumOf { it.batch.particles.count }
            total += batch.count
        }
        if (packed.size < total * STRIDE) packed = FloatArray(Integer.highestOneBit(total.coerceAtLeast(1)) * 2 * STRIDE)

        var cursor = 0
        batches.values.forEach { batch ->
            batch.first = cursor
            var depthSum = 0f
            batch.draws.forEach { draw ->
                for (slot in 0 until draw.batch.particles.count) {
                    write(cursor * STRIDE, draw, slot)
                    depthSum += distanceSquared(cursor * STRIDE, view)
                    cursor++
                }
            }
            batch.depth = if (batch.count > 0) depthSum / batch.count else 0f
            if (batch.sorted) sortBackToFront(batch, view)
        }
        return total
    }

    private fun write(at: Int, draw: VfxMeshDraw, slot: Int) {
        val batch = draw.batch
        val particles = batch.particles
        val material = draw.spec.material
        val look = batch.look
        val size = batch.lookOf(slot) + VfxParticleLook.SIZE
        val spin = batch.lookOf(slot) + VfxParticleLook.ROTATION
        val color = batch.lookOf(slot) + VfxParticleLook.COLOR

        matrix.set(batch.matrix).translate(
            particles.positionX[slot] + batch.offset.x,
            particles.positionY[slot] + batch.offset.y,
            particles.positionZ[slot] + batch.offset.z,
        )
        if (draw.spec.alignToVelocity) {
            matrix.rotate(
                VfxBillboards.facing(particles.velocityX[slot], particles.velocityY[slot], particles.velocityZ[slot])
            )
        }
        matrix.rotate(
            rotation.identity().rotateZYX(
                (look[spin + 2] + batch.spin.z) * Mth.DEG_TO_RAD,
                (look[spin + 1] + batch.spin.y) * Mth.DEG_TO_RAD,
                (look[spin] + batch.spin.x) * Mth.DEG_TO_RAD,
            )
        )
        matrix.scale(
            look[size] * batch.sizeScale.x,
            look[size + 1] * batch.sizeScale.y,
            look[size + 2] * batch.sizeScale.z,
        )

        val region = material.uv
        val cellWidth = region.width / batch.uvColumns
        val cellHeight = region.height / batch.uvRows
        val frame = particles.frame[slot].toInt().coerceIn(0, batch.uvColumns * batch.uvRows - 1)
        val light = particles.light[slot]
        val tint = batch.tint

        packed[at] = matrix.m00(); packed[at + 1] = matrix.m01(); packed[at + 2] = matrix.m02()
        packed[at + 3] = matrix.m10(); packed[at + 4] = matrix.m11(); packed[at + 5] = matrix.m12()
        packed[at + 6] = matrix.m20(); packed[at + 7] = matrix.m21(); packed[at + 8] = matrix.m22()
        packed[at + 9] = matrix.m30(); packed[at + 10] = matrix.m31(); packed[at + 11] = matrix.m32()
        packed[at + 12] = look[color] * tint[0]
        packed[at + 13] = look[color + 1] * tint[1]
        packed[at + 14] = look[color + 2] * tint[2]
        packed[at + 15] = look[color + 3] * tint[3]
        packed[at + 16] = region.u0 + (frame % batch.uvColumns) * cellWidth
        packed[at + 17] = region.v0 + (frame / batch.uvColumns) * cellHeight
        packed[at + 18] = cellWidth
        packed[at + 19] = cellHeight
        packed[at + 20] = (light and 0xFFFF).toFloat()
        packed[at + 21] = (light shr 16 and 0xFFFF).toFloat()
    }

    private fun distanceSquared(at: Int, view: VfxView): Float {
        val dx = packed[at + 9] - view.eye.x
        val dy = packed[at + 10] - view.eye.y
        val dz = packed[at + 11] - view.eye.z
        return dx * dx + dy * dy + dz * dz
    }

    /** Few meshes are alpha blended at once, so a plain sort of the instances is enough. */
    private fun sortBackToFront(batch: Batch, view: VfxView) {
        val start = batch.first
        val items = (0 until batch.count).map { index ->
            val at = (start + index) * STRIDE
            distanceSquared(at, view) to packed.copyOfRange(at, at + STRIDE)
        }.sortedByDescending { it.first }
        items.forEachIndexed { index, (_, data) -> System.arraycopy(data, 0, packed, (start + index) * STRIDE, STRIDE) }
    }

    private fun drawInstanced(order: List<Batch>, view: VfxView, glow: Boolean) {
        val engine = ModShaders.VFX_MESH ?: return
        val previousVao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
        val previousBuffer = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING)
        try {
            GL33.glDepthFunc(GL33.GL_LEQUAL)
            order.forEach { batch ->
                val shader = if (glow) {
                    VfxMaterialStates.glowShader(batch.key.shader, engine, VfxSurface.MESH) ?: return@forEach
                } else {
                    batch.key.shader?.let { VfxShaders.surface(it, VfxSurface.MESH) } ?: engine
                }
                val mesh = gpuMesh(batch.primitive)
                view.setDefaultUniforms(shader, VertexFormat.Mode.TRIANGLES)
                VfxMaterialStates.bindCommonSamplers(shader, VfxMaterialStates.texture(batch.key.texture))
                shader.safeGetUniform("Shaded").set(if (batch.shaded) 1f else 0f)
                shader.safeGetUniform("BlendMode").set(VfxQuadPacker.blendMode(batch.draws.first().spec.material.blend))
                shader.safeGetUniform("Softness").set(batch.key.softness)
                shader.safeGetUniform("Glow").set(batch.key.glow)
                shader.safeGetUniform("GlowPass").set(if (glow) 1f else 0f)
                if (shader !== engine) batch.draws.first().uniforms?.apply(shader)
                shader.apply()
                if (glow) VfxMaterialStates.applyGlow(batch.key) else VfxMaterialStates.apply(batch.key, premultiplied = false)

                RenderSystem.glBindVertexArray(vaoFor(shader, batch.primitive, mesh))
                instanceBuffer?.bind()
                pointInstances(shader, batch.first.toLong() * STRIDE_BYTES)
                GL33.glDrawElementsInstanced(GL33.GL_TRIANGLES, mesh.indexCount, GL33.GL_UNSIGNED_INT, 0L, batch.count)
                shader.clear()
            }
        } finally {
            VfxMaterialStates.restore()
            GlStateManager._glUseProgram(0)
            RenderSystem.glBindVertexArray(previousVao)
            RenderSystem.glBindBuffer(GL33.GL_ARRAY_BUFFER, previousBuffer)
        }
    }

    private fun uploadInstances(total: Int) {
        val buffer = instanceBuffer ?: VboWrapper.createArrayBuffer().also { instanceBuffer = it }
        if (instanceCapacity < total || upload.capacity() < total * STRIDE) {
            val capacity = Integer.highestOneBit((total - 1).coerceAtLeast(63)) * 2
            upload = BufferUtils.createFloatBuffer(capacity * STRIDE)
            instanceCapacity = capacity
            buffer.bind()
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, capacity.toLong() * STRIDE_BYTES, GL33.GL_STREAM_DRAW)
        }
        upload.clear()
        upload.put(packed, 0, total * STRIDE)
        upload.flip()
        buffer.bind()
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0L, upload)
    }

    private fun gpuMesh(primitive: VfxPrimitive): GpuMesh = meshes.getOrPut(primitive) {
        RenderSystem.glBindVertexArray(0)
        val geometry = VfxPrimitiveMeshes.of(primitive)
        val vertices = VboWrapper.createArrayBuffer().apply {
            val data = BufferUtils.createFloatBuffer(geometry.vertices.size)
            data.put(geometry.vertices).flip()
            uploadData(data)
        }
        val indices = VboWrapper.createElementBuffer().apply {
            val data = BufferUtils.createIntBuffer(geometry.indices.size)
            data.put(geometry.indices).flip()
            uploadData(data, bindingTarget = GL33.GL_ARRAY_BUFFER)
        }
        GpuMesh(vertices, indices, geometry.indices.size)
    }

    private fun vaoFor(shader: ShaderInstance, primitive: VfxPrimitive, mesh: GpuMesh): Int =
        vaos.getOrPut(shader to primitive) {
            val vao = GL33.glGenVertexArrays()
            GL33.glBindVertexArray(vao)
            mesh.vertices.bind()
            bindFloats(shader, "Position", 3, VERTEX_BYTES, 0L, divisor = 0)
            bindFloats(shader, "Normal", 3, VERTEX_BYTES, 3L * Float.SIZE_BYTES, divisor = 0)
            bindFloats(shader, "UV0", 2, VERTEX_BYTES, 6L * Float.SIZE_BYTES, divisor = 0)
            instanceBuffer?.bind()
            pointInstances(shader, 0L)
            mesh.indices.bind()
            vao
        }

    private fun pointInstances(shader: ShaderInstance, base: Long) {
        bindFloats(shader, "InstanceX", 3, STRIDE_BYTES, base)
        bindFloats(shader, "InstanceY", 3, STRIDE_BYTES, base + 3L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceZ", 3, STRIDE_BYTES, base + 6L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceOrigin", 3, STRIDE_BYTES, base + 9L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceColor", 4, STRIDE_BYTES, base + 12L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceUv", 4, STRIDE_BYTES, base + 16L * Float.SIZE_BYTES)
        bindFloats(shader, "InstanceLight", 2, STRIDE_BYTES, base + 20L * Float.SIZE_BYTES)
    }

    private fun bindFloats(shader: ShaderInstance, name: String, size: Int, stride: Int, offset: Long, divisor: Int = 1) {
        val location = GL33.glGetAttribLocation(shader.id, name)
        if (location == -1) return
        GL33.glVertexAttribPointer(location, size, GL33.GL_FLOAT, false, stride, offset)
        GL33.glEnableVertexAttribArray(location)
        GL33.glVertexAttribDivisor(location, divisor)
    }
}
