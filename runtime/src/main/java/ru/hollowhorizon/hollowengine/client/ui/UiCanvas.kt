package ru.hollowhorizon.hollowengine.client.ui

import com.mojang.blaze3d.vertex.PoseStack
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutNode
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.particles.UiParticleSystem
import ru.hollowhorizon.hollowengine.client.ui.shape.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiBackfaceVisibility
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.ui.style.UiImageFit
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Geometry coverage applied to a canvas draw operation. */
sealed interface UiDrawStyle {
    data object Fill : UiDrawStyle

    data class Stroke(
        val width: Float = 1f,
        val lineCap: UiPathStrokeLineCap = UiPathStrokeLineCap.Round,
        val lineJoin: UiPathStrokeLineJoin = UiPathStrokeLineJoin.Round,
    ) : UiDrawStyle {
        init {
            require(width >= 0f) { "Stroke width cannot be negative" }
        }
    }
}

/**
 * Records local drawing operations for one laid-out UI node. Commands are streamed directly into
 * the frame renderer: the API has retained canvas semantics without allocating another frame tree.
 */
interface UiCanvasDrawScope {
    val size: UiShapeSize

    val bounds: UiRect
        get() = UiRect(0f, 0f, size.width, size.height)

    /** The node's own `border-radius`, for drawing that has to stay inside its rounded box. */
    val cornerRadius: Float

    fun drawRect(
        rect: UiRect,
        paint: UiPaint,
        radius: Float = 0f,
        border: UiBorder = UiBorder(),
        tint: UiColor = UiColor.White,
        fit: UiImageFit = UiImageFit.STRETCH,
        slice: UiInsets = UiInsets.Zero,
    )

    fun drawRect(
        paint: UiPaint,
        radius: Float = 0f,
        border: UiBorder = UiBorder(),
        tint: UiColor = UiColor.White,
        fit: UiImageFit = UiImageFit.STRETCH,
        slice: UiInsets = UiInsets.Zero,
    ) = drawRect(bounds, paint, radius, border, tint, fit, slice)

    fun drawShape(
        shape: Shape,
        rect: UiRect,
        paint: UiPaint,
        style: UiDrawStyle = UiDrawStyle.Fill,
    )

    fun drawShape(
        shape: Shape,
        paint: UiPaint,
        style: UiDrawStyle = UiDrawStyle.Fill,
    ) = drawShape(shape, bounds, paint, style)

    fun drawSvg(document: UiSvgPathDocument, rect: UiRect, tint: UiColor = UiColor.White)

    fun drawSvg(document: UiSvgPathDocument) = drawSvg(document, bounds)

    /** [radius] rounds the corners, so a texture filling a rounded panel stays inside it. */
    fun drawTexture(rect: UiRect, textureId: Int, flipY: Boolean = false, opaque: Boolean = false, radius: Float = 0f)

    fun drawTexture(textureId: Int, flipY: Boolean = false, opaque: Boolean = false) =
        drawTexture(bounds, textureId, flipY, opaque)

    fun drawTexture(rect: UiRect, texture: () -> Int, flipY: Boolean = false, opaque: Boolean = false, radius: Float = 0f)

    /** Draws world-independent Minecraft sprite particles, clipped to the given local rectangle. */
    fun drawParticles(system: UiParticleSystem, rect: UiRect = bounds)

    /**
     * Escapes to raw OpenGL for [rect] (node-local coordinates; defaults to the whole node). Unlike the
     * vector/text primitives above, [block] does not record a batchable command, it runs at render
     * time, after pending UI batches are flushed and depth testing is enabled, so it can draw 3D content
     * (models, entities, custom GL) that the 2D canvas can't express. See [UiGlDrawScope].
     */
    fun drawGl(rect: UiRect, block: UiGlDrawScope.() -> Unit)

    fun drawGl(block: UiGlDrawScope.() -> Unit) = drawGl(bounds, block)
}

interface UiGlDrawScope {
    /** The node's draw rect in the active projection's coordinate space (already layer-local). */
    val rect: UiRect

    /** The node's effective opacity, for callers that blend manually. */
    val opacity: Float

    /** A fresh, identity pose stack sharing the UI's current projection matrix. */
    val poseStack: PoseStack
}

enum class UiCanvasDrawLayer {
    BEHIND,
    OVERLAY,
}

class UiCanvasModifier internal constructor(
    val layer: UiCanvasDrawLayer,
    key: Any?,
    internal val block: UiCanvasDrawScope.() -> Unit,
) : Modifier {
    private val equalityKey = key ?: block

    override fun equals(other: Any?): Boolean =
        other is UiCanvasModifier && layer == other.layer && equalityKey == other.equalityKey

    override fun hashCode(): Int = 31 * layer.hashCode() + equalityKey.hashCode()
}

