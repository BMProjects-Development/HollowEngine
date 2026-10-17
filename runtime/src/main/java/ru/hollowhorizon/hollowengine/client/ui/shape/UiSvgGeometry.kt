package ru.hollowhorizon.hollowengine.client.ui.shape

import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import java.awt.BasicStroke
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import kotlin.math.*
import java.awt.Shape as AwtShape

data class UiSvgPathElement(
    val path: UiPath,
    val style: UiSvgStyle = UiSvgStyle.Default,
    val id: String? = null,
    val paint: UiPaint = style.fillColor()?.let(UiPaint::Color) ?: UiPaint.None,
    val filterEffects: List<UiSvgFilterEffect> = emptyList(),
)

sealed interface UiSvgFilterEffect {
    data class GaussianBlur(val standardDeviation: Float) : UiSvgFilterEffect

    data class DropShadow(
        val offsetX: Float,
        val offsetY: Float,
        val standardDeviation: Float,
        val color: UiColor,
    ) : UiSvgFilterEffect
}

enum class UiSvgStrokeLineCap {
    BUTT,
    ROUND,
    SQUARE,
}

enum class UiSvgStrokeLineJoin {
    MITER,
    ROUND,
    BEVEL,
}

enum class UiSvgTextAnchor {
    START,
    MIDDLE,
    END,
}

/** Which parts of a self-overlapping path count as inside it. */
enum class UiSvgFillRule {
    NON_ZERO,
    EVEN_ODD,
}

data class UiSvgStyle(
    val fill: UiColor? = UiColor.Black,
    /** A `url(#id)` fill: the gradient or pattern the paint comes from instead of [fill]. */
    val fillRef: String? = null,
    val fillRule: UiSvgFillRule = UiSvgFillRule.NON_ZERO,
    val stroke: UiColor? = null,
    val strokeRef: String? = null,
    val strokeWidth: Float = 1f,
    val strokeLineCap: UiSvgStrokeLineCap = UiSvgStrokeLineCap.BUTT,
    val strokeLineJoin: UiSvgStrokeLineJoin = UiSvgStrokeLineJoin.MITER,
    val color: UiColor = UiColor.Black,
    val opacity: Float = 1f,
    val fillOpacity: Float = 1f,
    val strokeOpacity: Float = 1f,
    val fontFamily: String = "Serif",
    val fontSize: Float = 16f,
    val textAnchor: UiSvgTextAnchor = UiSvgTextAnchor.START,
    val display: Boolean = true,
    val visibility: Boolean = true,
    val clipPath: String? = null,
    val mask: String? = null,
    val filter: String? = null,
) {
    fun fillColor(): UiColor? = fill?.withAlphaMultiplier(opacity * fillOpacity)

    fun strokeColor(): UiColor? = stroke?.withAlphaMultiplier(opacity * strokeOpacity)

    /** How much a referenced fill is faded by the element's own opacity. */
    fun fillAlpha(): Float = opacity * fillOpacity

    fun strokeAlpha(): Float = opacity * strokeOpacity

    companion object {
        val Default = UiSvgStyle()
    }
}

data class UiSvgTransform(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    fun transform(point: UiPathPoint): UiPathPoint {
        return UiPathPoint(
            x = a * point.x + c * point.y + e,
            y = b * point.x + d * point.y + f,
        )
    }

    operator fun times(other: UiSvgTransform): UiSvgTransform {
        return UiSvgTransform(
            a = a * other.a + c * other.b,
            b = b * other.a + d * other.b,
            c = a * other.c + c * other.d,
            d = b * other.c + d * other.d,
            e = a * other.e + c * other.f + e,
            f = b * other.e + d * other.f + f,
        )
    }

    fun isIdentity(): Boolean = this == Identity

    companion object {
        val Identity = UiSvgTransform(1f, 0f, 0f, 1f, 0f, 0f)

        fun translation(x: Float, y: Float) = UiSvgTransform(1f, 0f, 0f, 1f, x, y)

        fun scale(x: Float, y: Float) = UiSvgTransform(x, 0f, 0f, y, 0f, 0f)

        fun rotation(degrees: Float): UiSvgTransform {
            val radians = degrees / 180f * PI.toFloat()
            val cos = cos(radians)
            val sin = sin(radians)
            return UiSvgTransform(cos, sin, -sin, cos, 0f, 0f)
        }

        fun rotation(degrees: Float, centerX: Float, centerY: Float): UiSvgTransform {
            return translation(centerX, centerY) * rotation(degrees) * translation(-centerX, -centerY)
        }

        fun skewX(degrees: Float): UiSvgTransform {
            val radians = degrees / 180f * PI.toFloat()
            return UiSvgTransform(1f, 0f, tan(radians), 1f, 0f, 0f)
        }

        fun skewY(degrees: Float): UiSvgTransform {
            val radians = degrees / 180f * PI.toFloat()
            return UiSvgTransform(1f, tan(radians), 0f, 1f, 0f, 0f)
        }
    }
}

