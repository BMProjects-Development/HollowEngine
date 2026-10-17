package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.renderer.GameRenderer
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.shape.*
import ru.hollowhorizon.hollowengine.client.ui.style.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

internal fun UiTriangleBatch.appendSolidQuad(
    width: Float,
    height: Float,
    color: UiColor,
    transform: UiMatrix4,
) {
    appendGradientQuad(width, height, color, color, color, color, transform)
}

internal fun UiTriangleBatch.appendGradientQuad(
    width: Float,
    height: Float,
    angleDegrees: Float,
    stops: List<UiGradientStop>,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    appendGradientQuad(
        width = width,
        height = height,
        topLeft = gradientColorAt(0f, 0f, width, height, angleDegrees, stops).withOpacity(opacity).filtered(filter),
        bottomLeft = gradientColorAt(0f, height, width, height, angleDegrees, stops).withOpacity(opacity)
            .filtered(filter),
        bottomRight = gradientColorAt(width, height, width, height, angleDegrees, stops).withOpacity(opacity)
            .filtered(filter),
        topRight = gradientColorAt(width, 0f, width, height, angleDegrees, stops).withOpacity(opacity).filtered(filter),
        transform = transform,
    )
}

internal fun UiTriangleBatch.appendLocalPaint(
    width: Float,
    height: Float,
    radius: Float,
    color: UiColor,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    val filtered = color.filtered(filter)
    if (radius <= 0f) {
        appendSolidQuad(width, height, filtered, transform)
        return
    }
    appendRoundedFill(width, height, radius, transform) { _, _ -> filtered }
}

internal fun UiTriangleBatch.appendLocalGradient(
    width: Float,
    height: Float,
    radius: Float,
    angleDegrees: Float,
    stops: List<UiGradientStop>,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    if (radius <= 0f) {
        appendGradientQuad(width, height, angleDegrees, stops, opacity, transform, filter)
        return
    }
    appendRoundedFill(width, height, radius, transform) { x, y ->
        gradientColorAt(x, y, width, height, angleDegrees, stops).withOpacity(opacity).filtered(filter)
    }
}

internal fun UiTriangleBatch.appendLocalRadialGradient(
    width: Float,
    height: Float,
    radius: Float,
    gradient: UiRadialGradient,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    if (radius <= 0f) {
        appendGradientQuad(
            width = width,
            height = height,
            topLeft = radialGradientColorAt(0f, 0f, width, height, gradient).withOpacity(opacity).filtered(filter),
            bottomLeft = radialGradientColorAt(0f, height, width, height, gradient).withOpacity(opacity)
                .filtered(filter),
            bottomRight = radialGradientColorAt(width, height, width, height, gradient).withOpacity(opacity)
                .filtered(filter),
            topRight = radialGradientColorAt(width, 0f, width, height, gradient).withOpacity(opacity).filtered(filter),
            transform = transform,
        )
        return
    }
    appendRoundedFill(width, height, radius, transform) { x, y ->
        radialGradientColorAt(x, y, width, height, gradient).withOpacity(opacity).filtered(filter)
    }
}

internal fun UiTriangleBatch.appendLocalBorder(
    width: Float,
    height: Float,
    radius: Float,
    thickness: Float,
    colorAt: (Float, Float) -> UiColor,
    transform: UiMatrix4,
) {
    val border = thickness.coerceAtLeast(1f)
    if (radius > 0f) {
        appendRoundedStroke(width, height, radius, border, colorAt, transform)
        return
    }
    appendSampledQuad(0f, 0f, width, border, transform, colorAt)
    appendSampledQuad(0f, height - border, width, border, transform, colorAt)
    appendSampledQuad(0f, 0f, border, height, transform, colorAt)
    appendSampledQuad(width - border, 0f, border, height, transform, colorAt)
}

