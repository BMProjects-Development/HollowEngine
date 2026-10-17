package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.vfx.VfxFrame
import ru.hollowhorizon.hollowengine.client.vfx.VfxNodeRuntime
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.math.*

/** One line of the gizmo, already in panel pixels. */
data class VfxGizmoLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color: UiColor)

/**
 * A draggable point of the gizmo.
 */
class VfxGizmoHandle(
    val id: String,
    val x: Float,
    val y: Float,
    val screenAxisX: Float,
    val screenAxisY: Float,
    val color: UiColor,
    val readout: (Float) -> Float,
    val apply: (VfxNodeSpec, Float) -> VfxNodeSpec,
)

class VfxGizmo(val lines: List<VfxGizmoLine>, val handles: List<VfxGizmoHandle>) {
    fun handleAt(x: Float, y: Float): VfxGizmoHandle? =
        handles.filter { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) <= PICK_RADIUS * PICK_RADIUS }
            .minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }

    companion object {
        val EMPTY = VfxGizmo(emptyList(), emptyList())
        const val PICK_RADIUS = 7f
    }
}

/**
 * Builds the handles of an emitter's shape as the preview camera sees it. Placement is the world
 * transform gizmo's job ([VfxTransformGizmo]).
 */
object VfxGizmos {
    fun build(
        preview: VfxPreviewState,
        node: VfxNodeSpec,
        runtime: VfxNodeRuntime?,
        showShape: Boolean,
        driven: (VfxProperty) -> VfxDrivenValue?,
    ): VfxGizmo {
        val width = preview.viewportWidth
        val height = preview.viewportHeight
        if (width <= 1f || height <= 1f || runtime == null) return VfxGizmo.EMPTY

        val builder = Builder(preview, preview.panelMatrix(width, height), runtime.frame, driven)
        if (showShape && node is VfxEmitterSpec) builder.shape(node.shape)
        return VfxGizmo(builder.lines, builder.handles)
    }

    /**
     * How far a drag of ([dx], [dy]) pixels moves [handle], in its own units: Shift is ten times finer, Ctrl rounds to a step.
     */
    fun dragDistance(handle: VfxGizmoHandle, dx: Float, dy: Float, fine: Boolean, snap: Boolean): Float {
        val lengthSquared = handle.screenAxisX * handle.screenAxisX + handle.screenAxisY * handle.screenAxisY
        if (lengthSquared < 1.0e-4f) return 0f
        var distance = (dx * handle.screenAxisX + dy * handle.screenAxisY) / lengthSquared
        if (fine) distance *= FINE_FACTOR
        if (snap) {
            val tick = if (fine) FINE_TICK else TICK
            distance = round(distance / tick) * tick
        }
        return distance
    }

