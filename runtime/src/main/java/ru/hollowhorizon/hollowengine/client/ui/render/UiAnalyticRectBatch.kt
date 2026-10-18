package ru.hollowhorizon.hollowengine.client.ui.render

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.render.UiShaderClip.Companion.None
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import ru.hollowhorizon.hollowengine.client.ui.text.UiGlyphAtlasPage
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal class UiAnalyticRectBatch {
    private val instances = UiFloatArrayBuilder()
    private val records = UiFloatArrayBuilder()
    private val paints = UiFloatArrayBuilder()
    private val stops = UiFloatArrayBuilder()
    private val paintEncoder = UiPaintBufferEncoder(paints, stops)

    val isEmpty: Boolean get() = instances.size == 0
    val instanceCount: Int get() = instances.size / InstanceStride
    val instanceFloatCount: Int get() = instances.size
    val recordFloatCount: Int get() = records.size
    val paintFloatCount: Int get() = paints.size
    val stopFloatCount: Int get() = stops.size

    private val pageTextures = IntArray(MaxGlyphPages)
    private val pageDistanceRanges = FloatArray(MaxGlyphPages)
    private val pageSizes = FloatArray(MaxGlyphPages * 2)

    var glyphPageCount: Int = 0
        private set

    fun glyphPageTexture(index: Int): Int = pageTextures[index]

    /** Per-page distance ranges, for `float[]` uniform upload. */
    fun glyphPageDistanceRanges(): FloatArray = pageDistanceRanges

    /** Per-page atlas sizes as `width, height` pairs, for `vec2[]` uniform upload. */
    fun glyphPageSizes(): FloatArray = pageSizes

    /** Whether [page] can join this batch: it is already here, or there is a free slot for it. */
    fun acceptsGlyphPage(page: UiGlyphAtlasPage): Boolean =
        indexOfGlyphPage(page.textureId) >= 0 || glyphPageCount < MaxGlyphPages

    private fun indexOfGlyphPage(texture: Int): Int {
        for (index in 0 until glyphPageCount) if (pageTextures[index] == texture) return index
        return -1
    }

    private fun slotFor(page: UiGlyphAtlasPage): Int {
        val existing = indexOfGlyphPage(page.textureId)
        if (existing >= 0) return existing
        val slot = glyphPageCount
        pageTextures[slot] = page.textureId
        pageDistanceRanges[slot] = page.distanceRange
        pageSizes[slot * 2] = page.width
        pageSizes[slot * 2 + 1] = page.height
        glyphPageCount = slot + 1
        return slot
    }

    fun canAppend(command: DrawBoxCommand): Boolean {
        if (command.renderToFramebuffer) return false
        if (command.paint != UiResolvedPaint.None && !command.paint.isBufferPaint()) return false
        val borderPaint = command.border.paint.resolve()
        if (borderPaint != UiResolvedPaint.None && !borderPaint.isBufferPaint()) return false
        uniformBorderWidth(command) ?: return false
        return true
    }

    fun append(command: DrawBoxCommand, transform: UiMatrix4, clip: UiShaderClip) {
        val width = command.rect.width
        val height = command.rect.height
        if (width <= 0f || height <= 0f || command.opacity <= 0f) return
        val borderWidth = checkNotNull(uniformBorderWidth(command)).coerceIn(0f, min(width, height) * 0.5f)
        val borderPaint = command.border.paint.resolve().takeIf { borderWidth > 0f } ?: UiResolvedPaint.None
        if (command.paint == UiResolvedPaint.None && !borderPaint.hasVisiblePixels()) return
        val paintIndex = if (command.paint == UiResolvedPaint.None) {
            NoPaint
        } else {
            paintEncoder.append(command.paint, command.opacity, command.filter, width, height)
        }
        val borderColor =
            (borderPaint as? UiResolvedPaint.Color)?.color?.withOpacity(command.opacity)?.filtered(command.filter)
                ?: UiColor.Transparent
        val borderPaintIndex = if (borderPaint is UiResolvedPaint.Color || borderPaint == UiResolvedPaint.None) {
            NoPaint
        } else {
            paintEncoder.append(borderPaint, command.opacity, command.filter, width, height)
        }
        val radius = command.border.radius.coerceIn(0f, min(width, height) * 0.5f)
        val dash = command.border.dash?.takeIf { !it.isSolid && borderWidth > 0f }
            ?.let { borderDashPattern(width, height, radius, borderWidth, it) }
        records.add(width, height, radius, borderWidth)
        records.add(paintIndex.toFloat(), borderPaintIndex.toFloat(), dash?.period ?: 0f, -(dash?.length ?: 0f))
        records.add(borderColor.red, borderColor.green, borderColor.blue, borderColor.alpha)
        records.add(clip.minX, clip.minY, clip.maxX, clip.maxY)
        appendInstance(transform, 0f, 0f, width, height)
    }

    fun appendShadow(
        command: DrawShadowCommand,
        shadow: UiShadow,
        transform: UiMatrix4,
        clip: UiShaderClip,
    ) {
        val width = command.rect.width
        val height = command.rect.height
        if (width <= 0f || height <= 0f || command.opacity <= 0f || shadow.color.alpha <= 0f) return
        val blurRadius = shadow.blur.coerceAtLeast(0f)
        val spreadRadius = shadow.spread
        val paintIndex = paintEncoder.append(
            UiResolvedPaint.Color(shadow.color),
            command.opacity,
            command.filter,
            width,
            height,
        )
        val radius = command.radius.coerceIn(0f, min(width, height) * 0.5f)
        if (shadow.inset) {
            records.add(width, height, radius, command.borderWidth.coerceIn(0f, min(width, height) * 0.5f))
            records.add(paintIndex.toFloat(), blurRadius, spreadRadius, InsetShadowMode)
            records.add(shadow.offset.x, shadow.offset.y, 0f, 0f)
            records.add(clip.minX, clip.minY, clip.maxX, clip.maxY)
            appendInstance(transform, 0f, 0f, width, height)
            return
        }
        val rasterMargin = abs(spreadRadius) + blurRadius * BlurExtentFactor + AntialiasMargin
        records.add(width, height, radius, 0f)
        records.add(paintIndex.toFloat(), blurRadius, spreadRadius, ShadowMode)
        records.add(0f, 0f, 0f, 0f)
        records.add(clip.minX, clip.minY, clip.maxX, clip.maxY)
        appendInstance(
            transform,
            -rasterMargin,
            -rasterMargin,
            width + rasterMargin,
            height + rasterMargin,
        )
    }

    /**
     * A single glyph quad. [minX]..[maxY] are the glyph's local-space bounds (the shared run
     * [transform] maps them to screen), [u0]..[v1] the atlas UV rect, drawn in [color] and clipped
     * by [clip]. [page] must have been admitted by [acceptsGlyphPage].
     *
     * [sdBias] grows the signed-distance edge for faux-bold (distance-field pages only).
     */
    fun appendGlyph(
        transform: UiMatrix4,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: UiColor,
        clip: UiShaderClip,
        page: UiGlyphAtlasPage,
        sdBias: Float = 0f,
    ) {
        val slot = slotFor(page)
        records.add(GlyphMarker, sdBias, page.sampling.shaderMode.toFloat(), slot.toFloat())
        records.add(u0, v0, u1, v1)
        records.add(color.red, color.green, color.blue, color.alpha)
        records.add(clip.minX, clip.minY, clip.maxX, clip.maxY)
        appendInstance(transform, minX, minY, maxX, maxY)
    }

    /**
     * A plain untextured rectangle at the batch's current position, for the things drawn alongside
     * text: span backgrounds, and the underline and strikethrough rules.
     */
    fun appendSolidRect(
        transform: UiMatrix4,
        width: Float,
        height: Float,
        radius: Float,
        color: UiColor,
        clip: UiShaderClip,
    ) {
        if (width <= 0f || height <= 0f || color.alpha <= 0f) return
        val paintIndex = paintEncoder.append(UiResolvedPaint.Color(color), 1f, UiFilterChain.Empty, width, height)
        records.add(width, height, radius.coerceIn(0f, min(width, height) * 0.5f), 0f)
        records.add(paintIndex.toFloat(), 0f, 0f, 0f)
        records.add(0f, 0f, 0f, 0f)
        records.add(clip.minX, clip.minY, clip.maxX, clip.maxY)
        appendInstance(transform, 0f, 0f, width, height)
    }

    fun clear() {
        instances.clear()
        records.clear()
        paints.clear()
        stops.clear()
        glyphPageCount = 0
    }

    fun writeInstances(destination: FloatBuffer) = instances.writeTo(destination)
    fun writeRecords(destination: FloatBuffer) = records.writeTo(destination)
    fun writePaints(destination: FloatBuffer) = paints.writeTo(destination)
    fun writeStops(destination: FloatBuffer) = stops.writeTo(destination)

    private fun appendInstance(transform: UiMatrix4, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        instances.addMatrix(transform)
        instances.add(minX, minY, maxX, maxY)
    }

    private fun uniformBorderWidth(command: DrawBoxCommand): Float? {
        val width = command.rect.width
        val height = command.rect.height
        val left = command.border.width.left.resolve(width)
        val top = command.border.width.top.resolve(height)
        val right = command.border.width.right.resolve(width)
        val bottom = command.border.width.bottom.resolve(height)
        return left.takeIf {
            abs(it - top) <= BorderEpsilon && abs(it - right) <= BorderEpsilon && abs(it - bottom) <= BorderEpsilon
        }
    }

    companion object {
        const val InstanceStride = 20
        const val RecordStride = 16
        const val NoPaint = -1

        /**
         * Atlas pages one draw can mix. Enough for the vanilla font's three sheets plus several
         * custom faces on the same screen; each costs a texture unit and a branch in the shader's
         * sampler dispatch, which GLSL 3.30 forces to be a constant-indexed chain.
         */
        const val MaxGlyphPages = 8

        /** Record texel-0 x sentinel marking a glyph (rects always have positive width there). */
        const val GlyphMarker = -1f
        private const val ShadowMode = 1f
        private const val InsetShadowMode = 2f
        private const val BlurExtentFactor = 3f
        private const val AntialiasMargin = 1f
        private const val BorderEpsilon = 0.001f
    }
}

