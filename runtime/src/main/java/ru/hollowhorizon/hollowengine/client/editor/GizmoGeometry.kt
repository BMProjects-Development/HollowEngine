package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A 2D point in the overlay's logical coordinate space. */
data class Pt(val x: Float, val y: Float)

/** Which manipulator a screen handle drives. */
enum class GizmoHandleId {
    AXIS_X, AXIS_Y, AXIS_Z,
    PLANE_X, PLANE_Y, PLANE_Z,
    ROTATE_X, ROTATE_Y, ROTATE_Z,
    SCALE_X, SCALE_Y, SCALE_Z,
    CENTER, SCALE_UNIFORM,
}

/** The screen shape a handle is hit-tested against (see [GizmoPicker]). */
sealed interface PickPrimitive {
    data class Line(val points: List<Pt>, val closed: Boolean) : PickPrimitive
    data class Polygon(val points: List<Pt>) : PickPrimitive
    data class Disc(val center: Pt, val radius: Float) : PickPrimitive
}

/**
 * One interactive gizmo handle resolved into screen space for a frame. [renderLines] are the polylines
 * to stroke, each paired with whether it should be closed; [fillPolygon] is an optional filled area
 * (plane squares, arrow cones, scale cubes). [worldOrigin]/[worldAxis] feed the manipulator.
 * [emphasis] (0..1) dims handles that point away from the camera, so the flat projection still
 * tells which way an axis or plane faces.
 */
data class GizmoHandle(
    val id: GizmoHandleId,
    val worldOrigin: Vec3,
    val worldAxis: Vec3?,
    val renderLines: List<GizmoStroke>,
    val fillPolygon: List<Pt>?,
    val color: UiColor,
    val width: Float,
    val depth: Float,
    val pick: PickPrimitive,
    val emphasis: Float = 1f,
)

/** A polyline of a handle; [emphasis] below 1 draws it fainter and thinner, like the back half of a ring. */
data class GizmoStroke(val points: List<Pt>, val closed: Boolean = false, val emphasis: Float = 1f)

object GizmoColors {
    val AXIS_X = UiColor(0.96f, 0.30f, 0.28f)
    val AXIS_Y = UiColor(0.52f, 0.80f, 0.32f)
    val AXIS_Z = UiColor(0.28f, 0.58f, 0.98f)
    val CENTER = UiColor(0.95f, 0.96f, 0.98f)

    val BOUNDS = UiColor(0.68f, 0.70f, 0.74f, 0.55f)
    val BOUNDS_HOVER = UiColor(0.40f, 0.90f, 1f, 0.85f)
    val BOUNDS_ACTIVE = UiColor(1f, 0.80f, 0.25f, 0.95f)

    val LIGHT = UiColor(0.82f, 0.84f, 0.88f, 0.92f)
    val LIGHT_HOVER = UiColor(0.40f, 0.90f, 1f, 0.95f)
    val LIGHT_ACTIVE = BOUNDS_ACTIVE

    fun highlighted(base: UiColor): UiColor = UiColor(
        (base.red + 0.28f).coerceAtMost(1f),
        (base.green + 0.28f).coerceAtMost(1f),
        (base.blue + 0.28f).coerceAtMost(1f),
        base.alpha,
    )
}

/**
 * Builds the screen-space geometry for the gizmo.
 */
object GizmoGeometry {
    private const val AXIS_LENGTH_PX = 38f
    private const val PLANE_OFFSET = 0.36f
    private const val PLANE_HALF = 0.17f
    private const val RING_RADIUS_PX = 36f
    private const val RING_SEGMENTS = 72
    private const val CENTER_RADIUS_PX = 7f
    private const val SCALE_CUBE_PX = 4.5f
    private const val CIRCLE_SEGMENTS = 48
    private const val CONE_LENGTH_PX = 10f
    private const val CONE_RADIUS_PX = 3.6f
    private const val CONE_SEGMENTS = 16