fun Modifier.drawBehind(
    key: Any? = null,
    block: UiCanvasDrawScope.() -> Unit,
): Modifier = this then UiCanvasModifier(UiCanvasDrawLayer.BEHIND, key, block)

fun Modifier.draw(
    key: Any? = null,
    block: UiCanvasDrawScope.() -> Unit,
): Modifier = this then UiCanvasModifier(UiCanvasDrawLayer.OVERLAY, key, block)

internal class UiCommandCanvasScope(
    private val node: UiNode,
    private val layoutNode: UiLayoutNode,
    private val opacity: Float,
    private val filter: UiFilterChain,
    private val backfaceVisibility: UiBackfaceVisibility,
    private val phase: UiRenderPhase,
    private val sink: UiRenderSink,
    override val cornerRadius: Float = 0f,
) : UiCanvasDrawScope {
    override val size = UiShapeSize(layoutNode.rect.width, layoutNode.rect.height)

    override fun drawRect(
        rect: UiRect,
        paint: UiPaint,
        radius: Float,
        border: UiBorder,
        tint: UiColor,
        fit: UiImageFit,
        slice: UiInsets,
    ) {
        if (!rect.isDrawable() || opacity <= 0f) return
        val resolvedPaint = paint.resolve()
        if (!resolvedPaint.hasVisiblePixels() && !border.hasVisiblePixels()) return
        sink += DrawBoxCommand(
            node = node,
            rect = rect.toCommandRect(),
            paint = resolvedPaint,
            border = border.copy(radius = radius.coerceAtLeast(0f)),
            shadows = emptyList(),
            opacity = opacity,
            tint = tint,
            transform = layoutNode.worldTransform.translated(rect.x, rect.y),
            renderToFramebuffer = false,
            fit = fit,
            slice = slice,
            filter = filter,
            backfaceVisibility = backfaceVisibility,
            phase = phase,
        )
    }

    override fun drawShape(
        shape: Shape,
        rect: UiRect,
        paint: UiPaint,
        style: UiDrawStyle,
    ) {
        if (!rect.isDrawable() || opacity <= 0f || paint == UiPaint.None) return
        val resolvedPaint = paint.resolve()
        if (!resolvedPaint.hasVisiblePixels()) return
        val fill = if (style == UiDrawStyle.Fill) resolvedPaint else UiResolvedPaint.None
        val stroke = if (style is UiDrawStyle.Stroke) resolvedPaint else UiResolvedPaint.None
        sink += DrawShapeCommand(
            node = node,
            rect = rect.toCommandRect(),
            shape = shape,
            fill = fill,
            stroke = stroke,
            strokeWidth = (style as? UiDrawStyle.Stroke)?.width ?: 0f,
            opacity = opacity,
            transform = layoutNode.worldTransform.translated(rect.x, rect.y),
            filter = filter,
            backfaceVisibility = backfaceVisibility,
            phase = phase,
            strokeLineCap = (style as? UiDrawStyle.Stroke)?.lineCap ?: UiPathStrokeLineCap.Round,
            strokeLineJoin = (style as? UiDrawStyle.Stroke)?.lineJoin ?: UiPathStrokeLineJoin.Round,
        )
    }

    override fun drawTexture(rect: UiRect, textureId: Int, flipY: Boolean, opaque: Boolean, radius: Float) {
        if (textureId == 0) return
        rawTexture(rect, textureId, null, flipY, opaque, radius)
    }

    override fun drawTexture(rect: UiRect, texture: () -> Int, flipY: Boolean, opaque: Boolean, radius: Float) {
        rawTexture(rect, 0, texture, flipY, opaque, radius)
    }

    private fun rawTexture(
        rect: UiRect,
        textureId: Int,
        texture: (() -> Int)?,
        flipY: Boolean,
        opaque: Boolean,
        radius: Float,
    ) {
        if (!rect.isDrawable() || opacity <= 0f) return
        sink += DrawRawTextureCommand(
            node = node,
            rect = rect.toCommandRect(),
            textureId = textureId,
            texture = texture,
            opacity = opacity,
            flipY = flipY,
            opaque = opaque,
            radius = radius.coerceAtLeast(0f),
            transform = layoutNode.worldTransform.translated(rect.x, rect.y),
            filter = filter,
            backfaceVisibility = backfaceVisibility,
            phase = phase,
        )
    }

    override fun drawParticles(system: UiParticleSystem, rect: UiRect) {
        if (!rect.isDrawable() || opacity <= 0f) return
        sink += DrawParticlesCommand(
            node, rect.toCommandRect(), system, opacity,
            layoutNode.worldTransform.translated(rect.x, rect.y), filter, backfaceVisibility, phase,
        )
    }

    override fun drawGl(rect: UiRect, block: UiGlDrawScope.() -> Unit) {
        if (!rect.isDrawable() || opacity <= 0f) return
        sink += DrawCanvasGlCommand(
            node = node,
            rect = rect.toCommandRect(),
            opacity = opacity,
            transform = layoutNode.worldTransform.translated(rect.x, rect.y),
            filter = filter,
            backfaceVisibility = backfaceVisibility,
            phase = phase,
            block = block,
        )
    }

    override fun drawSvg(document: UiSvgPathDocument, rect: UiRect, tint: UiColor) {
        if (!rect.isDrawable()) return
        val viewBox = document.viewBox
        val scale = min(
            rect.width / viewBox.width.coerceAtLeast(0.0001f),
            rect.height / viewBox.height.coerceAtLeast(0.0001f),
        )
        val contentRect = UiRect(
            x = rect.x + (rect.width - viewBox.width * scale) * 0.5f,
            y = rect.y + (rect.height - viewBox.height * scale) * 0.5f,
            width = viewBox.width * scale,
            height = viewBox.height * scale,
        )
        val scaleX = contentRect.width / viewBox.width.coerceAtLeast(0.0001f)
        val scaleY = contentRect.height / viewBox.height.coerceAtLeast(0.0001f)
        val effectScale = max(abs(scaleX), abs(scaleY))
        for (index in document.elements.indices) {
            val element = document.elements[index]
            val shape = document.shapes[index]
            for (effect in element.filterEffects) {
                if (effect !is UiSvgFilterEffect.DropShadow) continue
                submitSvgShape(
                    shape = shape,
                    rect = contentRect,
                    fill = UiResolvedPaint.Color(effect.color),
                    offsetX = effect.offsetX * scaleX,
                    offsetY = effect.offsetY * scaleY,
                    blurRadius = effect.standardDeviation * effectScale,
                )
            }
        }
        for (index in document.elements.indices) {
            val element = document.elements[index]
            if (element.paint === UiPaint.None) continue
            val shape = document.shapes[index]
            var blurRadius = 0f
            for (effect in element.filterEffects) {
                when (effect) {
                    is UiSvgFilterEffect.GaussianBlur -> {
                        blurRadius = max(blurRadius, effect.standardDeviation * effectScale)
                    }

                    is UiSvgFilterEffect.DropShadow -> Unit
                }
            }
            submitSvgShape(
                shape = shape,
                rect = contentRect,
                fill = element.paint.tintedBy(tint).resolve(),
                blurRadius = blurRadius,
            )
        }
    }

    private fun UiColor.tintedBy(tint: UiColor): UiColor {
        if (tint == UiColor.White) return this
        return UiColor(red * tint.red, green * tint.green, blue * tint.blue, alpha * tint.alpha)
    }

    private fun UiPaint.tintedBy(tint: UiColor): UiPaint {
        if (tint == UiColor.White) return this
        return when (this) {
            is UiPaint.Color -> UiPaint.Color(color.tintedBy(tint))
            is UiPaint.LinearGradient -> copy(stops = stops.map { it.copy(color = it.color.tintedBy(tint)) })
            is UiPaint.RadialGradient -> UiPaint.RadialGradient(
                gradient.copy(stops = gradient.stops.map { it.copy(color = it.color.tintedBy(tint)) }),
            )

            UiPaint.None, is UiPaint.Image, is UiPaint.Shader -> this
        }
    }

    private fun submitSvgShape(
        shape: Shape,
        rect: UiRect,
        fill: UiResolvedPaint,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        blurRadius: Float = 0f,
    ) {
        sink += DrawShapeCommand(
            node = node,
            rect = rect.toCommandRect(),
            shape = shape,
            fill = fill,
            stroke = UiResolvedPaint.None,
            strokeWidth = 0f,
            opacity = opacity,
            transform = layoutNode.worldTransform.translated(rect.x + offsetX, rect.y + offsetY),
            filter = filter,
            backfaceVisibility = backfaceVisibility,
            phase = phase,
            blurRadius = blurRadius.coerceAtLeast(0f),
        )
    }

    private fun UiRect.toCommandRect() = UiRect(
        x = layoutNode.rect.x + x,
        y = layoutNode.rect.y + y,
        width = width,
        height = height,
    )

    private fun UiRect.isDrawable(): Boolean =
        width.isFinite() && height.isFinite() && width > 0f && height > 0f
}