internal fun UiPath.transformed(transform: UiSvgTransform): UiPath {
    if (transform.isIdentity()) return this
    fun UiPathPoint.t() = transform.transform(this)
    val transformed = ArrayList<UiPathCommand>(commands.size)
    var current = UiPathPoint(0f, 0f)
    var subPathStart = current
    commands.forEach { command ->
        when (command) {
            is UiPathCommand.MoveTo -> {
                transformed += command.copy(target = command.target.t())
                current = command.target
                subPathStart = current
            }

            is UiPathCommand.LineTo -> {
                transformed += command.copy(target = command.target.t())
                current = command.target
            }

            is UiPathCommand.CubicTo -> {
                transformed += command.copy(
                    control1 = command.control1.t(),
                    control2 = command.control2.t(),
                    target = command.target.t(),
                )
                current = command.target
            }

            is UiPathCommand.QuadraticTo -> {
                transformed += command.copy(
                    control = command.control.t(),
                    target = command.target.t(),
                )
                current = command.target
            }

            is UiPathCommand.ArcTo -> {
                arcToCubics(current, command).forEach { cubic ->
                    transformed += cubic.copy(
                        control1 = cubic.control1.t(),
                        control2 = cubic.control2.t(),
                        target = cubic.target.t(),
                    )
                }
                current = command.target
            }

            UiPathCommand.Close -> {
                transformed += UiPathCommand.Close
                current = subPathStart
            }
        }
    }
    return UiPath(transformed)
}

internal fun UiPathGeometry.toPath(transform: UiSvgTransform = UiSvgTransform.Identity): UiPath {
    val builder = UiPathBuilder()
    contours.forEach { contour ->
        val points = contour.points
        if (points.isEmpty()) return@forEach
        val first = transform.transform(points.first())
        builder.moveTo(first.x, first.y)
        points.drop(1).forEach { point ->
            val transformed = transform.transform(point)
            builder.lineTo(transformed.x, transformed.y)
        }
        if (contour.closed) builder.close()
    }
    return builder.build()
}

internal fun combineSvgPaths(paths: List<UiPath>): UiPath {
    if (paths.isEmpty()) return UiPath(emptyList())
    if (paths.size == 1) return paths.first()
    val area = Area()
    for (path in paths) area.add(Area(path.toAwtPath()))
    return area.toUiPath()
}

/**
 * Flattens a `<mask>` into the area it lets through.
 */
internal fun combineSvgMask(shapes: List<Pair<UiPath, UiColor>>): UiPath {
    if (shapes.isEmpty()) return UiPath(emptyList())
    val area = Area()
    for ((path, color) in shapes) {
        val shape = Area(path.toAwtPath())
        if (color.isMaskOpaque()) area.add(shape) else area.subtract(shape)
    }
    return area.toUiPath()
}

/** Bright enough to let what it covers through; anything darker punches a hole instead. */
private fun UiColor.isMaskOpaque(): Boolean = alpha > 0.01f && (red + green + blue) / 3f > 0.5f

internal fun UiPath.toSvgStrokePath(style: UiSvgStyle): UiPath? {
    val strokeWidth = style.strokeWidth
    if (style.strokeColor() == null || strokeWidth <= 0f || isEmpty()) return null
    return BasicStroke(
        strokeWidth,
        style.strokeLineCap.toAwtStrokeCap(),
        style.strokeLineJoin.toAwtStrokeJoin(),
    ).createStrokedShape(toAwtPath()).toUiPath()
}

internal fun rectPath(x: Float, y: Float, width: Float, height: Float): UiPath {
    if (width <= 0f || height <= 0f) return UiPath.Empty
    return path {
        moveTo(x, y)
        lineTo(x + width, y)
        lineTo(x + width, y + height)
        lineTo(x, y + height)
        close()
    }
}

/**
 * Rewrites an even-odd path into contours that a plain non-zero fill draws identically, so the
 * tessellator downstream never has to know about fill rules.
 */