    private class Builder(
        private val preview: VfxPreviewState,
        private val matrix: Matrix4f,
        private val frame: VfxFrame,
        private val driven: (VfxProperty) -> VfxDrivenValue?,
    ) {
        val lines = ArrayList<VfxGizmoLine>()
        val handles = ArrayList<VfxGizmoHandle>()

        private val point = MutableVec3f()
        private val basis = preview.cameraBasis()

        private fun world(x: Float, y: Float, z: Float): Vector3f {
            frame.transformPoint(Vec3f(x, y, z), point)
            return Vector3f(point.x, point.y, point.z)
        }

        fun segment(a: Vector3f, b: Vector3f, color: UiColor) {
            val (start, end) = clipToNear(a, b) ?: return
            val from = preview.project(matrix, start) ?: return
            val to = preview.project(matrix, end) ?: return
            if (!sane(from) || !sane(to)) return
            lines += VfxGizmoLine(from.x, from.y, to.x, to.y, color)
        }

        private fun clipToNear(a: Vector3f, b: Vector3f): Pair<Vector3f, Vector3f>? {
            val da = Vector3f(a).sub(basis.eye).dot(basis.forward) - NEAR_CLIP
            val db = Vector3f(b).sub(basis.eye).dot(basis.forward) - NEAR_CLIP
            if (da < 0f && db < 0f) return null
            if (da >= 0f && db >= 0f) return a to b
            val t = da / (da - db)
            val cut = Vector3f(a).lerp(b, t)
            return if (da < 0f) cut to b else a to cut
        }

        private fun sane(point: Vector3f): Boolean =
            point.x in -SCREEN_LIMIT..SCREEN_LIMIT && point.y in -SCREEN_LIMIT..SCREEN_LIMIT

        private fun polyline(points: List<Vector3f>, color: UiColor, closed: Boolean) {
            for (index in 0 until points.size - 1) segment(points[index], points[index + 1], color)
            if (closed && points.size > 2) segment(points.last(), points.first(), color)
        }

        private fun circle(radius: Float, plane: Int, height: Float, color: UiColor) {
            val points = (0 until CIRCLE_SEGMENTS).map { index ->
                val angle = index * (2.0 * PI / CIRCLE_SEGMENTS).toFloat()
                val u = cos(angle) * radius
                val v = sin(angle) * radius
                when (plane) {
                    PLANE_XZ -> world(u, height, v)
                    PLANE_XY -> world(u, v, height)
                    else -> world(height, u, v)
                }
            }
            polyline(points, color, closed = true)
        }

        private fun handle(
            id: String,
            local: Vector3f,
            direction: Vector3f,
            color: UiColor,
            readout: (Float) -> Float,
            apply: (VfxNodeSpec, Float) -> VfxNodeSpec,
        ) {
            val base = world(local.x, local.y, local.z)
            val tip = world(local.x + direction.x, local.y + direction.y, local.z + direction.z)
            val from = preview.project(matrix, base) ?: return
            val to = preview.project(matrix, tip) ?: return
            if (!sane(from)) return
            handles += VfxGizmoHandle(id, from.x, from.y, to.x - from.x, to.y - from.y, color, readout, apply)
        }

        private fun current(property: VfxProperty, channel: Int, value: VfxValue, fallback: Float): Float {
            val keyed = driven(property)?.takeIf { it.drives(channel) }
            return keyed?.value(channel) ?: value.constantOr(fallback)
        }

        /** A handle sets a constant; a keyed channel takes the drag as a key or holds it, as the timeline records or not. */
        private fun editable(value: VfxValue): Boolean = value is VfxValue.Const

        fun shape(shape: VfxShape) {
            val radius = current(VfxProperty.SHAPE_RADIUS, 0, shape.radius, 0.5f)
            val thickness = current(VfxProperty.SHAPE_THICKNESS, 0, shape.thickness, 1f)
            val radiusEditable = editable(shape.radius)

            fun radiusHandle() {
                if (!radiusEditable) return
                handle(
                    "radius", Vector3f(radius, 0f, 0f), Vector3f(1f, 0f, 0f), HandleColor,
                    readout = { (radius + it).coerceAtLeast(0f) },
                ) { node, delta ->
                    node.withShape { it.copy(radius = VfxValue.Const((radius + delta).coerceAtLeast(0f))) }
                }
            }

            when (shape.kind) {
                VfxShapeKind.POINT, VfxShapeKind.MODEL -> {
                    val size = 0.08f
                    segment(world(-size, 0f, 0f), world(size, 0f, 0f), ShapeColor)
                    segment(world(0f, -size, 0f), world(0f, size, 0f), ShapeColor)
                    segment(world(0f, 0f, -size), world(0f, 0f, size), ShapeColor)
                }

                VfxShapeKind.SPHERE -> {
                    circle(radius, PLANE_XZ, 0f, ShapeColor)
                    circle(radius, PLANE_XY, 0f, ShapeColor)
                    circle(radius, PLANE_YZ, 0f, ShapeColor)
                    if (thickness < 1f) circle(radius * (1f - thickness), PLANE_XZ, 0f, ShapeFaint)
                    radiusHandle()
                }

                VfxShapeKind.DISC -> {
                    circle(radius, PLANE_XZ, 0f, ShapeColor)
                    if (thickness < 1f) circle(radius * (1f - thickness), PLANE_XZ, 0f, ShapeFaint)
                    radiusHandle()
                }

                VfxShapeKind.CONE -> cone(shape, radius)
                VfxShapeKind.BOX -> box(shape)
                VfxShapeKind.LINE -> line(shape)
            }
        }

        private fun cone(shape: VfxShape, radius: Float) {
            val angle = current(VfxProperty.SHAPE_ANGLE, 0, shape.angle, 25f).coerceIn(0f, 89f)
            val top = radius + tan(Math.toRadians(angle.toDouble())).toFloat() * CONE_PREVIEW_HEIGHT
            circle(radius, PLANE_XZ, 0f, ShapeColor)
            circle(top, PLANE_XZ, CONE_PREVIEW_HEIGHT, ShapeFaint)
            for (index in 0 until 4) {
                val around = index * (PI / 2.0).toFloat()
                segment(
                    world(cos(around) * radius, 0f, sin(around) * radius),
                    world(cos(around) * top, CONE_PREVIEW_HEIGHT, sin(around) * top),
                    ShapeFaint,
                )
            }
            if (editable(shape.radius)) {
                handle(
                    "radius", Vector3f(radius, 0f, 0f), Vector3f(1f, 0f, 0f), HandleColor,
                    readout = { (radius + it).coerceAtLeast(0f) },
                ) { node, delta ->
                    node.withShape { it.copy(radius = VfxValue.Const((radius + delta).coerceAtLeast(0f))) }
                }
            }
            if (editable(shape.angle)) {
                fun degreesFor(delta: Float): Float {
                    val spread = ((top + delta - radius) / CONE_PREVIEW_HEIGHT).coerceAtLeast(0f)
                    return Math.toDegrees(atan(spread).toDouble()).toFloat().coerceIn(0f, 89f)
                }
                handle(
                    "angle", Vector3f(top, CONE_PREVIEW_HEIGHT, 0f), Vector3f(1f, 0f, 0f), AngleColor,
                    readout = ::degreesFor,
                ) { node, delta -> node.withShape { it.copy(angle = VfxValue.Const(degreesFor(delta))) } }
            }
        }

        private fun box(shape: VfxShape) {
            val extents = shape.extents
            val x = current(VfxProperty.SHAPE_EXTENTS, 0, extents.x, 0.5f)
            val y = current(VfxProperty.SHAPE_EXTENTS, 1, extents.y, 0.5f)
            val z = current(VfxProperty.SHAPE_EXTENTS, 2, extents.z, 0.5f)
            val corners = listOf(
                world(-x, -y, -z), world(x, -y, -z), world(x, -y, z), world(-x, -y, z),
                world(-x, y, -z), world(x, y, -z), world(x, y, z), world(-x, y, z),
            )
            polyline(corners.subList(0, 4), ShapeColor, closed = true)
            polyline(corners.subList(4, 8), ShapeColor, closed = true)
            for (index in 0 until 4) segment(corners[index], corners[index + 4], ShapeColor)

            if (editable(extents.x)) {
                handle(
                    "extent-x",
                    Vector3f(x, 0f, 0f),
                    Vector3f(1f, 0f, 0f),
                    AxisX,
                    { (x + it).coerceAtLeast(0f) }) { node, delta ->
                    node.withShape { it.copy(extents = it.extents.copy(x = VfxValue.Const((x + delta).coerceAtLeast(0f)))) }
                }
            }
            if (editable(extents.y)) {
                handle(
                    "extent-y",
                    Vector3f(0f, y, 0f),
                    Vector3f(0f, 1f, 0f),
                    AxisY,
                    { (y + it).coerceAtLeast(0f) }) { node, delta ->
                    node.withShape { it.copy(extents = it.extents.copy(y = VfxValue.Const((y + delta).coerceAtLeast(0f)))) }
                }
            }
            if (editable(extents.z)) {
                handle(
                    "extent-z",
                    Vector3f(0f, 0f, z),
                    Vector3f(0f, 0f, 1f),
                    AxisZ,
                    { (z + it).coerceAtLeast(0f) }) { node, delta ->
                    node.withShape { it.copy(extents = it.extents.copy(z = VfxValue.Const((z + delta).coerceAtLeast(0f)))) }
                }
            }
        }

        private fun line(shape: VfxShape) {
            val extents = shape.extents
            val end = Vector3f(
                current(VfxProperty.SHAPE_EXTENTS, 0, extents.x, 0.5f),
                current(VfxProperty.SHAPE_EXTENTS, 1, extents.y, 0.5f),
                current(VfxProperty.SHAPE_EXTENTS, 2, extents.z, 0.5f),
            )
            segment(world(0f, 0f, 0f), world(end.x, end.y, end.z), ShapeColor)

            val length = end.length()
            val editable = (0..2).all { channel ->
                editable(listOf(extents.x, extents.y, extents.z)[channel])
            }
            if (!editable || length <= 1.0e-4f) return

            val direction = Vector3f(end).div(length)
            handle("line-end", end, direction, HandleColor, { (length + it).coerceAtLeast(0f) }) { node, delta ->
                val stretched = Vector3f(direction).mul((length + delta).coerceAtLeast(0f))
                node.withShape {
                    it.copy(
                        extents = it.extents.copy(
                            x = VfxValue.Const(stretched.x),
                            y = VfxValue.Const(stretched.y),
                            z = VfxValue.Const(stretched.z),
                        )
                    )
                }
            }
        }
    }

