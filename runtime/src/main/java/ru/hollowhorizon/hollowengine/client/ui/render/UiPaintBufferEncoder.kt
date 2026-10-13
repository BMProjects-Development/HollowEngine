package ru.hollowhorizon.hollowengine.client.ui.render

import ru.hollowhorizon.hollowengine.client.ui.UiResolvedPaint
import ru.hollowhorizon.hollowengine.client.ui.resolve
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.linearGradientRadians
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal class UiPaintBufferEncoder(
    private val paints: UiFloatArrayBuilder,
    private val stops: UiFloatArrayBuilder,
) {
    fun append(
        paint: UiResolvedPaint,
        opacity: Float,
        filter: UiFilterChain,
        width: Float,
        height: Float,
    ): Int {
        val paintIndex = paints.size / PaintStride
        when (paint) {
            is UiResolvedPaint.Color -> {
                val color = paint.color.filtered(filter)
                paints.add(PaintSolid, 0f, 0f, opacity)
                paints.add(color.red, color.green, color.blue, color.alpha)
                paints.add(width, height, 0f, 0f)
                paints.add(0f, 0f, 0f, 0f)
            }
            is UiResolvedPaint.LinearGradient -> {
                val stopStart = stops.size / StopStride
                appendStops(paint.stops, filter)
                val radians = linearGradientRadians(paint.angleDegrees)
                paints.add(
                    PaintLinearGradient,
                    stopStart.toFloat(),
                    paint.stops.size.toFloat(),
                    opacity,
                )
                paints.add(0f, 0f, 0f, 0f)
                paints.add(width, height, cos(radians).toFloat(), sin(radians).toFloat())
                paints.add(0f, 0f, 0f, 0f)
            }
            is UiResolvedPaint.RadialGradient -> {
                val stopStart = stops.size / StopStride
                appendStops(paint.gradient.stops, filter)
                paints.add(
                    PaintRadialGradient,
                    stopStart.toFloat(),
                    paint.gradient.stops.size.toFloat(),
                    opacity,
                )
                paints.add(0f, 0f, 0f, 0f)
                paints.add(width, height, 0f, 0f)
                paints.add(
                    paint.gradient.centerX.resolve(width),
                    paint.gradient.centerY.resolve(height),
                    paint.gradient.radius.resolve(max(width, height)).coerceAtLeast(0.0001f),
                    0f,
                )
            }
            UiResolvedPaint.None,
            is UiResolvedPaint.Image,
            is UiResolvedPaint.Shader,
                -> error("Unsupported GPU paint $paint")
        }
        return paintIndex
    }

    private fun appendStops(source: List<UiGradientStop>, filter: UiFilterChain) {
        for (stop in source) {
            val color = stop.color.filtered(filter)
            stops.add(color.red, color.green, color.blue, color.alpha)
            stops.add(stop.offset, 0f, 0f, 0f)
        }
    }

    private companion object {
        const val PaintStride = 16
        const val StopStride = 8
        const val PaintSolid = 0f
        const val PaintLinearGradient = 1f
        const val PaintRadialGradient = 2f
    }
}

internal fun UiResolvedPaint.isBufferPaint(): Boolean = when (this) {
    is UiResolvedPaint.Color,
    is UiResolvedPaint.LinearGradient,
    is UiResolvedPaint.RadialGradient,
        -> true
    UiResolvedPaint.None,
    is UiResolvedPaint.Image,
    is UiResolvedPaint.Shader,
        -> false
}
