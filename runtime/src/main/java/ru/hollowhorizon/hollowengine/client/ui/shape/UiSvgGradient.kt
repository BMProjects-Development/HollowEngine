package ru.hollowhorizon.hollowengine.client.ui.shape

import org.w3c.dom.Element
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.style.UiRadialGradient
import kotlin.math.*

/**
 * Resolves a `fill="url(#id)"` reference into a paint.
 */
internal fun resolveSvgPaint(
    reference: String?,
    viewBox: UiRect,
    transform: UiSvgTransform,
    objectBounds: UiRect?,
    alpha: Float,
    elementById: (String) -> Element?,
): UiPaint? {
    val id = parseUrlReference(reference)?.substringAfterLast('#')?.takeIf(String::isNotEmpty) ?: return null
    val element = elementById(id) ?: return null
    val stops = gradientStops(element, alpha, elementById).takeIf { it.isNotEmpty() } ?: return null
    val gradientTransform =
        element.gradientAttribute("gradientTransform")?.let(::parseSvgTransform) ?: UiSvgTransform.Identity
    val toBox = transform * gradientTransform
    val units = element.gradientAttribute("gradientUnits") ?: "objectBoundingBox"
    val bounds = objectBounds.takeIf { units.equals("objectBoundingBox", ignoreCase = true) }

    return when (element.svgName()) {
        "lineargradient" -> linearSvgPaint(element, viewBox, toBox, bounds, stops)
        "radialgradient" -> radialSvgPaint(element, viewBox, toBox, bounds, stops)
        else -> null
    }
}

private fun linearSvgPaint(
    element: Element,
    viewBox: UiRect,
    transform: UiSvgTransform,
    bounds: UiRect?,
    stops: List<UiGradientStop>,
): UiPaint {
    val start = element.gradientPoint("x1", "y1", 0f, 0f, bounds, transform, viewBox)
    val end = element.gradientPoint("x2", "y2", 1f, 0f, bounds, transform, viewBox)
    val length = hypot(end.x - start.x, end.y - start.y)
    if (length <= 0.0001f) return UiPaint.Color(stops.first().color)

    val directionX = (end.x - start.x) / length
    val directionY = (end.y - start.y) / length
    val extent = abs(directionX) * viewBox.width * 0.5f + abs(directionY) * viewBox.height * 0.5f
    if (extent <= 0.0001f) return UiPaint.Color(stops.first().color)

    fun boxOffset(x: Float, y: Float): Float {
        val projection = (x - viewBox.width * 0.5f) * directionX + (y - viewBox.height * 0.5f) * directionY
        return (projection / extent + 1f) * 0.5f
    }

    val from = boxOffset(start.x, start.y)
    val span = boxOffset(end.x, end.y) - from
    if (abs(span) <= 0.0001f) return UiPaint.Color(stops.first().color)

    val angle = Math.toDegrees(atan2(directionX, -directionY).toDouble()).toFloat().mod(360f)
    return UiPaint.LinearGradient(angle, stops.remappedTo(from, span))
}

private fun radialSvgPaint(
    element: Element,
    viewBox: UiRect,
    transform: UiSvgTransform,
    bounds: UiRect?,
    stops: List<UiGradientStop>,
): UiPaint {
    val center = element.gradientPoint("cx", "cy", 0.5f, 0.5f, bounds, transform, viewBox)
    val authored = element.gradientLength("r", 0.5f, bounds?.let { max(it.width, it.height) })
    val radius = (authored * transform.scaleMagnitude()).coerceAtLeast(0.0001f)
    val reference = max(viewBox.width, viewBox.height).coerceAtLeast(0.0001f)
    return UiPaint.RadialGradient(
        UiRadialGradient(
            centerX = (center.x / viewBox.width.coerceAtLeast(0.0001f) * 100f).percent,
            centerY = (center.y / viewBox.height.coerceAtLeast(0.0001f) * 100f).percent,
            radius = (radius / reference * 100f).percent,
            stops = stops,
        ),
    )
}