internal fun UiTriangleBatch.appendLocalShape(
    shape: Shape,
    width: Float,
    height: Float,
    fill: UiResolvedPaint,
    stroke: UiResolvedPaint,
    strokeWidth: Float,
    strokeLineCap: UiPathStrokeLineCap,
    strokeLineJoin: UiPathStrokeLineJoin,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
): Boolean {
    if (!fill.canDrawAsShapePaint() || !stroke.canDrawAsShapePaint()) return false
    val mesh = UiShapeMeshCache.mesh(
        shape = shape,
        width = width,
        height = height,
        strokeWidth = strokeWidth,
        strokeLineCap = strokeLineCap,
        strokeLineJoin = strokeLineJoin,
        includeFill = fill != UiResolvedPaint.None,
        includeStroke = stroke != UiResolvedPaint.None && strokeWidth > 0f,
    )
    if (fill != UiResolvedPaint.None) {
        mesh.fill.forEach { triangle ->
            appendColoredTriangle(triangle, fill, width, height, opacity, transform, filter)
        }
    }
    if (stroke != UiResolvedPaint.None && strokeWidth > 0f) {
        mesh.stroke.forEach { triangle ->
            appendColoredTriangle(triangle, stroke, width, height, opacity, transform, filter)
        }
    }
    return true
}

internal fun cachedFillTriangles(shape: Shape, width: Float, height: Float): List<UiPathTriangle> {
    return UiShapeMeshCache.mesh(
        shape = shape,
        width = width,
        height = height,
        strokeWidth = 0f,
        strokeLineCap = UiPathStrokeLineCap.Round,
        strokeLineJoin = UiPathStrokeLineJoin.Round,
        includeFill = true,
        includeStroke = false,
    ).fill
}

private object UiShapeMeshCache {
    private val meshes = ConcurrentHashMap<UiShapeMeshKey, UiShapeMesh>()

    fun mesh(
        shape: Shape,
        width: Float,
        height: Float,
        strokeWidth: Float,
        strokeLineCap: UiPathStrokeLineCap,
        strokeLineJoin: UiPathStrokeLineJoin,
        includeFill: Boolean,
        includeStroke: Boolean,
    ): UiShapeMesh {
        val key = UiShapeMeshKey(
            shape = shape,
            revision = shape.revision(),
            width = width,
            height = height,
            strokeWidth = strokeWidth,
            strokeLineCap = strokeLineCap,
            strokeLineJoin = strokeLineJoin,
            includeFill = includeFill,
            includeStroke = includeStroke,
        )
        return meshes.computeIfAbsent(key) {
            val geometry = shape.createPath(UiShapeSize(width, height)).flatten()
            UiShapeMesh(
                fill = if (includeFill) geometry.fillTriangles() else emptyList(),
                stroke = if (includeStroke) {
                    geometry.strokeTriangles(strokeWidth, strokeLineCap, strokeLineJoin)
                } else {
                    emptyList()
                },
            )
        }
    }

    private fun Shape.revision(): Long {
        return if (this is SvgResourceShape) HollowUiResourceAccess.version(location) else 0L
    }
}

private data class UiShapeMeshKey(
    val shape: Shape,
    val revision: Long,
    val width: Float,
    val height: Float,
    val strokeWidth: Float,
    val strokeLineCap: UiPathStrokeLineCap,
    val strokeLineJoin: UiPathStrokeLineJoin,
    val includeFill: Boolean,
    val includeStroke: Boolean,
)

private data class UiShapeMesh(
    val fill: List<UiPathTriangle>,
    val stroke: List<UiPathTriangle>,
)

private fun UiTriangleBatch.appendGradientQuad(
    width: Float,
    height: Float,
    topLeft: UiColor,
    bottomLeft: UiColor,
    bottomRight: UiColor,
    topRight: UiColor,
    transform: UiMatrix4,
) {
    addTriangle(transform, 0f, 0f, topLeft, 0f, height, bottomLeft, width, height, bottomRight)
    addTriangle(transform, 0f, 0f, topLeft, width, height, bottomRight, width, 0f, topRight)
}

