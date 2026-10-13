package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.math.PI

enum class StyleOrigin(val priority: Int) {
    THEME_DEFAULTS(1), STYLESHEET(2), STATE_STYLESHEET(3),
}

enum class UiTextOverflow {
    SHOW, HIDDEN, DOTS
}

enum class UiImageFit {
    STRETCH, CONTAIN, COVER, NONE, NINE_SLICE, THREE_SLICE_VERTICAL, THREE_SLICE_HORIZONTAL
}

enum class UiBackfaceVisibility {
    VISIBLE, HIDDEN
}

data class UiImageUv(
    val x: UiLength = 0.px,
    val y: UiLength = 0.px,
    val width: UiLength = 100.percent,
    val height: UiLength = 100.percent,
) {
    fun resolve(textureWidth: Float, textureHeight: Float): UiUvRect {
        if (this == Full || textureWidth <= 0f || textureHeight <= 0f) return UiUvRect.Full
        val u0 = (x.resolve(textureWidth) / textureWidth).coerceIn(0f, 1f)
        val v0 = (y.resolve(textureHeight) / textureHeight).coerceIn(0f, 1f)
        val u1 = (u0 + width.resolve(textureWidth) / textureWidth).coerceIn(u0, 1f)
        val v1 = (v0 + height.resolve(textureHeight) / textureHeight).coerceIn(v0, 1f)
        if (u1 <= u0 || v1 <= v0) return UiUvRect.Full
        return UiUvRect(u0, v0, u1, v1)
    }

    companion object {
        val Full = UiImageUv()
    }
}

data class UiUvRect(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val width get() = u1 - u0
    val height get() = v1 - v0

    companion object {
        val Full = UiUvRect(0f, 0f, 1f, 1f)
    }
}

sealed interface UiPaint {
    data object None : UiPaint
    data class Color(val color: UiColor) : UiPaint
    data class LinearGradient(val angleDegrees: Float, val stops: List<UiGradientStop>) : UiPaint
    data class RadialGradient(val gradient: UiRadialGradient) : UiPaint
    data class Image(val source: String) : UiPaint
    data class Shader(val name: String) : UiPaint
}

/** Converts a CSS gradient angle to radians in the UI's rightward-X, downward-Y coordinates. */
internal fun linearGradientRadians(angleDegrees: Float): Double = (angleDegrees.mod(360f) - 90f) * PI / 180.0

data class UiRadialGradient(
    val centerX: UiLength = 50.percent,
    val centerY: UiLength = 50.percent,
    val radius: UiLength = 50.percent,
    val stops: List<UiGradientStop>,
)

data class UiGradientStop(
    val offset: Float,
    val color: UiColor,
)

data class UiShadow(
    val offset: UiVec3 = UiVec3(),
    val blur: Float = 0f,
    val spread: Float = 0f,
    val color: UiColor = UiColor.Transparent,
    val inset: Boolean = false,
) {
    fun interpolate(to: UiShadow, progress: Float) = UiShadow(
        offset = interpolateVec3(offset, to.offset, progress),
        blur = blur + (to.blur - blur) * progress,
        spread = spread + (to.spread - spread) * progress,
        color = color.interpolate(to.color, progress),
        inset = if (progress >= 1f) to.inset else inset,
    )
}

data class UiFilterChain(
    val effects: List<UiFilterEffect> = emptyList(),
) {
    val requiresLayer: Boolean
    private val grayscale: Float
    private val blur: Float

    init {
        var layer = false
        var grayscaleAmount = 0f
        var blurRadius = 0f
        for (effect in effects) {
            if (effect.requiresLayer) layer = true
            if (effect is UiFilterEffect.Grayscale) grayscaleAmount += effect.amount
            if (effect is UiFilterEffect.Blur) blurRadius += effect.radius
        }
        requiresLayer = layer
        grayscale = grayscaleAmount.coerceIn(0f, 1f)
        blur = blurRadius.coerceAtLeast(0f)
    }

    fun grayscaleAmount(): Float = grayscale

    fun blurRadius(): Float = blur

    fun withoutBlur(): UiFilterChain = UiFilterChain(effects.filterNot { it is UiFilterEffect.Blur })

    /** The gradient mask to apply, if the chain carries one. */
    fun linearMask(): UiFilterEffect.LinearMask? =
        effects.filterIsInstance<UiFilterEffect.LinearMask>().lastOrNull()?.takeIf { it.stops.isNotEmpty() }

    fun interpolate(to: UiFilterChain, progress: Float): UiFilterChain {
        if (effects.size != to.effects.size) return if (progress >= 1f) to else this
        return UiFilterChain(effects.zip(to.effects) { from, target -> from.interpolate(target, progress) })
    }

    companion object {
        val Empty = UiFilterChain()
    }
}

