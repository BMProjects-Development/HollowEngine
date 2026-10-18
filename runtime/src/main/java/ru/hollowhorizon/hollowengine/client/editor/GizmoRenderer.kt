package ru.hollowhorizon.hollowengine.client.editor

import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.UiDrawStyle
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint

/**
 * Draws gizmo geometry onto a canvas, the same way wherever the gizmo is shown: over the world, or
 * over a preview panel.
 */
object GizmoRenderer {
    /** Screen span beyond which a polyline is treated as degenerate and skipped (guards the tiler). */
    private const val MAX_DRAW_SPAN = 8000f

    private val SectorFill = UiColor(1f, 0.85f, 0.32f, 0.25f)
    private val SectorStroke = UiColor(1f, 0.86f, 0.34f, 0.9f)

    /** [handles] far to near; the one being dragged, or else the one under the pointer, lit up. */
    fun drawHandles(
        scope: UiCanvasDrawScope,
        handles: List<GizmoHandle>,
        hovered: GizmoHandleId?,
        dragging: GizmoHandleId?,
    ) {
        for (handle in handles.sortedByDescending { it.depth }) {
            val highlighted = handle.id == dragging || (dragging == null && handle.id == hovered)
            val base = if (highlighted) GizmoColors.highlighted(handle.color) else handle.color
            val emphasis = if (highlighted) 1f else handle.emphasis
            handle.fillPolygon?.let { fill ->
                val alpha = (if (handle.id.isSolidHandle()) 0.72f else 0.22f) * emphasis
                fillPolygon(scope, fill, base.withAlpha(alpha))
            }
            for (stroke in handle.renderLines) {
                val strokeEmphasis = if (highlighted) 1f else emphasis * stroke.emphasis
                strokeLine(
                    scope,
                    stroke.points,
                    base.withAlpha(base.alpha * strokeEmphasis),
                    handle.width * (0.55f + 0.45f * strokeEmphasis),
                    stroke.closed,
                )
            }
        }
    }

    /** The angle a rotation drag has swept so far, as a filled sector on its ring. */
    fun drawRotationSector(scope: UiCanvasDrawScope, geometry: GizmoGeometry, projector: GizmoProjector, drag: GizmoDrag) {
        if (!drag.handleId.isRotation()) return
        val axis = drag.axis ?: return
        val perPixel = projector.worldPerPixel(drag.origin)
        val sector = geometry.buildRotationSector(drag.origin, axis, drag.startAngle, drag.angle, perPixel) ?: return
        fillPolygon(scope, sector, SectorFill)
        strokeLine(scope, sector, SectorStroke, 1.5f, closed = true)
    }

    fun strokeLine(
        scope: UiCanvasDrawScope,
        points: List<Pt>,
        color: UiColor,
        width: Float,
        closed: Boolean = false,
    ) {
        if (points.size < 2 || !withinDrawBounds(points)) return
        val shape = GenericShape {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            if (closed) close()
        }
        scope.drawShape(shape, UiPaint.Color(color), UiDrawStyle.Stroke(width))
    }

    fun fillPolygon(scope: UiCanvasDrawScope, points: List<Pt>, color: UiColor) {
        if (points.size < 3 || !withinDrawBounds(points)) return
        val shape = GenericShape {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        scope.drawShape(shape, UiPaint.Color(color), UiDrawStyle.Fill)
    }

    private fun withinDrawBounds(points: List<Pt>): Boolean {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite()) return false
            minX = minOf(minX, point.x); minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x); maxY = maxOf(maxY, point.y)
        }
        return (maxX - minX) <= MAX_DRAW_SPAN && (maxY - minY) <= MAX_DRAW_SPAN
    }

    private fun UiColor.withAlpha(alpha: Float) = UiColor(red, green, blue, alpha)

    private fun GizmoHandleId.isSolidHandle(): Boolean = when (this) {
        GizmoHandleId.AXIS_X, GizmoHandleId.AXIS_Y, GizmoHandleId.AXIS_Z,
        GizmoHandleId.SCALE_X, GizmoHandleId.SCALE_Y, GizmoHandleId.SCALE_Z, GizmoHandleId.SCALE_UNIFORM -> true
        else -> false
    }
}

fun GizmoHandleId.isRotation(): Boolean =
    this == GizmoHandleId.ROTATE_X || this == GizmoHandleId.ROTATE_Y || this == GizmoHandleId.ROTATE_Z

/** The manipulator a handle belongs to. */
val GizmoHandleId.editMode: GizmoEditMode
    get() = when (this) {
        GizmoHandleId.SCALE_X, GizmoHandleId.SCALE_Y, GizmoHandleId.SCALE_Z, GizmoHandleId.SCALE_UNIFORM -> GizmoEditMode.SCALE
        else -> if (isRotation()) GizmoEditMode.ROTATE else GizmoEditMode.TRANSLATE
    }