private fun BufferBuilder.addColoredQuad(corners: Array<UiVec3>, color: UiColor) {
    corners.forEach { corner ->
        addVertex(corner.x, corner.y, corner.z).setColor(color.red, color.green, color.blue, color.alpha)
    }
}

private fun UiTriangleBatch.appendRoundedFill(
    width: Float,
    height: Float,
    radius: Float,
    transform: UiMatrix4,
    colorAt: (Float, Float) -> UiColor,
) {
    val centerX = width * 0.5f
    val centerY = height * 0.5f
    val centerColor = colorAt(centerX, centerY)
    val perimeter = roundedPerimeter(width, height, radius)
    for (index in 0 until perimeter.lastIndex) {
        val firstX = perimeter.x(index)
        val firstY = perimeter.y(index)
        val secondX = perimeter.x(index + 1)
        val secondY = perimeter.y(index + 1)
        addTriangle(
            transform,
            centerX,
            centerY,
            centerColor,
            firstX,
            firstY,
            colorAt(firstX, firstY),
            secondX,
            secondY,
            colorAt(secondX, secondY),
        )
    }
}

private fun UiTriangleBatch.appendSampledQuad(
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    transform: UiMatrix4,
    colorAt: (Float, Float) -> UiColor,
) {
    appendGradientQuad(
        width = width,
        height = height,
        topLeft = colorAt(offsetX, offsetY),
        bottomLeft = colorAt(offsetX, offsetY + height),
        bottomRight = colorAt(offsetX + width, offsetY + height),
        topRight = colorAt(offsetX + width, offsetY),
        transform = transform.translated(offsetX, offsetY),
    )
}

private fun UiTriangleBatch.appendRoundedStroke(
    width: Float,
    height: Float,
    radius: Float,
    thickness: Float,
    colorAt: (Float, Float) -> UiColor,
    transform: UiMatrix4,
) {
    val inset = thickness.coerceAtLeast(1f)
    val innerWidth = width - inset * 2f
    val innerHeight = height - inset * 2f
    if (innerWidth <= 0f || innerHeight <= 0f) {
        appendRoundedFill(width, height, radius, transform, colorAt)
        return
    }
    val segments = roundedSegments(radius)
    val outer = roundedPerimeter(width, height, radius, segments)
    val inner = roundedPerimeter(innerWidth, innerHeight, max(0f, radius - inset), segments)
    for (index in 0 until outer.lastIndex) {
        val nextIndex = index + 1
        val currentInnerIndex = index.coerceAtMost(inner.lastIndex)
        val nextInnerIndex = nextIndex.coerceAtMost(inner.lastIndex)
        val outerX = outer.x(index)
        val outerY = outer.y(index)
        val nextOuterX = outer.x(nextIndex)
        val nextOuterY = outer.y(nextIndex)
        val innerX = inner.x(currentInnerIndex) + inset
        val innerY = inner.y(currentInnerIndex) + inset
        val nextInnerX = inner.x(nextInnerIndex) + inset
        val nextInnerY = inner.y(nextInnerIndex) + inset
        val outerColor = colorAt(outerX, outerY)
        val nextOuterColor = colorAt(nextOuterX, nextOuterY)
        val innerColor = colorAt(innerX, innerY)
        val nextInnerColor = colorAt(nextInnerX, nextInnerY)
        addTriangle(
            transform,
            outerX, outerY, outerColor,
            innerX, innerY, innerColor,
            nextInnerX, nextInnerY, nextInnerColor,
        )
        addTriangle(
            transform,
            outerX, outerY, outerColor,
            nextInnerX, nextInnerY, nextInnerColor,
            nextOuterX, nextOuterY, nextOuterColor,
        )
    }
}