sealed interface UiFilterEffect {
    val requiresLayer: Boolean

    fun interpolate(to: UiFilterEffect, progress: Float): UiFilterEffect

    data class Grayscale(val amount: Float) : UiFilterEffect {
        override val requiresLayer: Boolean get() = amount != 0f

        override fun interpolate(to: UiFilterEffect, progress: Float): UiFilterEffect {
            if (to !is Grayscale) return if (progress >= 1f) to else this
            return Grayscale(amount + (to.amount - amount) * progress)
        }
    }

    data class Blur(val radius: Float) : UiFilterEffect {
        override val requiresLayer: Boolean get() = radius > 0f

        override fun interpolate(to: UiFilterEffect, progress: Float): UiFilterEffect {
            if (to !is Blur) return if (progress >= 1f) to else this
            return Blur(radius + (to.radius - radius) * progress)
        }
    }

    data class Shader(val name: String, val arguments: Map<String, Float> = emptyMap()) : UiFilterEffect {
        override val requiresLayer: Boolean get() = true

        override fun interpolate(to: UiFilterEffect, progress: Float): UiFilterEffect = if (progress >= 1f) to else this
    }

    /**
     * A CSS-style gradient mask: what the node draws keeps the alpha of the gradient at that point,
     * so a list can fade out towards its edges instead of being cut off.
     */
    data class LinearMask(val angle: Float, val stops: List<MaskStop>) : UiFilterEffect {
        override val requiresLayer: Boolean get() = stops.isNotEmpty()

        override fun interpolate(to: UiFilterEffect, progress: Float): UiFilterEffect {
            if (to !is LinearMask || to.stops.size != stops.size) return if (progress >= 1f) to else this
            return LinearMask(
                angle + (to.angle - angle) * progress,
                stops.zip(to.stops) { from, target -> from.interpolate(target, progress) },
            )
        }

        companion object {
            /** The shader carries this many stops; more are sampled down to it when compiled. */
            const val MAX_STOPS = 4
        }
    }
}

/** One stop of a [UiFilterEffect.LinearMask]: how visible the node is at [position] along the gradient. */
data class MaskStop(val position: Float, val alpha: Float) {
    fun interpolate(to: MaskStop, progress: Float) = MaskStop(
        position + (to.position - position) * progress,
        alpha + (to.alpha - alpha) * progress,
    )
}

data class UiScrollbarStyle(
    val thickness: UiLength? = null,
    val margin: UiLength? = null,
    val minThumbSize: UiLength? = null,
    val overlay: Boolean? = null,
    val atStart: Boolean? = null,
    val track: UiScrollbarPartStyle = UiScrollbarPartStyle(),
    val thumb: UiScrollbarPartStyle = UiScrollbarPartStyle(),
    val thumbHover: UiScrollbarPartStyle = UiScrollbarPartStyle(),
    val hoverThickness: UiLength? = null,
) {
    fun merge(other: UiScrollbarStyle): UiScrollbarStyle = UiScrollbarStyle(
        thickness = other.thickness ?: thickness,
        margin = other.margin ?: margin,
        minThumbSize = other.minThumbSize ?: minThumbSize,
        overlay = other.overlay ?: overlay,
        atStart = other.atStart ?: atStart,
        track = track.merge(other.track),
        thumb = thumb.merge(other.thumb),
        thumbHover = thumbHover.merge(other.thumbHover),
        hoverThickness = other.hoverThickness ?: hoverThickness,
    )

    fun resolved(reference: Float, hover: Float = 0f): ResolvedUiScrollbarStyle {
        val base = (thickness ?: DefaultThickness).resolve(reference).coerceAtLeast(0f)
        if (base <= 0f) return HiddenScrollbar
        val full = hoverThickness?.takeIf { hover > 0f && overlay == true }?.resolve(reference)?.coerceAtLeast(base)
        val resolvedThickness = full?.let { base + (it - base) * hover.coerceIn(0f, 1f) } ?: base
        return ResolvedUiScrollbarStyle(
            overlay = overlay == true,
            atStart = overlay == true && atStart == true,
            thickness = resolvedThickness,
            margin = (margin ?: DefaultMargin).resolve(reference).coerceAtLeast(0f),
            minThumbSize = (minThumbSize ?: DefaultMinThumbSize).resolve(reference).coerceAtLeast(1f),
            track = track,
            thumb = thumb,
        )
    }

    companion object {
        val DefaultThickness: UiLength = 3.5.px
        val DefaultMargin: UiLength = 3.px
        val DefaultMinThumbSize: UiLength = 18.px

        private val HiddenScrollbar = ResolvedUiScrollbarStyle(
            overlay = false,
            atStart = false,
            thickness = 0f,
            margin = 0f,
            minThumbSize = 1f,
            track = UiScrollbarPartStyle(),
            thumb = UiScrollbarPartStyle(),
        )
    }
}

