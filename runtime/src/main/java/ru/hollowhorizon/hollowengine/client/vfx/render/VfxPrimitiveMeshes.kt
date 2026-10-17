package ru.hollowhorizon.hollowengine.client.vfx.render

import ru.hollowhorizon.hollowengine.client.vfx.render.VfxPrimitiveGeometry.Companion.FLOATS
import ru.hollowhorizon.hollowengine.common.vfx.VfxPrimitive
import ru.hollowhorizon.hollowengine.common.vfx.VfxPrimitiveKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A built-in mesh on the CPU, one block across: [FLOATS] floats per vertex (position, normal,
 * texture coordinate) and counter-clockwise triangles seen from outside.
 */
class VfxPrimitiveGeometry(val vertices: FloatArray, val indices: IntArray) {
    companion object {
        const val FLOATS = 8
    }
}

/** The geometry of every built-in mesh an effect has asked for, built once. */
object VfxPrimitiveMeshes {
    private val built = HashMap<VfxPrimitive, VfxPrimitiveGeometry>()

    @Synchronized
    fun of(primitive: VfxPrimitive): VfxPrimitiveGeometry = built.getOrPut(primitive) {
        when (primitive.kind) {
            VfxPrimitiveKind.CUBE -> cube()
            VfxPrimitiveKind.SPHERE -> sphere(primitive.segments)
            VfxPrimitiveKind.CYLINDER -> cylinder(primitive.segments, primitive.caps)
        }
    }

    private class Builder {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()
        var count = 0

        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): Int {
            vertices += x; vertices += y; vertices += z
            vertices += nx; vertices += ny; vertices += nz
            vertices += u; vertices += v
            return count++
        }

        fun triangle(a: Int, b: Int, c: Int) {
            indices += a; indices += b; indices += c
        }

        fun build() = VfxPrimitiveGeometry(vertices.toFloatArray(), indices.toIntArray())
    }

    /** Six faces, each with the whole texture; the two edge axes of a face cross into its normal. */
    private fun cube(): VfxPrimitiveGeometry {
        val builder = Builder()
        val faces = listOf(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
        )
        faces.forEach { face ->
            val first = builder.count
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                builder.vertex(
                    (face[0] + face[3] * a + face[6] * b) * 0.5f,
                    (face[1] + face[4] * a + face[7] * b) * 0.5f,
                    (face[2] + face[5] * a + face[8] * b) * 0.5f,
                    face[0], face[1], face[2],
                    (a + 1f) * 0.5f, 1f - (b + 1f) * 0.5f,
                )
            }
            builder.triangle(first, first + 1, first + 2)
            builder.triangle(first, first + 2, first + 3)
        }
        return builder.build()
    }

    private fun sphere(segments: Int): VfxPrimitiveGeometry {
        val builder = Builder()
        val rings = (segments / 2).coerceAtLeast(2)
        for (ring in 0..rings) {
            val phi = PI.toFloat() * ring / rings
            for (slice in 0..segments) {
                val theta = TAU * slice / segments
                val nx = sin(phi) * cos(theta)
                val ny = cos(phi)
                val nz = sin(phi) * sin(theta)
                val u = slice.toFloat() / segments
                builder.vertex(nx * 0.5f, ny * 0.5f, nz * 0.5f, nx, ny, nz, u, ring.toFloat() / rings)
            }
        }
        val row = segments + 1
        for (ring in 0 until rings) {
            for (slice in 0 until segments) {
                val a = ring * row + slice
                val b = a + row
                builder.triangle(a, a + 1, b)
                builder.triangle(a + 1, b + 1, b)
            }
        }
        return builder.build()
    }

    private fun cylinder(segments: Int, caps: Boolean): VfxPrimitiveGeometry {
        val builder = Builder()
        for (slice in 0..segments) {
            val theta = TAU * slice / segments
            val nx = cos(theta)
            val nz = sin(theta)
            val u = slice.toFloat() / segments
            builder.vertex(nx * 0.5f, 0.5f, nz * 0.5f, nx, 0f, nz, u, 0f)
            builder.vertex(nx * 0.5f, -0.5f, nz * 0.5f, nx, 0f, nz, u, 1f)
        }
        for (slice in 0 until segments) {
            val top = slice * 2
            val bottom = top + 1
            builder.triangle(top, top + 2, bottom)
            builder.triangle(top + 2, bottom + 2, bottom)
        }
        if (caps) {
            cap(builder, segments, 0.5f)
            cap(builder, segments, -0.5f)
        }
        return builder.build()
    }

    private fun cap(builder: Builder, segments: Int, y: Float) {
        val up = if (y > 0f) 1f else -1f
        val center = builder.vertex(0f, y, 0f, 0f, up, 0f, 0.5f, 0.5f)
        val first = builder.count
        for (slice in 0..segments) {
            val theta = TAU * slice / segments
            builder.vertex(
                cos(theta) * 0.5f, y, sin(theta) * 0.5f,
                0f, up, 0f,
                0.5f + cos(theta) * 0.5f, 0.5f + sin(theta) * 0.5f,
            )
        }
        for (slice in 0 until segments) {
            val current = first + slice
            if (up > 0f) builder.triangle(center, current + 1, current) else builder.triangle(center, current, current + 1)
        }
    }

    private val TAU = (PI * 2.0).toFloat()
}
