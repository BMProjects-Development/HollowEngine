package ru.hollowhorizon.hollowengine.client.models.internal.extrusion

import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.TexturedMeshSurface
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** Clips the original triangles to occupied texels and closes the exposed contour. */
internal object ExtrusionMesh {
    private const val MAX_TEXEL_TESTS = 262_144
    private const val MAX_POLYGON_VERTICES = 262_144
    private const val MIN_UV_AREA = 1e-8f
    private const val JOIN_PRECISION = 1_000_000.0
    const val MITER_LIMIT = 4f

    /** Null means the surface cannot be replaced. An empty result means it was fully transparent. */
    fun build(source: Primitive, mask: TexelMask, thickness: Float): Primitive? {
        if (thickness == 0f) return null
        val surface = source.texturedSurface ?: return null
        val elements = source.indices ?: IntArray(surface.positions.size) { it }
        if (elements.size % 3 != 0) return null
        val faces = triangles(surface, elements, mask) ?: return null
        val vertexBudget = MAX_POLYGON_VERTICES / (1 + source.morphTargets.size)
        val output = ExtrusionVertices(source, surface, extendsOutward = thickness > 0f)
        if (CuboidExtrusion.emit(faces, surface.positions, mask, thickness, output, vertexBudget)) {
            return output.build()
        }
        val offsets = offsets(faces, surface.positions)
        val edges = HashMap<Edge, MutableList<Boundary>>()
        var tests = 0L
        var vertexCount = 0
        for (face in faces) {
            val minX = floor(face.uv.minOf(Vec2f::x)).toInt()
            val minY = floor(face.uv.minOf(Vec2f::y)).toInt()
            val maxX = ceil(face.uv.maxOf(Vec2f::x)).toInt()
            val maxY = ceil(face.uv.maxOf(Vec2f::y)).toInt()
            val columns = maxX.toLong() - minX
            val rows = maxY.toLong() - minY
            if (columns !in 1L..MAX_TEXEL_TESTS.toLong() || rows !in 1L..MAX_TEXEL_TESTS.toLong()) return null
            tests += columns * rows
            if (tests > MAX_TEXEL_TESTS) return null

            for (y in minY until maxY) for (x in minX until maxX) {
                if (!mask[x, y]) continue
                val polygon = face.clip(x, y)
                if (polygon.size < 3) continue
                val vertices = polygon.map { point ->
                    val weights = face.weightsAt(point)
                    val base = face.interpolate(weights) { surface.positions[it] }
                    val offset = face.interpolate(weights) { offsets.getValue(Point(surface.positions[it])) }
                    Vertex(base, base + offset * thickness, weights, face)
                }
                vertexCount += vertices.size
                if (vertexCount > vertexBudget) return null
                val uv = Vec2f((x + 0.5f) / mask.width, (y + 0.5f) / mask.height)
                output.cap(vertices, uv, outer = true)
                output.cap(vertices, uv, outer = false)
                for (i in vertices.indices) {
                    val start = vertices[i]
                    val end = vertices[(i + 1) % vertices.size]
                    val from = Point(start.base)
                    val to = Point(end.base)
                    if (from == to) continue
                    val edge = if (from < to) Edge(from, to) else Edge(to, from)
                    val boundaries = edges.getOrPut(edge, ::ArrayList)
                    val opposite = boundaries.indexOfFirst { it.from != from }
                    if (opposite >= 0) boundaries.removeAt(opposite)
                    else boundaries.add(Boundary(from, start, end, uv))
                }
            }
        }
        edges.values.forEach { boundaries ->
            boundaries.forEach { output.wall(it.start, it.end, it.uv) }
        }
        return output.build()
    }

    private fun triangles(surface: TexturedMeshSurface, elements: IntArray, mask: TexelMask): List<Face>? {
        val faces = ArrayList<Face>(elements.size / 3)
        for (i in elements.indices step 3) {
            val corners = intArrayOf(elements[i], elements[i + 1], elements[i + 2])
            if (corners.any { it !in surface.positions.indices || it !in surface.uv.indices }) return null
            val (a, b, c) = corners.map { surface.positions[it] }
            val normal = (b - a).cross(c - a, MutableVec3f())
            if (!normal.sqrLength().isFinite()) return null
            if (normal.sqrLength() == 0f) continue
            val uv = corners.map { Vec2f(surface.uv[it].x * mask.width, surface.uv[it].y * mask.height) }
            if (uv.any { !it.x.isFinite() || !it.y.isFinite() }) return null
            val area = cross(uv[1] - uv[0], uv[2] - uv[0])
            if (abs(area) < MIN_UV_AREA) return null
            faces += Face(corners, uv, normal.norm(), area)
        }
        return faces
    }

