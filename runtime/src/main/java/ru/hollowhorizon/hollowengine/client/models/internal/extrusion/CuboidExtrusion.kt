package ru.hollowhorizon.hollowengine.client.models.internal.extrusion

import ru.hollowhorizon.hollowengine.client.models.internal.extrusion.ExtrusionMesh.Face
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Builds the boundary of the union of rectangular texel prisms on a cuboid.
 * Inward prisms overlap at the edges. Outward prisms are joined by edge and corner cells when their texels touch.
 */
internal object CuboidExtrusion {
    private const val AXIS_EPSILON = 1e-5f
    private const val UV_EPSILON = 1e-4f
    private const val MAX_GRID_CELLS = 262_144
    private val SIGNS = intArrayOf(-1, 1)

    fun emit(
        faces: List<Face>,
        positions: Array<Vec3f>,
        mask: TexelMask,
        thickness: Float,
        output: ExtrusionVertices,
        vertexBudget: Int,
    ): Boolean {
        val sides = cuboidSides(faces, positions) ?: return false
        val cuts = Array(3) { sortedSetOf<Float>() }
        val prisms = ArrayList<Prism>()
        var texelTests = 0L
        for (side in sides) {
            val minU = side.uv.minOf(Vec2f::x)
            val maxU = side.uv.maxOf(Vec2f::x)
            val minV = side.uv.minOf(Vec2f::y)
            val maxV = side.uv.maxOf(Vec2f::y)
            val startU = floor(minU).toInt()
            val endU = ceil(maxU).toInt()
            val startV = floor(minV).toInt()
            val endV = ceil(maxV).toInt()
            val columns = endU.toLong() - startU
            val rows = endV.toLong() - startV
            if (columns !in 1L..MAX_GRID_CELLS.toLong() || rows !in 1L..MAX_GRID_CELLS.toLong()) return false
            texelTests += columns * rows
            if (texelTests > MAX_GRID_CELLS) return false

            val displaced = side.plane + side.sign * thickness
            for (v in startV until endV) for (u in startU until endU) {
                if (!mask[u, v]) continue
                val a = side.position(maxOf(minU, u.toFloat()), maxOf(minV, v.toFloat()))
                val b = side.position(minOf(maxU, u + 1f), minOf(maxV, v + 1f))
                val low = FloatArray(3) { axis -> if (axis == side.axis) minOf(side.plane, displaced) else minOf(a[axis], b[axis]) }
                val high = FloatArray(3) { axis -> if (axis == side.axis) maxOf(side.plane, displaced) else maxOf(a[axis], b[axis]) }
                if (low.indices.any { low[it] >= high[it] }) continue
                if (prisms.size >= vertexBudget / 4) return false
                prisms += Prism(side, low, high, Vec2f((u + 0.5f) / mask.width, (v + 0.5f) / mask.height))
                for (axis in 0..2) {
                    cuts[axis] += low[axis]
                    cuts[axis] += high[axis]
                }
            }
        }
        if (prisms.isEmpty()) return true

        val bounds = Array(3) { axis -> sides.filter { it.axis == axis }.map(Side::plane).sorted() }
        if (thickness > 0f) {
            for (axis in 0..2) cuts[axis].addAll(bounds[axis])
        }
        val coordinates = Array(3) { axis -> cuts[axis].toFloatArray() }
        var count = 1L
        for (axis in coordinates) {
            count *= axis.size - 1
            if (count > MAX_GRID_CELLS) return false
        }

        val grid = Grid(coordinates)
        prisms.forEach(grid::fill)
        if (thickness > 0f) grid.joinOutside(bounds)
        return grid.emit(output, vertexBudget)
    }

    private fun cuboidSides(faces: List<Face>, positions: Array<Vec3f>): List<Side>? {
        if (faces.size != 12) return null
        val groups = faces.groupBy { face ->
            val normal = face.normal
            val axis = (0..2).firstOrNull { abs(normal[it]) >= 1f - AXIS_EPSILON } ?: return null
            if ((0..2).any { it != axis && abs(normal[it]) > AXIS_EPSILON }) return null
            axis to if (normal[axis] > 0f) 1 else -1
        }
        if (groups.size != 6) return null
        val sides = groups.map { (orientation, triangles) ->
            val (axis, sign) = orientation
            Side.create(axis, sign, triangles, positions) ?: return null
        }
        val bounds = Array(3) { axis -> sides.filter { it.axis == axis }.associate { it.sign to it.plane } }
        if (bounds.any { it.getValue(-1) >= it.getValue(1) }) return null
        if (positions.any { point -> (0..2).any { axis -> point[axis] !in bounds[axis].values } }) return null
        return sides
    }