data class UiScrollbarPartStyle(
    val paint: UiPaint? = null,
    val border: UiBorder? = null,
    val radius: Float? = null,
    val fit: UiImageFit? = null,
    val slice: UiInsets? = null,
) {
    fun merge(other: UiScrollbarPartStyle): UiScrollbarPartStyle = UiScrollbarPartStyle(
        paint = other.paint ?: paint,
        border = other.border ?: border,
        radius = other.radius ?: radius,
        fit = other.fit ?: fit,
        slice = other.slice ?: slice,
    )
}

data class ResolvedUiScrollbarStyle(
    val overlay: Boolean,
    val atStart: Boolean,
    val thickness: Float,
    val margin: Float,
    val minThumbSize: Float,
    val track: UiScrollbarPartStyle,
    val thumb: UiScrollbarPartStyle,
) {
    val isVisible: Boolean get() = thickness > 0f
    val gutter: Float get() = if (isVisible && !overlay) thickness + margin * 2f else 0f
}

internal fun interpolatePaint(from: UiPaint, to: UiPaint, progress: Float): UiPaint {
    if (from is UiPaint.Color && to is UiPaint.Color) {
        return UiPaint.Color(from.color.interpolate(to.color, progress))
    }
    if (from is UiPaint.LinearGradient && to is UiPaint.LinearGradient && from.stops.size == to.stops.size) {
        return UiPaint.LinearGradient(
            angleDegrees = from.angleDegrees + (to.angleDegrees - from.angleDegrees) * progress,
            stops = interpolateStops(from.stops, to.stops, progress),
        )
    }
    if (from is UiPaint.RadialGradient && to is UiPaint.RadialGradient && from.gradient.stops.size == to.gradient.stops.size) {
        return UiPaint.RadialGradient(
            gradient = from.gradient.copy(stops = interpolateStops(from.gradient.stops, to.gradient.stops, progress)),
        )
    }
    return if (progress >= 1f) to else from
}

internal fun interpolateOptionalPaint(from: UiPaint?, to: UiPaint?, progress: Float): UiPaint? {
    if (from == null || to == null) return if (progress >= 1f) to else from
    return interpolatePaint(from, to, progress)
}

private fun interpolateStops(from: List<UiGradientStop>, to: List<UiGradientStop>, progress: Float) =
    from.zip(to) { start, target ->
        UiGradientStop(
            offset = start.offset + (target.offset - start.offset) * progress,
            color = start.color.interpolate(target.color, progress),
        )
    }

internal fun interpolateShadows(from: List<UiShadow>, to: List<UiShadow>, progress: Float): List<UiShadow> {
    if (from.size != to.size) return if (progress >= 1f) to else from
    return from.zip(to) { start, target -> start.interpolate(target, progress) }
}

internal fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress

/**
 * Interpolates a [UiLength] for `transition: width/height/size`. Only same-kind pixel and percentage
 * values blend smoothly (the common case: an animated bar width, a growing panel); Fit/Auto/Fill and
 * cross-kind pairs have no meaningful midpoint, so they snap to the target instead of holding a stale
 * value.
 */
internal fun interpolateLength(from: UiLength, to: UiLength, progress: Float): UiLength = when {
    from is UiLength.Px && to is UiLength.Px -> UiLength.Px(lerp(from.value, to.value, progress))
    from is UiLength.Percent && to is UiLength.Percent -> UiLength.Percent(lerp(from.value, to.value, progress))
    else -> to
}

internal fun interpolateVec3(from: UiVec3, to: UiVec3, progress: Float) = UiVec3(
    x = from.x + (to.x - from.x) * progress,
    y = from.y + (to.y - from.y) * progress,
    z = from.z + (to.z - from.z) * progress,
)

// Combining semantics for stackable props: overlapping rules/modifiers accumulate instead
// of the last one winning, so states (`:hover`, custom states) compose their effects.
internal fun addVec3(a: UiVec3, b: UiVec3) = UiVec3(a.x + b.x, a.y + b.y, a.z + b.z)

internal fun mulVec3(a: UiVec3, b: UiVec3) = UiVec3(a.x * b.x, a.y * b.y, a.z * b.z)

internal fun mulColor(a: UiColor, b: UiColor) = UiColor(
    red = a.red * b.red,
    green = a.green * b.green,
    blue = a.blue * b.blue,
    alpha = a.alpha * b.alpha,
)