    private const val COMBINED_RING_RADIUS_PX = 52f
    private const val COMBINED_SCALE_LENGTH_PX = 24f

    private const val AWAY_DIMMING = 0.6f
    private const val RING_BACK_EMPHASIS = 0.3f
    private const val EDGE_ON_PLANE_EMPHASIS = 0.35f

    private const val AXIS_WIDTH = 1.7f
    private const val RING_WIDTH = 1.7f

    fun buildHandles(translation: Vec3f, rotation: QuatF, modes: Set<GizmoEditMode>): List<GizmoHandle> {
        val projector = WorldToScreenProjector
        val origin = Vec3(translation.x.toDouble(), translation.y.toDouble(), translation.z.toDouble())
        val perPixel = projector.worldPerPixel(origin)
        val originScreen = projector.project(origin) ?: return emptyList()
        if (!originScreen.onScreen) return emptyList()
        val originPt = Pt(originScreen.x, originScreen.y)
        val lx = Vec3f.X_AXIS.rotateBy(rotation)
        val ly = Vec3f.Y_AXIS.rotateBy(rotation)
        val lz = Vec3f.Z_AXIS.rotateBy(rotation)
        val translate = GizmoEditMode.TRANSLATE in modes
        val combined = modes.size > 1

        return buildList {
            if (translate) {
                val wx = Vec3f.X_AXIS
                val wy = Vec3f.Y_AXIS
                val wz = Vec3f.Z_AXIS
                axisArrow(GizmoHandleId.AXIS_X, origin, wx, perPixel, GizmoColors.AXIS_X)?.let(::add)
                axisArrow(GizmoHandleId.AXIS_Y, origin, wy, perPixel, GizmoColors.AXIS_Y)?.let(::add)
                axisArrow(GizmoHandleId.AXIS_Z, origin, wz, perPixel, GizmoColors.AXIS_Z)?.let(::add)
                planeQuad(GizmoHandleId.PLANE_X, origin, wx, wy, wz, perPixel, GizmoColors.AXIS_X)?.let(::add)
                planeQuad(GizmoHandleId.PLANE_Y, origin, wy, wz, wx, perPixel, GizmoColors.AXIS_Y)?.let(::add)
                planeQuad(GizmoHandleId.PLANE_Z, origin, wz, wx, wy, perPixel, GizmoColors.AXIS_Z)?.let(::add)
                add(discHandle(GizmoHandleId.CENTER, origin, originPt, CENTER_RADIUS_PX, GizmoColors.CENTER, originScreen.depth))
            }

            if (GizmoEditMode.ROTATE in modes) {
                val radius = if (combined) COMBINED_RING_RADIUS_PX else RING_RADIUS_PX
                rotationRing(GizmoHandleId.ROTATE_X, origin, lx, ly, lz, perPixel, radius, GizmoColors.AXIS_X)?.let(::add)
                rotationRing(GizmoHandleId.ROTATE_Y, origin, ly, lz, lx, perPixel, radius, GizmoColors.AXIS_Y)?.let(::add)
                rotationRing(GizmoHandleId.ROTATE_Z, origin, lz, lx, ly, perPixel, radius, GizmoColors.AXIS_Z)?.let(::add)
            }

            if (GizmoEditMode.SCALE in modes) {
                // Next to the arrows the scale handles lose their shafts and the centre stays free movement.
                val basis = Triple(lx, ly, lz)
                scaleAxis(GizmoHandleId.SCALE_X, origin, lx, basis, perPixel, translate, GizmoColors.AXIS_X)?.let(::add)
                scaleAxis(GizmoHandleId.SCALE_Y, origin, ly, basis, perPixel, translate, GizmoColors.AXIS_Y)?.let(::add)
                scaleAxis(GizmoHandleId.SCALE_Z, origin, lz, basis, perPixel, translate, GizmoColors.AXIS_Z)?.let(::add)
                if (!translate) {
                    cubeHandle(GizmoHandleId.SCALE_UNIFORM, origin, basis, perPixel * (SCALE_CUBE_PX + 1.5f), GizmoColors.CENTER)
                        ?.let(::add)
                }
            }
        }
    }