internal fun drawLocalBorder(width: Float, height: Float, radius: Float, color: UiColor, transform: UiMatrix4) {
    drawLocalBorder(width, height, radius, 1f, { _, _ -> color }, transform)
}

internal fun drawLocalBorder(
    width: Float,
    height: Float,
    radius: Float,
    thickness: Float,
    colorAt: (Float, Float) -> UiColor,
    transform: UiMatrix4,
) {
    val border = thickness.coerceAtLeast(1f)
    if (radius > 0f) {
        drawRoundedStroke(width, height, radius, border, colorAt, transform)
        return
    }
    drawSampledQuad(0f, 0f, width, border, transform, colorAt)
    drawSampledQuad(0f, height - border, width, border, transform, colorAt)
    drawSampledQuad(0f, 0f, border, height, transform, colorAt)
    drawSampledQuad(width - border, 0f, border, height, transform, colorAt)
}

private fun drawSampledQuad(
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    transform: UiMatrix4,
    colorAt: (Float, Float) -> UiColor,
) {
    val quadTransform = transform.translated(offsetX, offsetY)
    withCullStatePreserved {
        RenderSystem.disableCull()
        RenderSystem.enableBlend()
        configureUiBlend()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val corners = localCorners(width, height, quadTransform)
        val colors = arrayOf(
            colorAt(offsetX, offsetY),
            colorAt(offsetX, offsetY + height),
            colorAt(offsetX + width, offsetY + height),
            colorAt(offsetX + width, offsetY),
        )
        corners.forEachIndexed { index, corner ->
            val color = colors[index]
            buffer.addVertex(corner.x, corner.y, corner.z)
                .setColor(color.red, color.green, color.blue, color.alpha)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }
}

internal fun drawLocalPaint(
    width: Float,
    height: Float,
    radius: Float,
    color: UiColor,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    val filtered = color.filtered(filter)
    if (radius > 0f) {
        drawRoundedFan(width, height, radius, transform) { _, _ -> filtered }
        return
    }
    withCullStatePreserved {
        RenderSystem.disableCull()
        RenderSystem.enableBlend()
        configureUiBlend()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        buffer.addColoredQuad(localCorners(width, height, transform), filtered)
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }
}

internal fun drawLocalGradient(
    width: Float,
    height: Float,
    radius: Float,
    angleDegrees: Float,
    stops: List<UiGradientStop>,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    drawRoundedFan(width, height, radius, transform) { x, y ->
        gradientColorAt(x, y, width, height, angleDegrees, stops).withOpacity(opacity).filtered(filter)
    }
}

internal fun drawLocalRadialGradient(
    width: Float,
    height: Float,
    radius: Float,
    gradient: UiRadialGradient,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    drawRoundedFan(width, height, radius, transform) { x, y ->
        radialGradientColorAt(x, y, width, height, gradient).withOpacity(opacity).filtered(filter)
    }
}

internal fun drawShadow(
    width: Float,
    height: Float,
    radius: Float,
    shadow: UiShadow,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    drawProjectedShadow(width, height, radius, shadow, opacity, transform, filter)
}

internal fun drawProjectedShadow(
    width: Float,
    height: Float,
    radius: Float,
    shadow: UiShadow,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    val perimeter = roundedPerimeter(width, height, radius)
    val outline = ArrayList<UiVec3>(perimeter.size)
    for (index in 0 until perimeter.size) {
        outline += transform.transform(perimeter.x(index), perimeter.y(index))
    }
    val corners = localCorners(width, height, transform)
    val projectedScale = projectedScale(corners, width, height)
    val facing = facingAmount(width, height, transform)
    val elevation = corners.map { it.z }.average().toFloat().coerceAtLeast(0f) + shadow.offset.z.coerceAtLeast(0f)
    val distanceFactor = 1f + elevation * 0.025f
    val angleFactor = 1f + (1f - facing) * 0.45f
    val spread = shadow.spread * projectedScale
    val blur = shadow.blur * projectedScale * distanceFactor * angleFactor
    val castX = shadow.offset.x
    val castY = shadow.offset.y
    val alpha = opacity * (0.78f + facing * 0.42f) / distanceFactor
    drawProjectedShadowGradient(outline, spread, blur, castX, castY, shadow.color.withOpacity(alpha).filtered(filter))
}

internal fun isBackfaceHidden(
    width: Float,
    height: Float,
    transform: UiMatrix4,
    visibility: UiBackfaceVisibility,
): Boolean {
    if (visibility == UiBackfaceVisibility.VISIBLE) return false
    val xAxis = transform.transform(1f, 0f, 0f)
    val yAxis = transform.transform(0f, 1f, 0f)
    val origin = transform.transform(0f, 0f, 0f)
    val ax = xAxis.x - origin.x
    val ay = xAxis.y - origin.y
    val bx = yAxis.x - origin.x
    val by = yAxis.y - origin.y
    val normalZ = ax * by - ay * bx
    return normalZ < 0f
}

internal fun localCorners(width: Float, height: Float, transform: UiMatrix4) = arrayOf(
    transform.transform(0f, 0f),
    transform.transform(0f, height),
    transform.transform(width, height),
    transform.transform(width, 0f),
)

private fun projectedScale(corners: Array<UiVec3>, width: Float, height: Float): Float {
    val top = distance(corners[0], corners[3]) / width.coerceAtLeast(1f)
    val bottom = distance(corners[1], corners[2]) / width.coerceAtLeast(1f)
    val left = distance(corners[0], corners[1]) / height.coerceAtLeast(1f)
    val right = distance(corners[3], corners[2]) / height.coerceAtLeast(1f)
    return ((top + bottom + left + right) * 0.25f).coerceIn(0.25f, 4f)
}

private fun distance(a: UiVec3, b: UiVec3): Float {
    val x = a.x - b.x
    val y = a.y - b.y
    return sqrt(x * x + y * y)
}

private fun facingAmount(width: Float, height: Float, transform: UiMatrix4): Float {
    val origin = transform.transform(width * 0.5f, height * 0.5f, 0f)
    val xAxis = transform.transform(width * 0.5f + 1f, height * 0.5f, 0f)
    val yAxis = transform.transform(width * 0.5f, height * 0.5f + 1f, 0f)
    val ax = xAxis.x - origin.x
    val ay = xAxis.y - origin.y
    val az = xAxis.z - origin.z
    val bx = yAxis.x - origin.x
    val by = yAxis.y - origin.y
    val bz = yAxis.z - origin.z
    val crossX = ay * bz - az * by
    val crossY = az * bx - ax * bz
    val crossZ = ax * by - ay * bx
    val length = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ).coerceAtLeast(0.0001f)
    return abs(crossZ / length).coerceIn(0.05f, 1f)
}

