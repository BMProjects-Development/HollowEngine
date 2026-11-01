package ru.hollowhorizon.hollowengine.client.ui.graph

import ru.hollowhorizon.hollowengine.client.ui.graph.GraphCurves.HANDLE
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPath
import ru.hollowhorizon.hollowengine.client.ui.shape.UiShapeSize
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** A rectangle of the graph or of the canvas, whichever space it was made in. */
data class GraphRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX get() = x + width / 2f
    val centerY get() = y + height / 2f

    fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + width && py >= y && py <= y + height

    fun intersects(other: GraphRect): Boolean =
        x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height

    /** This rectangle of the graph as it lies on the canvas. */
    fun toCanvas(view: GraphViewState) =
        GraphRect(view.toCanvasX(x), view.toCanvasY(y), width * view.zoom, height * view.zoom)

    /** This rectangle grown by [left], [top], [right] and [bottom] on each side. */
    fun expanded(left: Float, top: Float, right: Float = left, bottom: Float = top) =
        GraphRect(x - left, y - top, width + left + right, height + top + bottom)

    companion object {
        /** The rectangle two corners span, in whichever order they came. */
        fun between(x1: Float, y1: Float, x2: Float, y2: Float) =
            GraphRect(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1), abs(y2 - y1))

        /** The smallest rectangle around all of [rects], or null when there are none. */
        fun around(rects: Collection<GraphRect>): GraphRect? {
            if (rects.isEmpty()) return null
            val left = rects.minOf { it.x }
            val top = rects.minOf { it.y }
            return GraphRect(left, top, rects.maxOf { it.x + it.width } - left, rects.maxOf { it.y + it.height } - top)
        }
    }
}

/** A connection as a sampled curve in canvas space: what gets drawn, clicked and labeled. */
class GraphCurve(val points: FloatArray) {
    val pointCount: Int get() = points.size / 2

    /** Where the curve lies, a little wider than its points so that a straight line still has an area. */
    val bounds: UiRect by lazy {
        val xs = FloatArray(pointCount) { points[it * 2] }
        val ys = FloatArray(pointCount) { points[it * 2 + 1] }
        val left = xs.min() - BOUNDS_MARGIN
        val top = ys.min() - BOUNDS_MARGIN
        UiRect(left, top, xs.max() + BOUNDS_MARGIN - left, ys.max() + BOUNDS_MARGIN - top)
    }

    /** The whole curve as one path inside [bounds]; curves of the same form share its geometry. */
    val shape: Shape by lazy { GraphCurveShape(points, bounds.x, bounds.y) }

    /** A point [fraction] of the way along the curve. */
    fun pointAt(fraction: Float): FloatArray {
        val last = pointCount - 1
        val step = (fraction.coerceIn(0f, 1f) * last).toInt().coerceIn(0, last)
        return floatArrayOf(points[step * 2], points[step * 2 + 1])
    }

    /** Whether ([x], [y]) can be within [radius] of the curve at all. */
    fun passesNear(x: Float, y: Float, radius: Float): Boolean = bounds.let {
        x >= it.x - radius && y >= it.y - radius && x <= it.x + it.width + radius && y <= it.y + it.height + radius
    }

    fun distanceTo(x: Float, y: Float): Float = (0 until pointCount - 1).minOfOrNull { index ->
        distanceToSegment(
            x, y,
            points[index * 2], points[index * 2 + 1],
            points[index * 2 + 2], points[index * 2 + 3],
        )
    } ?: Float.MAX_VALUE

    override fun equals(other: Any?) = other is GraphCurve && points.contentEquals(other.points)
    override fun hashCode() = points.contentHashCode()
}

private const val BOUNDS_MARGIN = 1f
private const val SHAPE_PRECISION = 32f

/** Points are snapped to a fraction of a pixel so that panning, which only moves a curve, finds the same geometry. */
private class GraphCurveShape(points: FloatArray, originX: Float, originY: Float) : Shape {
    private val local = FloatArray(points.size) {
        round((points[it] - if (it % 2 == 0) originX else originY) * SHAPE_PRECISION) / SHAPE_PRECISION
    }
    private val hash = local.contentHashCode()

    override fun createPath(size: UiShapeSize): UiPath = GenericShape {
        moveTo(local[0], local[1])
        for (index in 2 until local.size step 2) lineTo(local[index], local[index + 1])
    }.createPath(size)

    override fun equals(other: Any?) = other is GraphCurveShape && local.contentEquals(other.local)
    override fun hashCode() = hash
}

/** The way a link runs at a point it passes through, as a vector of length one. */
class GraphDirection(val x: Float, val y: Float)

/** How the two kinds of graph link their nodes. */
object GraphCurves {
    private const val SAMPLES = 32
    private const val MIN_BOW = 26f
    private const val MAX_BOW = 220f
    private const val HANDLE = 0.35f
    private const val MIN_LENGTH = 1e-3f

