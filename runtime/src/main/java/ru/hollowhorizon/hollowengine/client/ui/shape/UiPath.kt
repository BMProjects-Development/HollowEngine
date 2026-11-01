package ru.hollowhorizon.hollowengine.client.ui.shape

import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import kotlin.math.max
import kotlin.math.min

data class UiPathPoint(
    val x: Float,
    val y: Float,
)

data class UiPath(
    val commands: List<UiPathCommand>,
) {
    private var hash = 0

    fun isEmpty(): Boolean = commands.isEmpty()

    override fun hashCode(): Int {
        if (hash == 0) hash = commands.hashCode()
        return hash
    }

    fun bounds(): UiRect? {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        var hasPoint = false

        fun include(point: UiPathPoint) {
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
            hasPoint = true
        }

        commands.forEach { command ->
            when (command) {
                is UiPathCommand.MoveTo -> include(command.target)
                is UiPathCommand.LineTo -> include(command.target)
                is UiPathCommand.CubicTo -> {
                    include(command.control1)
                    include(command.control2)
                    include(command.target)
                }

                is UiPathCommand.QuadraticTo -> {
                    include(command.control)
                    include(command.target)
                }

                is UiPathCommand.ArcTo -> include(command.target)
                UiPathCommand.Close -> Unit
            }
        }
        if (!hasPoint) return null
        return UiRect(left, top, right - left, bottom - top)
    }

    fun transformed(scaleX: Float, scaleY: Float, translateX: Float = 0f, translateY: Float = 0f): UiPath {
        fun UiPathPoint.transform() = UiPathPoint(x * scaleX + translateX, y * scaleY + translateY)
        val transformed = commands.map { command ->
            when (command) {
                is UiPathCommand.MoveTo -> command.copy(target = command.target.transform())
                is UiPathCommand.LineTo -> command.copy(target = command.target.transform())
                is UiPathCommand.CubicTo -> command.copy(
                    control1 = command.control1.transform(),
                    control2 = command.control2.transform(),
                    target = command.target.transform(),
                )

                is UiPathCommand.QuadraticTo -> command.copy(
                    control = command.control.transform(),
                    target = command.target.transform(),
                )

                is UiPathCommand.ArcTo -> command.copy(
                    radiusX = command.radiusX * scaleX,
                    radiusY = command.radiusY * scaleY,
                    target = command.target.transform(),
                )

                UiPathCommand.Close -> UiPathCommand.Close
            }
        }
        return UiPath(transformed)
    }

    companion object {
        val Empty = UiPath(emptyList())
    }
}

sealed interface UiPathCommand {
    data class MoveTo(val target: UiPathPoint) : UiPathCommand
    data class LineTo(val target: UiPathPoint) : UiPathCommand
    data class CubicTo(
        val control1: UiPathPoint,
        val control2: UiPathPoint,
        val target: UiPathPoint,
    ) : UiPathCommand

    data class QuadraticTo(
        val control: UiPathPoint,
        val target: UiPathPoint,
    ) : UiPathCommand

    data class ArcTo(
        val radiusX: Float,
        val radiusY: Float,
        val xAxisRotation: Float,
        val largeArc: Boolean,
        val sweep: Boolean,
        val target: UiPathPoint,
    ) : UiPathCommand

    data object Close : UiPathCommand
}

class UiPathBuilder {
    private val commands = mutableListOf<UiPathCommand>()
    private var current = UiPathPoint(0f, 0f)
    private var subPathStart = UiPathPoint(0f, 0f)
    private var lastCubicControl: UiPathPoint? = null
    private var lastQuadraticControl: UiPathPoint? = null

    fun moveTo(x: Float, y: Float) {
        val target = UiPathPoint(x, y)
        commands += UiPathCommand.MoveTo(target)
        current = target
        subPathStart = target
        resetControls()
    }

    fun lineTo(x: Float, y: Float) {
        val target = UiPathPoint(x, y)
        commands += UiPathCommand.LineTo(target)
        current = target
        resetControls()
    }

    fun horizontalLineTo(x: Float) = lineTo(x, current.y)

    fun verticalLineTo(y: Float) = lineTo(current.x, y)

    fun curveTo(control1X: Float, control1Y: Float, control2X: Float, control2Y: Float, x: Float, y: Float) {
        val control1 = UiPathPoint(control1X, control1Y)
        val control2 = UiPathPoint(control2X, control2Y)
        val target = UiPathPoint(x, y)
        commands += UiPathCommand.CubicTo(control1, control2, target)
        current = target
        lastCubicControl = control2
        lastQuadraticControl = null
    }

    fun smoothCurveTo(control2X: Float, control2Y: Float, x: Float, y: Float) {
        val reflected = lastCubicControl?.reflect(current) ?: current
        curveTo(reflected.x, reflected.y, control2X, control2Y, x, y)
    }

    fun quadraticBezierTo(controlX: Float, controlY: Float, x: Float, y: Float) {
        val control = UiPathPoint(controlX, controlY)
        val target = UiPathPoint(x, y)
        commands += UiPathCommand.QuadraticTo(control, target)
        current = target
        lastCubicControl = null
        lastQuadraticControl = control
    }

    fun smoothQuadraticBezierTo(x: Float, y: Float) {
        val reflected = lastQuadraticControl?.reflect(current) ?: current
        quadraticBezierTo(reflected.x, reflected.y, x, y)
    }

    fun ellipticalArcTo(
        radiusX: Float,
        radiusY: Float,
        xAxisRotation: Float,
        largeArc: Boolean,
        sweep: Boolean,
        x: Float,
        y: Float,
    ) {
        val target = UiPathPoint(x, y)
        commands += UiPathCommand.ArcTo(radiusX, radiusY, xAxisRotation, largeArc, sweep, target)
        current = target
        resetControls()
    }

    fun close() {
        commands += UiPathCommand.Close
        current = subPathStart
        resetControls()
    }

    fun build(): UiPath = UiPath(commands.toList())

    private fun resetControls() {
        lastCubicControl = null
        lastQuadraticControl = null
    }

    private fun UiPathPoint.reflect(origin: UiPathPoint) = UiPathPoint(
        x = origin.x * 2f - x,
        y = origin.y * 2f - y,
    )
}