internal fun UiColor.withOpacity(opacity: Float) = copy(alpha = alpha * opacity)

internal fun UiColor.argb(): Int {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return a shl 24 or (r shl 16) or (g shl 8) or b
}

private fun drawRoundedFan(
    width: Float,
    height: Float,
    radius: Float,
    transform: UiMatrix4,
    colorAt: (Float, Float) -> UiColor,
) {
    withCullStatePreserved {
        RenderSystem.disableCull()
        RenderSystem.enableBlend()
        configureUiBlend()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR)
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val center = transform.transform(centerX, centerY)
        val centerColor = colorAt(centerX, centerY)
        buffer.addVertex(center.x, center.y, center.z)
            .setColor(centerColor.red, centerColor.green, centerColor.blue, centerColor.alpha)
        val perimeter = roundedPerimeter(width, height, radius)
        for (index in 0 until perimeter.size) {
            val x = perimeter.x(index)
            val y = perimeter.y(index)
            val point = transform.transform(x, y)
            val color = colorAt(x, y)
            buffer.addVertex(point.x, point.y, point.z).setColor(color.red, color.green, color.blue, color.alpha)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }
}

private fun drawProjectedShadowGradient(
    outline: List<UiVec3>,
    spread: Float,
    blur: Float,
    offsetX: Float,
    offsetY: Float,
    color: UiColor,
) {
    if (color.alpha <= 0f) return
    if (outline.isEmpty()) return
    val centerX = outline.sumOf { it.x.toDouble() }.toFloat() / outline.size.toFloat()
    val centerY = outline.sumOf { it.y.toDouble() }.toFloat() / outline.size.toFloat()
    val outerExpansion = blur.coerceAtLeast(1f)
    val innerAlpha = color.alpha * 0.72f
    configureUiBlend()
    withCullStatePreserved {
        RenderSystem.disableCull()
        RenderSystem.enableBlend()
        configureUiBlend()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        val center = UiVec3(centerX + offsetX, centerY + offsetY, 0f)
        val inner = outline.map { expandFromCenter(it, centerX, centerY, spread) }
        val outer = outline.map { expandFromCenter(it, centerX, centerY, spread + outerExpansion) }
        for (index in 0 until inner.lastIndex) {
            val currentInner = inner[index].withOffset(offsetX, offsetY)
            val nextInner = inner[index + 1].withOffset(offsetX, offsetY)
            val currentOuter = outer[index].withOffset(offsetX, offsetY)
            val nextOuter = outer[index + 1].withOffset(offsetX, offsetY)
            buffer.addVertex(center.x, center.y, center.z).setColor(color.red, color.green, color.blue, innerAlpha)
            buffer.addVertex(currentInner.x, currentInner.y, 0f)
                .setColor(color.red, color.green, color.blue, innerAlpha)
            buffer.addVertex(nextInner.x, nextInner.y, 0f).setColor(color.red, color.green, color.blue, innerAlpha)

            buffer.addVertex(currentInner.x, currentInner.y, 0f)
                .setColor(color.red, color.green, color.blue, innerAlpha)
            buffer.addVertex(currentOuter.x, currentOuter.y, 0f).setColor(color.red, color.green, color.blue, 0f)
            buffer.addVertex(nextOuter.x, nextOuter.y, 0f).setColor(color.red, color.green, color.blue, 0f)

            buffer.addVertex(currentInner.x, currentInner.y, 0f)
                .setColor(color.red, color.green, color.blue, innerAlpha)
            buffer.addVertex(nextOuter.x, nextOuter.y, 0f).setColor(color.red, color.green, color.blue, 0f)
            buffer.addVertex(nextInner.x, nextInner.y, 0f).setColor(color.red, color.green, color.blue, innerAlpha)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }
}

private fun UiVec3.withOffset(x: Float, y: Float): UiVec3 = UiVec3(this.x + x, this.y + y, z)

private fun expandFromCenter(point: UiVec3, centerX: Float, centerY: Float, distance: Float): UiVec3 {
    val x = point.x - centerX
    val y = point.y - centerY
    val length = sqrt(x * x + y * y).coerceAtLeast(1f)
    return UiVec3(
        x = point.x + x / length * distance,
        y = point.y + y / length * distance,
        z = point.z,
    )
}

private fun drawRoundedStroke(
    width: Float,
    height: Float,
    radius: Float,
    thickness: Float,
    colorAt: (Float, Float) -> UiColor,
    transform: UiMatrix4,
) {
    val inset = thickness.coerceAtLeast(1f)
    val innerWidth = width - inset * 2f
    val innerHeight = height - inset * 2f
    if (innerWidth <= 0f || innerHeight <= 0f) {
        drawRoundedFan(width, height, radius, transform, colorAt)
        return
    }
    val segments = roundedSegments(radius)
    val outer = roundedPerimeter(width, height, radius, segments)
    val inner = roundedPerimeter(
        innerWidth,
        innerHeight,
        max(0f, radius - inset),
        segments
    )
    withCullStatePreserved {
        RenderSystem.disableCull()
        RenderSystem.enableBlend()
        configureUiBlend()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR)
        for (index in 0 until outer.size) {
            val innerIndex = index.coerceAtMost(inner.lastIndex)
            val outerX = outer.x(index)
            val outerY = outer.y(index)
            val innerX = inner.x(innerIndex) + inset
            val innerY = inner.y(innerIndex) + inset
            val outerPoint = transform.transform(outerX, outerY)
            val innerPoint = transform.transform(innerX, innerY)
            val outerColor = colorAt(outerX, outerY)
            val innerColor = colorAt(innerX, innerY)
            buffer.addVertex(outerPoint.x, outerPoint.y, outerPoint.z)
                .setColor(outerColor.red, outerColor.green, outerColor.blue, outerColor.alpha)
            buffer.addVertex(innerPoint.x, innerPoint.y, innerPoint.z)
                .setColor(innerColor.red, innerColor.green, innerColor.blue, innerColor.alpha)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }
}

private fun roundedPerimeter(
    width: Float,
    height: Float,
    radius: Float,
    segmentsOverride: Int? = null,
): RoundedPerimeter {
    val clamped = radius.coerceIn(0f, min(width, height) * 0.5f)
    val segments = if (clamped <= 0f) 0 else segmentsOverride ?: roundedSegments(clamped)
    val key = RoundedPerimeterKey(width, height, clamped, segments)
    return roundedPerimeterCache.getOrPut(key) { buildRoundedPerimeter(width, height, clamped, segments) }
}

private fun buildRoundedPerimeter(width: Float, height: Float, radius: Float, segments: Int): RoundedPerimeter {
    if (radius <= 0f) {
        return RoundedPerimeter(floatArrayOf(0f, 0f, 0f, height, width, height, width, 0f, 0f, 0f))
    }
    val points = FloatArray((4 * (segments + 1) + 1) * 2)
    var offset = 0

    fun appendCorner(x: Float, y: Float, start: Float, end: Float) {
        for (index in 0..segments) {
            val progress = index.toFloat() / segments.toFloat()
            val angle = start + (end - start) * progress
            points[offset++] = x + cos(angle) * radius
            points[offset++] = y + sin(angle) * radius
        }
    }

    val pi = PI.toFloat()
    appendCorner(radius, radius, pi * 1.5f, pi)
    appendCorner(radius, height - radius, pi, pi * 0.5f)
    appendCorner(width - radius, height - radius, pi * 0.5f, 0f)
    appendCorner(width - radius, radius, 0f, -pi * 0.5f)
    points[offset++] = points[0]
    points[offset] = points[1]
    return RoundedPerimeter(points)
}

private fun roundedSegments(radius: Float): Int = max(8, min(48, (radius * 0.75f).roundToInt()))

private data class RoundedPerimeterKey(
    val width: Float,
    val height: Float,
    val radius: Float,
    val segments: Int,
)

private class RoundedPerimeter(private val points: FloatArray) {
    val size: Int = points.size / 2
    val lastIndex: Int = size - 1

    fun x(index: Int): Float = points[index * 2]

    fun y(index: Int): Float = points[index * 2 + 1]
}

private const val MaxRoundedPerimeterCacheEntries = 512

private val roundedPerimeterCache = object :
    LinkedHashMap<RoundedPerimeterKey, RoundedPerimeter>(MaxRoundedPerimeterCacheEntries, 0.75f, true) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<RoundedPerimeterKey, RoundedPerimeter>?,
    ): Boolean = size > MaxRoundedPerimeterCacheEntries
}