    /**
     * A link from a side of [from] into a side of [to].
     */
    fun betweenBoxes(from: GraphRect, to: GraphRect, offset: Float = 0f, gap: Float = 0f): GraphCurve {
        val horizontal = abs(to.centerX - from.centerX) >= abs(to.centerY - from.centerY)
        val start = from.sidePoint(towards = to, horizontal = horizontal, along = offset, gap = 0f)
        val end = to.sidePoint(towards = from, horizontal = horizontal, along = offset, gap = gap)

        val reach = if (horizontal) abs(end[0] - start[0]) else abs(end[1] - start[1])
        val bow = (reach * 0.6f).coerceIn(MIN_BOW, MAX_BOW)
        return bezier(
            start[0], start[1],
            start[0] + if (horizontal) bow * start[2] else 0f, start[1] + if (horizontal) 0f else bow * start[3],
            end[0] + if (horizontal) bow * end[2] else 0f, end[1] + if (horizontal) 0f else bow * end[3],
            end[0], end[1],
        )
    }

    /**
     * A link from an output pin to an input pin.
     */
    fun betweenPins(fromX: Float, fromY: Float, toX: Float, toY: Float, zoom: Float): GraphCurve {
        val bow = (abs(toX - fromX) * 0.5f).coerceIn(MIN_BOW * zoom, MAX_BOW * zoom)
        return bezier(fromX, fromY, fromX + bow, fromY, toX - bow, toY, toX, toY)
    }

    /**
     * A link between two pins, either of which can be a point the link passes through. It keeps to [leave] and [enter]
     * there, for a [HANDLE] of its length: unlike a pin's reach that cannot overshoot, so a link cannot tie a knot.
     */
    fun throughPoints(
        fromX: Float, fromY: Float, leave: GraphDirection?,
        toX: Float, toY: Float, enter: GraphDirection?,
        zoom: Float,
    ): GraphCurve {
        if (leave == null && enter == null) return betweenPins(fromX, fromY, toX, toY, zoom)
        val reach = hypot(toX - fromX, toY - fromY)
        val bow = (abs(toX - fromX) * 0.5f).coerceIn(MIN_BOW * zoom, MAX_BOW * zoom).coerceAtMost(reach * 0.5f)
        val handle = reach * HANDLE
        return bezier(
            fromX, fromY,
            fromX + (leave?.let { it.x * handle } ?: bow), fromY + (leave?.let { it.y * handle } ?: 0f),
            toX - (enter?.let { it.x * handle } ?: bow), toY - (enter?.let { it.y * handle } ?: 0f),
            toX, toY,
        )
    }

    /**
     * The way a link runs through ([x], [y]), coming from the points [from] and going to the points [to]: between the
     * two ways, so the curves on both sides of the point meet without a corner.
     */
    fun passing(x: Float, y: Float, from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>): GraphDirection {
        var sumX = 0f
        var sumY = 0f
        fun add(dx: Float, dy: Float) {
            val length = hypot(dx, dy)
            if (length > 0f) {
                sumX += dx / length
                sumY += dy / length
            }
        }
        from.forEach { (px, py) -> add(x - px, y - py) }
        to.forEach { (px, py) -> add(px - x, py - y) }
        val length = hypot(sumX, sumY)
        return if (length < MIN_LENGTH) GraphDirection(1f, 0f) else GraphDirection(sumX / length, sumY / length)
    }

    /** The key of the curve closest to ([x], [y]) within [radius], if any is. */
    fun <K> nearest(curves: List<Pair<K, GraphCurve>>, x: Float, y: Float, radius: Float): K? =
        curves.filter { (_, curve) -> curve.passesNear(x, y, radius) }
            .map { (key, curve) -> key to curve.distanceTo(x, y) }.filter { it.second <= radius }
            .minByOrNull { it.second }?.first

    private fun bezier(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
    ): GraphCurve {
        val points = FloatArray((SAMPLES + 1) * 2)
        for (step in 0..SAMPLES) {
            val t = step / SAMPLES.toFloat()
            val inverse = 1f - t
            val a = inverse * inverse * inverse
            val b = 3f * inverse * inverse * t
            val c = 3f * inverse * t * t
            val d = t * t * t
            points[step * 2] = a * x0 + b * x1 + c * x2 + d * x3
            points[step * 2 + 1] = a * y0 + b * y1 + c * y2 + d * y3
        }
        return GraphCurve(points)
    }

    /** Where a link attaches to this box: x, y, then the direction it leaves in. */
    private fun GraphRect.sidePoint(towards: GraphRect, horizontal: Boolean, along: Float, gap: Float): FloatArray =
        if (horizontal) {
            val right = towards.centerX >= centerX
            floatArrayOf(
                if (right) x + width + gap else x - gap,
                (centerY + along).coerceIn(y + 4f, y + height - 4f),
                if (right) 1f else -1f,
                0f,
            )
        } else {
            val below = towards.centerY >= centerY
            floatArrayOf(
                (centerX + along).coerceIn(x + 4f, x + width - 4f),
                if (below) y + height + gap else y - gap,
                0f,
                if (below) 1f else -1f,
            )
        }
}

internal fun distanceToSegment(x: Float, y: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return hypot(x - x1, y - y1)
    val t = (((x - x1) * dx + (y - y1) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
}