    private fun axisArrow(id: GizmoHandleId, origin: Vec3, axis: Vec3f, perPixel: Float, color: UiColor): GizmoHandle? {
        val projector = WorldToScreenProjector
        val direction = worldVec(axis)
        val length = (perPixel * AXIS_LENGTH_PX).toDouble()
        val tip = origin.add(direction.scale(length))
        val coneBase = origin.add(direction.scale(length - perPixel * CONE_LENGTH_PX))
        val start = projector.project(origin) ?: return null
        val end = projector.project(tip) ?: return null
        val shaftEnd = projector.project(coneBase) ?: return null
        if (!start.onScreen || !end.onScreen || !shaftEnd.onScreen) return null
        val cone = cone(tip, direction, perPixel) ?: return null
        val startPt = Pt(start.x, start.y)
        val endPt = Pt(end.x, end.y)
        return GizmoHandle(
            id, origin, direction,
            listOf(GizmoStroke(listOf(startPt, Pt(shaftEnd.x, shaftEnd.y))), GizmoStroke(cone, closed = true)),
            fillPolygon = cone, color = color, width = AXIS_WIDTH, depth = end.depth,
            pick = PickPrimitive.Line(listOf(startPt, endPt), closed = false),
            emphasis = facingEmphasis(origin, direction),
        )
    }

    private fun cone(tip: Vec3, axis: Vec3, perPixel: Float): List<Pt>? {
        val projector = WorldToScreenProjector
        val base = tip.subtract(axis.scale((perPixel * CONE_LENGTH_PX).toDouble()))
        val radius = (perPixel * CONE_RADIUS_PX).toDouble()
        val u = perpendicular(axis)
        val v = axis.cross(u)
        val points = ArrayList<Pt>(CONE_SEGMENTS + 1)
        projector.project(tip)?.takeIf { it.onScreen }?.let { points += Pt(it.x, it.y) } ?: return null
        for (i in 0 until CONE_SEGMENTS) {
            val angle = i.toDouble() / CONE_SEGMENTS * Math.PI * 2.0
            val world = base.add(u.scale(cos(angle) * radius)).add(v.scale(sin(angle) * radius))
            val projected = projector.project(world)?.takeIf { it.onScreen } ?: return null
            points += Pt(projected.x, projected.y)
        }
        return convexHull(points)
    }

    private fun planeQuad(id: GizmoHandleId, origin: Vec3, normal: Vec3f, spanA: Vec3f, spanB: Vec3f, perPixel: Float, color: UiColor): GizmoHandle? {
        val projector = WorldToScreenProjector
        val length = perPixel * AXIS_LENGTH_PX
        val offset = length * PLANE_OFFSET
        val half = length * PLANE_HALF
        val center = origin.add(
            (spanA.x + spanB.x).toDouble() * offset,
            (spanA.y + spanB.y).toDouble() * offset,
            (spanA.z + spanB.z).toDouble() * offset,
        )
        val corners = listOf(
            cornerWorld(center, spanA, spanB, -half, -half),
            cornerWorld(center, spanA, spanB, half, -half),
            cornerWorld(center, spanA, spanB, half, half),
            cornerWorld(center, spanA, spanB, -half, half),
        )
        val screen = corners.map { projector.project(it) ?: return null }
        if (screen.any { !it.onScreen }) return null
        val pts = screen.map { Pt(it.x, it.y) }
        val facing = abs(viewFacing(origin, worldVec(normal)))
        return GizmoHandle(
            id, origin, worldVec(normal), listOf(GizmoStroke(pts, closed = true)), fillPolygon = pts,
            color = color, width = AXIS_WIDTH, depth = screen.map { it.depth }.average().toFloat(),
            pick = PickPrimitive.Polygon(pts),
            emphasis = EDGE_ON_PLANE_EMPHASIS + (1f - EDGE_ON_PLANE_EMPHASIS) * facing,
        )
    }

