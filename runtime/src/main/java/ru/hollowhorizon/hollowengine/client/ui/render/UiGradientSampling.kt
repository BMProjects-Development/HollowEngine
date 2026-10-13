package ru.hollowhorizon.hollowengine.client.ui.render

import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.linearGradientRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal fun gradientColorAt(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    angleDegrees: Float,
    stops: List<UiGradientStop>,
): UiColor {
    if (stops.isEmpty()) return UiColor.Transparent
    if (stops.size == 1) return stops.first().color
    val radians = linearGradientRadians(angleDegrees)
    val directionX = cos(radians).toFloat()
    val directionY = sin(radians).toFloat()
    val projection = (x - width * 0.5f) * directionX + (y - height * 0.5f) * directionY
    val extent = abs(directionX) * width * 0.5f + abs(directionY) * height * 0.5f
    val offset = if (extent <= 0f) 0f else (projection / extent + 1f) * 0.5f
    val clamped = offset.coerceIn(0f, 1f)
    val right = stops.firstOrNull { it.offset >= clamped } ?: stops.last()
    val left = stops.lastOrNull { it.offset <= clamped } ?: stops.first()
    if (left == right) return left.color
    val range = (right.offset - left.offset).coerceAtLeast(0.0001f)
    return left.color.interpolate(right.color, (clamped - left.offset) / range)
}