internal fun UiResolvedPaint.localColorAt(
    width: Float,
    height: Float,
    opacity: Float,
    filter: UiFilterChain,
): ((Float, Float) -> UiColor)? = when (this) {
    is UiResolvedPaint.Color -> color.withOpacity(opacity).filtered(filter).let { resolved ->
        { _: Float, _: Float -> resolved }
    }

    is UiResolvedPaint.LinearGradient -> { x, y ->
        gradientColorAt(x, y, width, height, angleDegrees, stops).withOpacity(opacity).filtered(filter)
    }

    is UiResolvedPaint.RadialGradient -> { x, y ->
        radialGradientColorAt(x, y, width, height, gradient).withOpacity(opacity).filtered(filter)
    }

    UiResolvedPaint.None,
    is UiResolvedPaint.Image,
    is UiResolvedPaint.Shader,
        -> null
}

private fun radialGradientColorAt(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    gradient: UiRadialGradient,
): UiColor {
    val stops = gradient.stops
    if (stops.isEmpty()) return UiColor.Transparent
    if (stops.size == 1) return stops.first().color
    val centerX = gradient.centerX.resolve(width)
    val centerY = gradient.centerY.resolve(height)
    val radius = gradient.radius.resolve(max(width, height)).coerceAtLeast(0.0001f)
    val dx = x - centerX
    val dy = y - centerY
    val offset = (sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f)
    val right = stops.firstOrNull { it.offset >= offset } ?: stops.last()
    val left = stops.lastOrNull { it.offset <= offset } ?: stops.first()
    if (left == right) return left.color
    val range = (right.offset - left.offset).coerceAtLeast(0.0001f)
    return left.color.interpolate(right.color, (offset - left.offset) / range)
}