    private fun cornerWorld(center: Vec3, a: Vec3f, b: Vec3f, ka: Float, kb: Float): Vec3 = center.add(
        (a.x * ka + b.x * kb).toDouble(),
        (a.y * ka + b.y * kb).toDouble(),
        (a.z * ka + b.z * kb).toDouble(),
    )

    private fun rotationRing(
        id: GizmoHandleId,
        origin: Vec3,
        axis: Vec3f,
        u: Vec3f,
        v: Vec3f,
        perPixel: Float,
        radiusPx: Float,
        color: UiColor,
    ): GizmoHandle? {
        val projector = WorldToScreenProjector
        val radius = perPixel * radiusPx
        val worldPts = ArrayList<Vec3>(RING_SEGMENTS + 1)
        val pts = ArrayList<Pt>(RING_SEGMENTS + 1)
        var depthSum = 0f
        for (i in 0..RING_SEGMENTS) {
            val angle = i.toDouble() / RING_SEGMENTS * Math.PI * 2.0
            val c = cos(angle).toFloat() * radius
            val s = sin(angle).toFloat() * radius
            val world = origin.add(
                (u.x * c + v.x * s).toDouble(),
                (u.y * c + v.y * s).toDouble(),
                (u.z * c + v.z * s).toDouble(),
            )
            val projected = projector.project(world) ?: return null
            if (!projected.onScreen) return null
            worldPts += world
            pts += Pt(projected.x, projected.y)
            depthSum += projected.depth
        }
        val depthMean = depthSum / pts.size
        val pick = PickPrimitive.Line(pts, closed = true)

        val camDir = projector.cameraPosition.subtract(origin)
        val faceOn = camDir.length() < 1e-6 || abs(viewFacing(origin, worldVec(axis))) > 0.9
        if (faceOn) {
            return GizmoHandle(
                id, origin, worldVec(axis), listOf(GizmoStroke(pts, closed = true)), fillPolygon = null,
                color = color, width = RING_WIDTH, depth = depthMean, pick = pick,
            )
        }

        val front = BooleanArray(RING_SEGMENTS + 1) { worldPts[it].subtract(origin).dot(camDir) > 0.0 }
        val arcs = ArrayList<GizmoStroke>()
        var run = ArrayList<Pt>().apply { add(pts[0]) }
        var runFront = front[0] && front[1]
        for (i in 0 until RING_SEGMENTS) {
            val segmentFront = front[i] && front[i + 1]
            if (segmentFront != runFront) {
                arcs += GizmoStroke(run, emphasis = if (runFront) 1f else RING_BACK_EMPHASIS)
                run = arrayListOf(pts[i])
                runFront = segmentFront
            }
            run += pts[i + 1]
        }
        arcs += GizmoStroke(run, emphasis = if (runFront) 1f else RING_BACK_EMPHASIS)
        arcs.sortBy { it.emphasis }
        return GizmoHandle(
            id, origin, worldVec(axis), arcs, fillPolygon = null, color = color,
            width = RING_WIDTH, depth = depthMean, pick = pick,
        )
    }

