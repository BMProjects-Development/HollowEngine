package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.max

/** Ear clipping on the dominant plane preserves polygon winding and handles concave faces. */
internal fun triangulatePolygon(points: List<Vec3f>): List<Int> {
    require(points.size >= 3)
    if (points.size == 3) return listOf(0, 1, 2)
    val normal = DoubleArray(3)
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        normal[0] += (a.y.toDouble() - b.y) * (a.z.toDouble() + b.z)
        normal[1] += (a.z.toDouble() - b.z) * (a.x.toDouble() + b.x)
        normal[2] += (a.x.toDouble() - b.x) * (a.y.toDouble() + b.y)
    }
    val axis = normal.indices.maxBy { abs(normal[it]) }
    val x = points.map { (if (axis == 0) it.y else it.x).toDouble() }
    val y = points.map { (if (axis == 2) it.y else it.z).toDouble() }
    fun cross(a: Int, b: Int, c: Int) = (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
    val extent = max(x.max() - x.min(), y.max() - y.min())
    val epsilon = extent * extent * 1e-10
    val area = points.indices.sumOf { x[it] * y[(it + 1) % points.size] - x[(it + 1) % points.size] * y[it] }
    if (abs(area) <= epsilon) parseError("degenerate polygon")
    val sign = if (area > 0) 1 else -1
    val remaining = points.indices.toMutableList()
    val triangles = ArrayList<Int>()
    while (remaining.size > 3) {
        var clipped = false
        for (i in remaining.indices) {
            val a = remaining[(i + remaining.size - 1) % remaining.size]
            val b = remaining[i]
            val c = remaining[(i + 1) % remaining.size]
            val turn = cross(a, b, c) * sign
            if (abs(turn) <= epsilon) {
                remaining.removeAt(i)
                clipped = true
                break
            }
            if (turn < 0) continue
            if (remaining.any { p -> p != a && p != b && p != c &&
                    cross(a, b, p) * sign >= -epsilon && cross(b, c, p) * sign >= -epsilon &&
                    cross(c, a, p) * sign >= -epsilon }) continue
            triangles += listOf(a, b, c)
            remaining.removeAt(i)
            clipped = true
            break
        }
        if (!clipped) parseError("self-intersecting or invalid polygon")
    }
    if (remaining.size == 3) triangles += remaining
    return triangles
}