    private class Side(
        val axis: Int,
        val sign: Int,
        val plane: Float,
        val uv: List<Vec2f>,
        private val triangles: List<Face>,
        private val u: Mapping,
        private val v: Mapping,
    ) {
        fun position(u: Float, v: Float): FloatArray = FloatArray(3).also {
            it[axis] = plane
            it[this.u.axis] = this.u.position(u)
            it[this.v.axis] = this.v.position(v)
        }

        fun sample(point: Vec3f): Pair<Face, Vec3f> {
            val pointUv = Vec2f(u.texel(point[u.axis]), v.texel(point[v.axis]))
            val first = triangles.first()
            val weights = first.weightsAt(pointUv)
            if (minOf(weights.x, weights.y, weights.z) >= -UV_EPSILON) return first to weights
            val second = triangles.last()
            return second to second.weightsAt(pointUv)
        }

        companion object {
            fun create(axis: Int, sign: Int, triangles: List<Face>, positions: Array<Vec3f>): Side? {
                if (triangles.size != 2) return null
                val vertices = triangles.flatMap { face -> face.corners.mapIndexed { i, index -> face.uv[i] to positions[index] } }
                val byUv = vertices.toMap()
                if (byUv.size != 4 || vertices.any { (uv, point) -> byUv[uv] != point }) return null
                val shared = triangles[0].uv.intersect(triangles[1].uv.toSet()).toList()
                if (shared.size != 2 || shared[0].x == shared[1].x || shared[0].y == shared[1].y) return null
                val plane = vertices.first().second[axis]
                if (vertices.any { it.second[axis] != plane }) return null
                val uMin = byUv.keys.minOf(Vec2f::x)
                val uMax = byUv.keys.maxOf(Vec2f::x)
                val vMin = byUv.keys.minOf(Vec2f::y)
                val vMax = byUv.keys.maxOf(Vec2f::y)
                val origin = byUv[Vec2f(uMin, vMin)] ?: return null
                val uEnd = byUv[Vec2f(uMax, vMin)] ?: return null
                val vEnd = byUv[Vec2f(uMin, vMax)] ?: return null
                val uAxis = (0..2).singleOrNull { uEnd[it] != origin[it] } ?: return null
                val vAxis = (0..2).singleOrNull { vEnd[it] != origin[it] } ?: return null
                if (uAxis == vAxis) return null
                val opposite = byUv[Vec2f(uMax, vMax)] ?: return null
                if (opposite[uAxis] != uEnd[uAxis] || opposite[vAxis] != vEnd[vAxis]) return null
                val u = Mapping(uAxis, origin[uAxis], uEnd[uAxis], uMin, uMax)
                val v = Mapping(vAxis, origin[vAxis], vEnd[vAxis], vMin, vMax)
                return Side(axis, sign, plane, byUv.keys.toList(), triangles, u, v)
            }
        }
    }

    private class Mapping(val axis: Int, start: Float, end: Float, first: Float, last: Float) {
        private val min = minOf(start, end).toDouble()
        private val max = maxOf(start, end).toDouble()
        private val atMin = if (start < end) first.toDouble() else last.toDouble()
        private val atMax = if (start < end) last.toDouble() else first.toDouble()

        fun position(texel: Float): Float = (min + (max - min) * ((texel - atMin) / (atMax - atMin))).toFloat()
        fun texel(position: Float): Float = (atMin + (atMax - atMin) * ((position - min) / (max - min))).toFloat()
    }

    private class Prism(val side: Side, val low: FloatArray, val high: FloatArray, val uv: Vec2f) {
        fun sample(point: Vec3f): Pair<Face, Vec3f> = side.sample(Vec3f(
            point.x.coerceIn(low[0], high[0]),
            point.y.coerceIn(low[1], high[1]),
            point.z.coerceIn(low[2], high[2]),
        ))
    }