    /** Miter the displaced surface at shared corners by intersecting the incident offset planes. */
    private fun offsets(faces: List<Face>, positions: Array<Vec3f>): Map<Point, Vec3f> {
        val normals = HashMap<Point, MutableList<Vec3f>>()
        faces.forEach { face ->
            face.corners.forEach { index ->
                val at = normals.getOrPut(Point(positions[index]), ::ArrayList)
                if (at.none { (it dot face.normal) > 0.99999f }) at += face.normal
            }
        }
        return normals.mapValues { (_, at) ->
            val a = at.first()
            val b = at.drop(1).firstOrNull { abs(a dot it) < 0.99999f } ?: return@mapValues a
            val ab = a.cross(b, MutableVec3f())
            val c = at.firstOrNull { abs(ab dot it) > 0.001f }
            val offset = if (c == null) (a + b) / (1f + (a dot b)) else {
                (b.cross(c, MutableVec3f()) + c.cross(a, MutableVec3f()) + ab) / (ab dot c)
            }
            if (offset.sqrLength() <= MITER_LIMIT * MITER_LIMIT) offset else offset.normed() * MITER_LIMIT
        }
    }

    internal class Face(val corners: IntArray, val uv: List<Vec2f>, val normal: Vec3f, private val area: Float) {
        fun clip(x: Int, y: Int): List<Vec2f> = uv
            .clipAgainst(x.toFloat(), Vec2f::x, keepGreater = true)
            .clipAgainst(x + 1f, Vec2f::x, keepGreater = false)
            .clipAgainst(y.toFloat(), Vec2f::y, keepGreater = true)
            .clipAgainst(y + 1f, Vec2f::y, keepGreater = false)
            .takeIf { polygon ->
                polygon.size >= 3 && abs(polygon.drop(1).zipWithNext().sumOf { (b, c) ->
                    cross(b - polygon[0], c - polygon[0]).toDouble()
                }) >= MIN_UV_AREA
            }.orEmpty()

        fun weightsAt(point: Vec2f): Vec3f {
            val delta = point - uv[0]
            val b = cross(delta, uv[2] - uv[0]) / area
            val c = cross(uv[1] - uv[0], delta) / area
            return Vec3f(1f - b - c, b, c)
        }

        inline fun interpolate(weights: Vec3f, value: (Int) -> Vec3f): Vec3f =
            value(corners[0]) * weights.x + value(corners[1]) * weights.y + value(corners[2]) * weights.z
    }

    internal class Vertex(val base: Vec3f, val displaced: Vec3f, val weights: Vec3f, val face: Face)

    private data class Boundary(val from: Point, val start: Vertex, val end: Vertex, val uv: Vec2f)
    private data class Edge(val a: Point, val b: Point)
    private data class Point(val x: Long, val y: Long, val z: Long) : Comparable<Point> {
        constructor(value: Vec3f) : this(
            (value.x * JOIN_PRECISION).roundToLong(),
            (value.y * JOIN_PRECISION).roundToLong(),
            (value.z * JOIN_PRECISION).roundToLong(),
        )

        override fun compareTo(other: Point): Int = compareValuesBy(this, other, Point::x, Point::y, Point::z)
    }

    private fun List<Vec2f>.clipAgainst(
        boundary: Float,
        component: (Vec2f) -> Float,
        keepGreater: Boolean,
    ): List<Vec2f> {
        if (isEmpty()) return this
        return buildList(size + 1) {
            var previous = this@clipAgainst.last()
            var previousDistance = component(previous) - boundary
            for (point in this@clipAgainst) {
                val distance = component(point) - boundary
                val inside = if (keepGreater) distance >= 0f else distance <= 0f
                val wasInside = if (keepGreater) previousDistance >= 0f else previousDistance <= 0f
                if (inside != wasInside && distance != 0f && previousDistance != 0f) {
                    val (low, high) = if (previousDistance < distance) previous to point else point to previous
                    val t = (boundary - component(low)) / (component(high) - component(low))
                    add(low + (high - low) * t)
                }
                if (inside) add(point)
                previous = point
                previousDistance = distance
            }
        }
    }

    private fun cross(a: Vec2f, b: Vec2f): Float = a.x * b.y - a.y * b.x
}
