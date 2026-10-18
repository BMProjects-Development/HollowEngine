package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import com.mojang.blaze3d.vertex.*
import net.minecraft.client.renderer.texture.OverlayTexture
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderPreviewMesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the previews are drawn on, uploaded once and kept for as long as the editor is open. Every
 * mesh faces outward counterclockwise, so with back faces culled a convex one needs no depth buffer.
 */
internal class ShaderPreviewMeshes {
    private val meshes = HashMap<ShaderPreviewMesh, VertexBuffer>()

    /** The vertex format of every mesh; the preview program binds its attributes by these names. */
    val format: VertexFormat get() = DefaultVertexFormat.NEW_ENTITY

    fun of(mesh: ShaderPreviewMesh): VertexBuffer = meshes.getOrPut(mesh) {
        val builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, format)
        when (mesh) {
            ShaderPreviewMesh.QUAD -> quad(builder)
            ShaderPreviewMesh.SPHERE -> sphere(builder)
            ShaderPreviewMesh.CUBE -> cube(builder)
        }
        VertexBuffer(VertexBuffer.Usage.STATIC).apply {
            bind()
            upload(builder.buildOrThrow())
            VertexBuffer.unbind()
        }
    }

    fun release() {
        meshes.values.forEach(VertexBuffer::close)
        meshes.clear()
    }

    /** The whole target, from -1 to 1, facing the camera; drawn without matrices. */
    private fun quad(builder: BufferBuilder) {
        val normal = Vector3f(0f, 0f, 1f)
        face(builder, Vector3f(0f, 0f, 0f), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), normal)
    }

    /** A unit sphere, the seam at the back; u goes around it, v from the bottom to the top. */
    private fun sphere(builder: BufferBuilder) {
        fun point(stack: Int, slice: Int): Vector3f {
            val theta = PI * stack / STACKS
            val phi = 2 * PI * slice / SLICES
            return Vector3f((sin(theta) * sin(phi)).toFloat(), cos(theta).toFloat(), (sin(theta) * cos(phi)).toFloat())
        }
        fun vertex(stack: Int, slice: Int) {
            val p = point(stack, slice)
            val u = slice.toFloat() / SLICES
            val v = 1f - stack.toFloat() / STACKS
            builder.addVertex(p.x, p.y, p.z, WHITE, u, v, OverlayTexture.NO_OVERLAY, FULL_BRIGHT, p.x, p.y, p.z)
        }
        for (stack in 0 until STACKS) {
            for (slice in 0 until SLICES) {
                vertex(stack, slice)
                vertex(stack + 1, slice)
                vertex(stack + 1, slice + 1)
                vertex(stack, slice)
                vertex(stack + 1, slice + 1)
                vertex(stack, slice + 1)
            }
        }
    }

    /**
     * A cube from -1 to 1, with the whole of the UV square on each face; like every mesh here it spans
     * -1 to 1, which is what the position in the object reads, and the camera sizes it to fit.
     */
    private fun cube(builder: BufferBuilder) {
        listOf(
            Triple(Vector3f(0f, 0f, 1f), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f)),
            Triple(Vector3f(0f, 0f, -1f), Vector3f(-1f, 0f, 0f), Vector3f(0f, 1f, 0f)),
            Triple(Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, -1f), Vector3f(0f, 1f, 0f)),
            Triple(Vector3f(-1f, 0f, 0f), Vector3f(0f, 0f, 1f), Vector3f(0f, 1f, 0f)),
            Triple(Vector3f(0f, 1f, 0f), Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, -1f)),
            Triple(Vector3f(0f, -1f, 0f), Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, 1f)),
        ).forEach { (normal, right, up) ->
            face(builder, normal, right, up, normal)
        }
    }

    /** Two triangles around [center], [right] and [up] from it to the edges, counterclockwise seen from [normal]. */
    private fun face(builder: BufferBuilder, center: Vector3f, right: Vector3f, up: Vector3f, normal: Vector3f) {
        fun corner(u: Float, v: Float) {
            val x = center.x + right.x * (u * 2f - 1f) + up.x * (v * 2f - 1f)
            val y = center.y + right.y * (u * 2f - 1f) + up.y * (v * 2f - 1f)
            val z = center.z + right.z * (u * 2f - 1f) + up.z * (v * 2f - 1f)
            builder.addVertex(x, y, z, WHITE, u, v, OverlayTexture.NO_OVERLAY, FULL_BRIGHT, normal.x, normal.y, normal.z)
        }
        corner(0f, 0f)
        corner(1f, 0f)
        corner(1f, 1f)
        corner(0f, 0f)
        corner(1f, 1f)
        corner(0f, 1f)
    }

    private companion object {
        const val STACKS = 24
        const val SLICES = 48
        const val WHITE = -1
        const val FULL_BRIGHT = 0xF000F0
    }
}