    private class Grid(private val coordinates: Array<FloatArray>) {
        private val sizes = IntArray(3) { coordinates[it].size - 1 }
        private val stride = intArrayOf(1, sizes[0], sizes[0] * sizes[1])
        private val cells = arrayOfNulls<Prism>(sizes.reduce(Int::times))
        private val joins = HashMap<Int, List<Prism>>()

        fun fill(prism: Prism) {
            val low = IntArray(3) { coordinates[it].binarySearch(prism.low[it]) }
            val high = IntArray(3) { coordinates[it].binarySearch(prism.high[it]) }
            for (z in low[2] until high[2]) for (y in low[1] until high[1]) for (x in low[0] until high[0]) {
                val i = x + y * stride[1] + z * stride[2]
                if (cells[i] == null) cells[i] = prism
            }
        }

        fun joinOutside(bounds: Array<List<Float>>) {
            val inside = Array(3) { axis ->
                coordinates[axis].binarySearch(bounds[axis][0]) until coordinates[axis].binarySearch(bounds[axis][1])
            }
            for (i in cells.indices) {
                if (cells[i] != null) continue
                val cell = location(i)
                val outside = (0..2).filter { cell[it] !in inside[it] }
                if (outside.size < 2) continue
                val towardBox = IntArray(3) { axis ->
                    when {
                        cell[axis] < inside[axis].first -> stride[axis]
                        cell[axis] > inside[axis].last -> -stride[axis]
                        else -> 0
                    }
                }
                val total = towardBox.sum()
                val touching = outside.mapNotNull { axis -> cells[i + total - towardBox[axis]] }
                if (touching.size == outside.size) {
                    cells[i] = touching.first()
                    joins[i] = touching
                }
            }
        }

        fun emit(output: ExtrusionVertices, vertexBudget: Int): Boolean {
            var quads = 0
            for (i in cells.indices) {
                if (cells[i] == null) continue
                val cell = location(i)
                for (axis in 0..2) for (sign in SIGNS) {
                    if (exposed(i, cell, axis, sign) && ++quads > vertexBudget / 4) return false
                }
            }
            for (i in cells.indices) {
                val prism = cells[i] ?: continue
                val cell = location(i)
                for (axis in 0..2) for (sign in SIGNS) {
                    if (!exposed(i, cell, axis, sign)) continue
                    val points = corners(cell, axis, sign)
                    val materialSource = joins[i]?.firstOrNull { it.side.axis == axis && it.side.sign == sign } ?: prism
                    output.quad(points, materialSource.uv, AXES[axis] * sign.toFloat(), materialSource::sample)
                }
            }
            return true
        }

        private fun location(index: Int): IntArray = IntArray(3) { index / stride[it] % sizes[it] }

        private fun exposed(index: Int, cell: IntArray, axis: Int, sign: Int): Boolean =
            cell[axis] + sign !in 0 until sizes[axis] || cells[index + sign * stride[axis]] == null

        private fun corners(cell: IntArray, axis: Int, sign: Int): List<Vec3f> {
            val u = (axis + 1) % 3
            val v = (axis + 2) % 3
            val base = FloatArray(3) { coordinates[it][cell[it]] }
            if (sign > 0) base[axis] = coordinates[axis][cell[axis] + 1]
            val a = Vec3f(base[0], base[1], base[2])
            base[u] = coordinates[u][cell[u] + 1]
            val b = Vec3f(base[0], base[1], base[2])
            base[v] = coordinates[v][cell[v] + 1]
            val c = Vec3f(base[0], base[1], base[2])
            base[u] = coordinates[u][cell[u]]
            val d = Vec3f(base[0], base[1], base[2])
            return if (sign > 0) listOf(a, b, c, d) else listOf(a, d, c, b)
        }
    }

    private val AXES = listOf(Vec3f.X_AXIS, Vec3f.Y_AXIS, Vec3f.Z_AXIS)

    private operator fun Vec3f.get(axis: Int): Float = when (axis) {
        0 -> x
        1 -> y
        else -> z
    }
}