internal fun UiPath.withFillRule(rule: UiSvgFillRule): UiPath {
    if (rule == UiSvgFillRule.NON_ZERO || isEmpty()) return this
    val evenOdd = toAwtPath().also { it.setWindingRule(Path2D.WIND_EVEN_ODD) }
    return Area(evenOdd).toUiPath()
}

internal fun UiPath.intersectedWith(mask: UiPath): UiPath {
    if (isEmpty() || mask.isEmpty()) return UiPath.Empty
    val area = Area(toAwtPath())
    area.intersect(Area(mask.toAwtPath()))
    return area.toUiPath()
}

internal fun UiPath.toAwtPath(): Path2D.Float {
    val path = Path2D.Float(Path2D.WIND_NON_ZERO)
    var current = UiPathPoint(0f, 0f)
    var subPathStart = UiPathPoint(0f, 0f)
    commands.forEach { command ->
        when (command) {
            is UiPathCommand.MoveTo -> {
                path.moveTo(command.target.x, command.target.y)
                current = command.target
                subPathStart = command.target
            }

            is UiPathCommand.LineTo -> {
                path.lineTo(command.target.x, command.target.y)
                current = command.target
            }

            is UiPathCommand.CubicTo -> path.curveTo(
                command.control1.x,
                command.control1.y,
                command.control2.x,
                command.control2.y,
                command.target.x,
                command.target.y,
            ).also { current = command.target }

            is UiPathCommand.QuadraticTo -> path.quadTo(
                command.control.x,
                command.control.y,
                command.target.x,
                command.target.y,
            ).also { current = command.target }

            is UiPathCommand.ArcTo -> {
                arcToCubics(current, command).forEach { cubic ->
                    path.curveTo(
                        cubic.control1.x,
                        cubic.control1.y,
                        cubic.control2.x,
                        cubic.control2.y,
                        cubic.target.x,
                        cubic.target.y,
                    )
                }
                current = command.target
            }

            UiPathCommand.Close -> {
                path.closePath()
                current = subPathStart
            }
        }
    }
    return path
}

private fun arcToCubics(from: UiPathPoint, command: UiPathCommand.ArcTo): List<UiPathCommand.CubicTo> {
    var radiusX = abs(command.radiusX).toDouble()
    var radiusY = abs(command.radiusY).toDouble()
    val target = command.target
    if (radiusX <= 0.0 || radiusY <= 0.0 || from == target) {
        val first = UiPathPoint(
            from.x + (target.x - from.x) / 3f,
            from.y + (target.y - from.y) / 3f,
        )
        val second = UiPathPoint(
            from.x + (target.x - from.x) * 2f / 3f,
            from.y + (target.y - from.y) * 2f / 3f,
        )
        return listOf(UiPathCommand.CubicTo(first, second, target))
    }

    val phi = command.xAxisRotation.toDouble() * PI / 180.0
    val cosPhi = cos(phi)
    val sinPhi = sin(phi)
    val dx = (from.x - target.x).toDouble() * 0.5
    val dy = (from.y - target.y).toDouble() * 0.5
    val x1p = cosPhi * dx + sinPhi * dy
    val y1p = -sinPhi * dx + cosPhi * dy
    val lambda = x1p * x1p / (radiusX * radiusX) + y1p * y1p / (radiusY * radiusY)
    if (lambda > 1.0) {
        val scale = sqrt(lambda)
        radiusX *= scale
        radiusY *= scale
    }

    val rx2 = radiusX * radiusX
    val ry2 = radiusY * radiusY
    val numerator = max(0.0, rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p)
    val denominator = (rx2 * y1p * y1p + ry2 * x1p * x1p).coerceAtLeast(0.000001)
    val sign = if (command.largeArc == command.sweep) -1.0 else 1.0
    val coefficient = sign * sqrt(numerator / denominator)
    val cxp = coefficient * radiusX * y1p / radiusY
    val cyp = coefficient * -radiusY * x1p / radiusX
    val centerX = cosPhi * cxp - sinPhi * cyp + (from.x + target.x) * 0.5
    val centerY = sinPhi * cxp + cosPhi * cyp + (from.y + target.y) * 0.5
    val startAngle = atan2((y1p - cyp) / radiusY, (x1p - cxp) / radiusX)
    var sweepAngle = atan2(
        (x1p - cxp) / radiusX * (-y1p - cyp) / radiusY -
                (y1p - cyp) / radiusY * (-x1p - cxp) / radiusX,
        (x1p - cxp) / radiusX * (-x1p - cxp) / radiusX +
                (y1p - cyp) / radiusY * (-y1p - cyp) / radiusY,
    )
    if (!command.sweep && sweepAngle > 0.0) sweepAngle -= PI * 2.0
    if (command.sweep && sweepAngle < 0.0) sweepAngle += PI * 2.0

    val segmentCount = ceil(abs(sweepAngle) / (PI * 0.5)).toInt().coerceAtLeast(1)
    val step = sweepAngle / segmentCount
    return List(segmentCount) { index ->
        val firstAngle = startAngle + step * index
        val lastAngle = firstAngle + step
        val alpha = 4.0 / 3.0 * tan(step * 0.25)
        val firstPoint = ellipsePoint(centerX, centerY, radiusX, radiusY, cosPhi, sinPhi, firstAngle)
        val lastPoint = if (index == segmentCount - 1) target else
            ellipsePoint(centerX, centerY, radiusX, radiusY, cosPhi, sinPhi, lastAngle)
        val firstDerivative = ellipseDerivative(radiusX, radiusY, cosPhi, sinPhi, firstAngle)
        val lastDerivative = ellipseDerivative(radiusX, radiusY, cosPhi, sinPhi, lastAngle)
        UiPathCommand.CubicTo(
            UiPathPoint(
                (firstPoint.x + alpha * firstDerivative.x).toFloat(),
                (firstPoint.y + alpha * firstDerivative.y).toFloat(),
            ),
            UiPathPoint(
                (lastPoint.x - alpha * lastDerivative.x).toFloat(),
                (lastPoint.y - alpha * lastDerivative.y).toFloat(),
            ),
            lastPoint,
        )
    }
}