private fun UiTriangleBatch.appendColoredTriangle(
    triangle: UiPathTriangle,
    paint: UiResolvedPaint,
    width: Float,
    height: Float,
    opacity: Float,
    transform: UiMatrix4,
    filter: UiFilterChain,
) {
    val firstColor = triangle.first.colorAt(paint, width, height, opacity, filter)
    val secondColor = triangle.second.colorAt(paint, width, height, opacity, filter)
    val thirdColor = triangle.third.colorAt(paint, width, height, opacity, filter)
    addTriangle(
        transform,
        triangle.first.x,
        triangle.first.y,
        firstColor,
        triangle.second.x,
        triangle.second.y,
        secondColor,
        triangle.third.x,
        triangle.third.y,
        thirdColor,
    )
}

private fun UiPathPoint.colorAt(
    paint: UiResolvedPaint,
    width: Float,
    height: Float,
    opacity: Float,
    filter: UiFilterChain,
): UiColor = paint.colorAt(x, y, width, height).withOpacity(opacity).filtered(filter)

private fun UiResolvedPaint.colorAt(x: Float, y: Float, width: Float, height: Float): UiColor = when (this) {
    UiResolvedPaint.None -> UiColor.Transparent
    is UiResolvedPaint.Color -> color
    is UiResolvedPaint.LinearGradient -> gradientColorAt(x, y, width, height, angleDegrees, stops)
    is UiResolvedPaint.RadialGradient -> radialGradientColorAt(x, y, width, height, gradient)
    is UiResolvedPaint.Image,
    is UiResolvedPaint.Shader,
        -> UiColor.Transparent
}

private fun UiResolvedPaint.canDrawAsShapePaint(): Boolean = when (this) {
    UiResolvedPaint.None,
    is UiResolvedPaint.Color,
    is UiResolvedPaint.LinearGradient,
    is UiResolvedPaint.RadialGradient,
        -> true

    is UiResolvedPaint.Image,
    is UiResolvedPaint.Shader,
        -> false
}

internal fun UiColor.filtered(filter: UiFilterChain): UiColor {
    val grayscale = filter.grayscaleAmount()
    if (grayscale <= 0f) return this
    val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
    return UiColor(
        red = red + (luminance - red) * grayscale,
        green = green + (luminance - green) * grayscale,
        blue = blue + (luminance - blue) * grayscale,
        alpha = alpha,
    )
}