    /**
     * A scale handle: a cube at the end of a shaft, or only the cube, closer in, when the translate
     * arrows already occupy the axis.
     */
    private fun scaleAxis(
        id: GizmoHandleId,
        origin: Vec3,
        axis: Vec3f,
        basis: Triple<Vec3f, Vec3f, Vec3f>,
        perPixel: Float,
        besideArrows: Boolean,
        color: UiColor,
    ): GizmoHandle? {
        val projector = WorldToScreenProjector
        val direction = worldVec(axis)
        val length = perPixel * if (besideArrows) COMBINED_SCALE_LENGTH_PX else AXIS_LENGTH_PX
        val tipWorld = origin.add(direction.scale(length.toDouble()))
        val start = projector.project(origin) ?: return null
        val end = projector.project(tipWorld) ?: return null
        if (!start.onScreen || !end.onScreen) return null
        val cube = projectedCube(tipWorld, basis, perPixel * SCALE_CUBE_PX) ?: return null
        val startPt = Pt(start.x, start.y)
        val endPt = Pt(end.x, end.y)
        val lines = buildList {
            if (!besideArrows) add(GizmoStroke(listOf(startPt, endPt)))
            add(GizmoStroke(cube, closed = true))
        }
        return GizmoHandle(
            id, origin, direction, lines,
            fillPolygon = cube, color = color, width = AXIS_WIDTH, depth = end.depth,
            pick = if (besideArrows) PickPrimitive.Polygon(cube) else PickPrimitive.Line(listOf(startPt, endPt), closed = false),
            emphasis = facingEmphasis(origin, direction),
        )
    }

    private fun cubeHandle(
        id: GizmoHandleId,
        origin: Vec3,
        basis: Triple<Vec3f, Vec3f, Vec3f>,
        half: Float,
        color: UiColor,
    ): GizmoHandle? {
        val cube = projectedCube(origin, basis, half) ?: return null
        val depth = WorldToScreenProjector.project(origin)?.depth ?: return null
        return GizmoHandle(
            id, origin, worldAxis = null, listOf(GizmoStroke(cube, closed = true)), fillPolygon = cube,
            color = color, width = RING_WIDTH, depth = depth, pick = PickPrimitive.Polygon(cube),
        )
    }

    private fun projectedCube(center: Vec3, basis: Triple<Vec3f, Vec3f, Vec3f>, half: Float): List<Pt>? {
        val projector = WorldToScreenProjector
        val (a, b, c) = basis
        val points = ArrayList<Pt>(8)
        for (sa in SIGNS) for (sb in SIGNS) for (sc in SIGNS) {
            val world = center.add(
                ((a.x * sa + b.x * sb + c.x * sc) * half).toDouble(),
                ((a.y * sa + b.y * sb + c.y * sc) * half).toDouble(),
                ((a.z * sa + b.z * sb + c.z * sc) * half).toDouble(),
            )
            val projected = projector.project(world)?.takeIf { it.onScreen } ?: return null
            points += Pt(projected.x, projected.y)
        }
        return convexHull(points)
    }

    private val SIGNS = floatArrayOf(-1f, 1f)

    private fun discHandle(id: GizmoHandleId, origin: Vec3, center: Pt, radiusPx: Float, color: UiColor, depth: Float): GizmoHandle {
        return GizmoHandle(
            id, origin, worldAxis = null, listOf(GizmoStroke(screenCircle(center, radiusPx), closed = true)),
            fillPolygon = null, color = color, width = 2f, depth = depth, pick = PickPrimitive.Disc(center, radiusPx),
        )
    }

    /** Cosine between [direction] and the direction from [origin] towards the camera. */
    private fun viewFacing(origin: Vec3, direction: Vec3): Float {
        val toCamera = WorldToScreenProjector.cameraPosition.subtract(origin)
        val length = toCamera.length() * direction.length()
        if (length < 1e-9) return 1f
        return (direction.dot(toCamera) / length).toFloat()
    }

    /** Full strength for axes pointing at or across the view, fainter the more they point away. */
    private fun facingEmphasis(origin: Vec3, direction: Vec3): Float =
        1f - AWAY_DIMMING * (-viewFacing(origin, direction)).coerceAtLeast(0f)