    private fun VfxNodeSpec.withShape(change: (VfxShape) -> VfxShape): VfxNodeSpec {
        val emitter = this as? VfxEmitterSpec ?: return this
        return emitter.copy(shape = change(emitter.shape))
    }

    private const val CIRCLE_SEGMENTS = 40
    private const val PLANE_XZ = 0
    private const val PLANE_XY = 1
    private const val PLANE_YZ = 2
    private const val CONE_PREVIEW_HEIGHT = 1f
    private const val NEAR_CLIP = 0.06f
    private const val SCREEN_LIMIT = 8000f

    private const val FINE_FACTOR = 0.1f
    private const val TICK = 0.1f
    private const val FINE_TICK = 0.01f

    private val ShapeColor = UiColor(0.98f, 0.78f, 0.35f, 0.9f)
    private val ShapeFaint = UiColor(0.98f, 0.78f, 0.35f, 0.45f)
    private val HandleColor = UiColor(1f, 0.9f, 0.55f, 1f)
    private val AngleColor = UiColor(0.75f, 0.55f, 1f, 1f)
    private val AxisX = UiColor(0.91f, 0.36f, 0.36f, 1f)
    private val AxisY = UiColor(0.45f, 0.82f, 0.4f, 1f)
    private val AxisZ = UiColor(0.4f, 0.6f, 0.98f, 1f)
}