private fun List<UiGradientStop>.remappedTo(from: Float, span: Float): List<UiGradientStop> {
    val moved = map { stop -> stop.copy(offset = from + stop.offset * span) }.sortedBy { it.offset }
    val inside = moved.filter { it.offset > 0f && it.offset < 1f }
    return buildList {
        add(UiGradientStop(0f, moved.colorAt(0f)))
        addAll(inside)
        add(UiGradientStop(1f, moved.colorAt(1f)))
    }
}

private fun List<UiGradientStop>.colorAt(offset: Float): UiColor {
    val right = firstOrNull { it.offset >= offset } ?: return last().color
    val left = lastOrNull { it.offset <= offset } ?: return first().color
    if (left === right || right.offset - left.offset <= 0.0001f) return left.color
    return left.color.interpolate(right.color, (offset - left.offset) / (right.offset - left.offset))
}

private fun gradientStops(
    element: Element,
    alpha: Float,
    elementById: (String) -> Element?,
    seen: Set<Element> = emptySet(),
): List<UiGradientStop> {
    if (element in seen) return emptyList()
    val own = element.elementChildren().filter { it.svgName() == "stop" }
        .mapIndexedNotNull { index, stop -> stop.toGradientStop(index, element, alpha) }
    if (own.isNotEmpty()) return own.sortedBy { it.offset }

    val inherited = element.gradientAttribute("href") ?: element.getAttribute("xlink:href").takeIf(String::isNotBlank)
    val id = inherited?.substringAfterLast('#')?.takeIf(String::isNotEmpty) ?: return emptyList()
    val source = elementById(id) ?: return emptyList()
    return gradientStops(source, alpha, elementById, seen + element)
}

private fun Element.toGradientStop(index: Int, owner: Element, alpha: Float): UiGradientStop? {
    val color = parseSvgColor(getAttribute("stop-color").trim().ifEmpty { "black" }) ?: return null
    val stopOpacity = getAttribute("stop-opacity").trim().toFloatOrNull() ?: 1f
    val offset = getAttribute("offset").trim().let { raw ->
        when {
            raw.isEmpty() -> if (index == 0) 0f else 1f
            raw.endsWith('%') -> raw.dropLast(1).toFloatOrNull()?.div(100f)
            else -> raw.toFloatOrNull()
        } ?: return@let if (index == 0) 0f else 1f
    }
    val ownerOpacity = owner.getAttribute("opacity").trim().toFloatOrNull() ?: 1f
    return UiGradientStop(
        offset = offset.coerceIn(0f, 1f),
        color = color.withAlphaMultiplier(stopOpacity * ownerOpacity * alpha),
    )
}

private fun Element.gradientPoint(
    xName: String,
    yName: String,
    defaultX: Float,
    defaultY: Float,
    bounds: UiRect?,
    transform: UiSvgTransform,
    viewBox: UiRect,
): UiPathPoint {
    val rawX = gradientLength(xName, defaultX, bounds?.width)
    val rawY = gradientLength(yName, defaultY, bounds?.height)
    val userX = if (bounds == null) rawX else bounds.x + rawX
    val userY = if (bounds == null) rawY else bounds.y + rawY
    val point = transform.transform(UiPathPoint(userX, userY))
    return UiPathPoint(point.x - viewBox.x, point.y - viewBox.y)
}

private fun Element.gradientLength(name: String, default: Float, boundsExtent: Float?): Float {
    val raw = getAttribute(name).trim()
    if (boundsExtent != null) {
        val fraction = when {
            raw.isEmpty() -> default
            raw.endsWith('%') -> raw.dropLast(1).toFloatOrNull()?.div(100f) ?: default
            else -> raw.toFloatOrNull() ?: default
        }
        return fraction * boundsExtent
    }
    if (raw.isEmpty()) return default
    return parseSvgLength(raw) ?: default
}

private fun Element.gradientAttribute(name: String): String? = getAttribute(name).trim().takeIf(String::isNotEmpty)

private fun UiSvgTransform.scaleMagnitude(): Float = sqrt(abs(a * d - b * c)).takeIf { it > 0f } ?: 1f