    private fun convexHull(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy<Pt> { it.x }.thenBy { it.y })
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val hull = ArrayList<Pt>(sorted.size + 1)
        for (point in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], point) <= 0f) hull.removeAt(hull.size - 1)
            hull += point
        }
        val lowerSize = hull.size + 1
        for (i in sorted.size - 2 downTo 0) {
            val point = sorted[i]
            while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], point) <= 0f) hull.removeAt(hull.size - 1)
            hull += point
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    private fun screenCircle(center: Pt, radius: Float): List<Pt> {
        val pts = ArrayList<Pt>(CIRCLE_SEGMENTS + 1)
        for (i in 0..CIRCLE_SEGMENTS) {
            val angle = i.toDouble() / CIRCLE_SEGMENTS * Math.PI * 2.0
            pts += Pt(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
        }
        return pts
    }

    /**
     * The filled sector between [startAngle] and [endAngle] (radians) in the plane perpendicular to
     * [axis], for the rotation drag readout. Basis matches [GizmoManipulator.anglePlane].
     */
    fun buildRotationSector(origin: Vec3, axis: Vec3, startAngle: Double, endAngle: Double, perPixel: Float): List<Pt>? {
        val projector = WorldToScreenProjector
        val radius = perPixel * RING_RADIUS_PX * 0.92f
        val u = perpendicular(axis)
        val v = axis.cross(u)
        val originScreen = projector.project(origin) ?: return null
        if (!originScreen.onScreen) return null
        val steps = 48
        val pts = ArrayList<Pt>(steps + 2)
        pts += Pt(originScreen.x, originScreen.y)
        for (i in 0..steps) {
            val angle = startAngle + (endAngle - startAngle) * (i.toDouble() / steps)
            val c = cos(angle) * radius
            val s = sin(angle) * radius
            val world = origin.add(u.x * c + v.x * s, u.y * c + v.y * s, u.z * c + v.z * s)
            val projected = projector.project(world) ?: return null
            if (!projected.onScreen) return null
            pts += Pt(projected.x, projected.y)
        }
        return pts
    }

    private fun perpendicular(n: Vec3): Vec3 {
        val reference = if (abs(n.y) < 0.99) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        return n.cross(reference).normalize()
    }

    private fun worldVec(v: Vec3f): Vec3 = Vec3(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())

    /** The 12 edges of [bounds] projected into screen space, or empty when any corner is off-screen. */
    fun buildBoundsEdges(bounds: AABB): List<List<Pt>> {
        val projector = WorldToScreenProjector
        val corners = arrayOf(
            Vec3(bounds.minX, bounds.minY, bounds.minZ), Vec3(bounds.maxX, bounds.minY, bounds.minZ),
            Vec3(bounds.maxX, bounds.minY, bounds.maxZ), Vec3(bounds.minX, bounds.minY, bounds.maxZ),
            Vec3(bounds.minX, bounds.maxY, bounds.minZ), Vec3(bounds.maxX, bounds.maxY, bounds.minZ),
            Vec3(bounds.maxX, bounds.maxY, bounds.maxZ), Vec3(bounds.minX, bounds.maxY, bounds.maxZ),
        )
        val screen = arrayOfNulls<Pt>(8)
        for (i in corners.indices) {
            val p = projector.project(corners[i]) ?: return emptyList()
            if (!p.onScreen) return emptyList()
            screen[i] = Pt(p.x, p.y)
        }
        val edges = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 0,
            4, 5, 5, 6, 6, 7, 7, 4,
            0, 4, 1, 5, 2, 6, 3, 7,
        )
        val result = ArrayList<List<Pt>>(12)
        var i = 0
        while (i < edges.size) {
            result += listOf(screen[edges[i]]!!, screen[edges[i + 1]]!!)
            i += 2
        }
        return result
    }

    /** Projects a world-space polyline; returns null if any vertex is off-screen. */
    fun projectPolyline(points: List<Vec3>): List<Pt>? {
        val projector = WorldToScreenProjector
        val result = ArrayList<Pt>(points.size)
        for (point in points) {
            val p = projector.project(point) ?: return null
            if (!p.onScreen) return null
            result += Pt(p.x, p.y)
        }
        return result
    }
}