/** A dash pattern fitted to one border: [period] divides its center line into whole repeats. */
internal class UiBorderDashPattern(val period: Float, val length: Float)

internal fun borderDashPattern(
    width: Float,
    height: Float,
    radius: Float,
    borderWidth: Float,
    dash: UiBorderDash,
): UiBorderDashPattern {
    val inset = borderWidth * 0.5f
    val halfWidth = width * 0.5f - inset
    val halfHeight = height * 0.5f - inset
    val centreRadius = (radius - inset).coerceIn(0f, min(halfWidth, halfHeight))
    val perimeter =
        4f * (halfWidth - centreRadius) + 4f * (halfHeight - centreRadius) + 2f * PI.toFloat() * centreRadius
    val repeat = dash.length + dash.gap
    val count = (perimeter / repeat).roundToInt().coerceAtLeast(1)
    val period = perimeter / count
    return UiBorderDashPattern(period, period * dash.length / repeat)
}

/**
 * An axis-aligned clip rectangle in the batch's effective (pre-projection) screen space, encoded
 * per primitive so the fragment shader can discard out-of-bounds pixels, replacing the GL scissor
 * and letting a batch span multiple clip regions in one draw. [None] disables clipping.
 */
internal data class UiShaderClip(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    /**
     * The tighter clip common to both rectangles. An empty result (min ≥ max) makes the shader
     * discard every pixel, which is the correct outcome for two non-overlapping clip regions.
     */
    fun intersect(other: UiShaderClip): UiShaderClip = UiShaderClip(
        maxOf(minX, other.minX),
        maxOf(minY, other.minY),
        minOf(maxX, other.maxX),
        minOf(maxY, other.maxY),
    )

    companion object {
        /** No clipping: bounds far outside any UI coordinate so the shader never discards. */
        val None = UiShaderClip(-1e9f, -1e9f, 1e9f, 1e9f)
    }
}
