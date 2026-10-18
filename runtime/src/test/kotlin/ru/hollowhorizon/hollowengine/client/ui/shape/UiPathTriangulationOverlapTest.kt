package ru.hollowhorizon.hollowengine.client.ui.shape

import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Area-conservation is the sharpest overlap detector for a *fill*: a correct triangulation of a
 * simple polygon has triangles whose total area equals the polygon's, none overlapping. Strokes are
 * tessellated directly (segment quads + join/cap fans), so their check is plausible coverage - a
 * little overlap at inner joins is expected and harmless.
 */
class UiPathTriangulationOverlapTest {
    @Test
    fun `convex polygon still fills as a clean fan`() {
        val circle = List(64) { i ->
            val a = i * PI * 2.0 / 64.0
            UiPathPoint((cos(a) * 20.0).toFloat(), (sin(a) * 20.0).toFloat())
        }
        val triangles = UiPathGeometry(listOf(UiPathContour(circle, closed = true))).fillTriangles()
        assertEquals(62, triangles.size, "a convex 64-gon fans into 62 triangles")
        val polygonArea = abs(shoelace(circle))
        val triangleArea = triangles.sumOf { abs(signedArea(it)).toDouble() }.toFloat()
        assertTrue(abs(polygonArea - triangleArea) < polygonArea * 0.01f, "fan conserves area")
    }

    @Test
    fun `concave comb fill conserves area`() {
        val points = buildList {
            val teeth = 8
            add(UiPathPoint(0f, 0f))
            add(UiPathPoint((teeth * 2).toFloat(), 0f))
            for (t in teeth - 1 downTo 0) {
                add(UiPathPoint((t * 2 + 2).toFloat(), 3f))
                add(UiPathPoint((t * 2 + 1).toFloat(), 1f))
                add(UiPathPoint((t * 2).toFloat(), 3f))
            }
        }
        val triangles = UiPathGeometry(listOf(UiPathContour(points, closed = true))).fillTriangles()
        val polygonArea = abs(shoelace(points))
        val triangleArea = triangles.sumOf { abs(signedArea(it)).toDouble() }.toFloat()
        assertTrue(
            abs(polygonArea - triangleArea) < polygonArea * 0.01f + 1e-3f,
            "concave fill area $triangleArea should match polygon area $polygonArea",
        )
    }

    @Test
    fun `zig-zag stroke tessellates with plausible coverage`() {
        val contours = UiPathBuilder().apply {
            moveTo(0f, 2f)
            var x = 0f
            var high = true
            while (x < 40f) {
                x = min(x + 3f, 40f)
                lineTo(x, if (high) 0f else 4f)
                high = !high
            }
        }.build().flatten().contours
        val triangles = UiPathGeometry(contours).strokeTriangles(1.25f)
        assertTrue(triangles.isNotEmpty(), "stroke should tessellate")
        assertTrue(triangles.none { signedArea(it).isNaN() || abs(signedArea(it)) > 1e4f }, "no exploded triangles")
        // Polyline length ~56, width 1.25 -> coverage on the order of tens (with join/cap fans).
        val area = triangles.sumOf { abs(signedArea(it)).toDouble() }
        assertTrue(area in 40.0..160.0, "stroke coverage $area should be in a plausible band")
    }

    @Test
    fun `straight butt stroke is exactly the rectangle`() {
        val contours = UiPathBuilder().apply { moveTo(0f, 0f); lineTo(20f, 0f) }.build().flatten().contours
        val triangles = UiPathGeometry(contours).strokeTriangles(2f, lineCap = UiPathStrokeLineCap.Butt)
        val area = triangles.sumOf { abs(signedArea(it)).toDouble() }.toFloat()
        assertTrue(abs(area - 40f) < 1f, "20 x 2 butt stroke should cover ~40, was $area")
    }

    private fun shoelace(points: List<UiPathPoint>): Float {
        var area = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            area += a.x * b.y - b.x * a.y
        }
        return area * 0.5f
    }

    private fun signedArea(t: UiPathTriangle): Float {
        val abx = t.second.x - t.first.x
        val aby = t.second.y - t.first.y
        val acx = t.third.x - t.first.x
        val acy = t.third.y - t.first.y
        return (abx * acy - aby * acx) * 0.5f
    }
}