private fun ellipsePoint(
    centerX: Double,
    centerY: Double,
    radiusX: Double,
    radiusY: Double,
    cosPhi: Double,
    sinPhi: Double,
    angle: Double,
) = UiPathPoint(
    (centerX + cosPhi * radiusX * cos(angle) - sinPhi * radiusY * sin(angle)).toFloat(),
    (centerY + sinPhi * radiusX * cos(angle) + cosPhi * radiusY * sin(angle)).toFloat(),
)

private fun ellipseDerivative(
    radiusX: Double,
    radiusY: Double,
    cosPhi: Double,
    sinPhi: Double,
    angle: Double,
) = UiPathPoint(
    (-cosPhi * radiusX * sin(angle) - sinPhi * radiusY * cos(angle)).toFloat(),
    (-sinPhi * radiusX * sin(angle) + cosPhi * radiusY * cos(angle)).toFloat(),
)

internal fun AwtShape.toUiPath(): UiPath {
    val builder = UiPathBuilder()
    val iterator = getPathIterator(null)
    val coordinates = FloatArray(6)
    while (!iterator.isDone) {
        when (iterator.currentSegment(coordinates)) {
            PathIterator.SEG_MOVETO -> builder.moveTo(coordinates[0], coordinates[1])
            PathIterator.SEG_LINETO -> builder.lineTo(coordinates[0], coordinates[1])
            PathIterator.SEG_QUADTO -> builder.quadraticBezierTo(
                coordinates[0],
                coordinates[1],
                coordinates[2],
                coordinates[3],
            )

            PathIterator.SEG_CUBICTO -> builder.curveTo(
                coordinates[0],
                coordinates[1],
                coordinates[2],
                coordinates[3],
                coordinates[4],
                coordinates[5],
            )

            PathIterator.SEG_CLOSE -> builder.close()
        }
        iterator.next()
    }
    return builder.build()
}

internal fun UiColor.withAlphaMultiplier(multiplier: Float): UiColor {
    return copy(alpha = alpha * multiplier.coerceAtLeast(0f))
}

private fun UiSvgStrokeLineCap.toAwtStrokeCap(): Int {
    return when (this) {
        UiSvgStrokeLineCap.BUTT -> BasicStroke.CAP_BUTT
        UiSvgStrokeLineCap.ROUND -> BasicStroke.CAP_ROUND
        UiSvgStrokeLineCap.SQUARE -> BasicStroke.CAP_SQUARE
    }
}

private fun UiSvgStrokeLineJoin.toAwtStrokeJoin(): Int {
    return when (this) {
        UiSvgStrokeLineJoin.MITER -> BasicStroke.JOIN_MITER
        UiSvgStrokeLineJoin.ROUND -> BasicStroke.JOIN_ROUND
        UiSvgStrokeLineJoin.BEVEL -> BasicStroke.JOIN_BEVEL
    }
}
