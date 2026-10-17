package ru.hollowhorizon.hollowengine.client.ui

import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.client.ui.UiLength.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import kotlin.math.*

data class UiEntityView(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    companion object {
        val Portrait = UiEntityView()
    }
}

/**
 * The entity a node draws.
 */
class UiEntityRef private constructor(
    private val direct: Entity?,
    val networkId: Int,
    val view: UiEntityView,
) {
    /** The entity to draw, or null when the id names nothing the client currently knows about. */
    fun resolve(): Entity? = direct ?: Minecraft.getInstance().level?.getEntity(networkId)

    override fun equals(other: Any?): Boolean =
        other is UiEntityRef && other.direct === direct && other.networkId == networkId && other.view == view

    override fun hashCode(): Int = 31 * (31 * (direct?.id ?: 0) + networkId) + view.hashCode()

    override fun toString(): String = "UiEntityRef(${direct?.name?.string ?: networkId})"

    companion object {
        fun of(entity: Entity, view: UiEntityView = UiEntityView.Portrait): UiEntityRef =
            UiEntityRef(entity, entity.id, view)

        /** Names an entity by the id it has on this client; see [Entity.getId]. */
        fun ofId(networkId: Int, view: UiEntityView = UiEntityView.Portrait): UiEntityRef =
            UiEntityRef(null, networkId, view)
    }
}

/**
 * An interaction state, matched by HSS `:name` selectors. Open by design: any name is a
 * valid state, so widgets and users can define their own (`Modifier.state("expanded")`,
 * `.element:expanded { ... }`) beyond the built-in set. Names are normalized to
 * lowercase so selector and modifier casing never diverge.
 */
class UiState private constructor(val name: String) {
    override fun equals(other: Any?): Boolean = other is UiState && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name

    companion object {
        val HOVER = UiState("hover")
        val ACTIVE = UiState("active")
        val FOCUS = UiState("focus")
        val SELECTED = UiState("selected")
        val DISABLED = UiState("disabled")
        val DRAGGING = UiState("dragging")
        val CLOSING = UiState("closing")

        fun of(name: String): UiState = UiState(name.trim().lowercase())

        /** States the engine itself sets; user-defined ones are equally valid. */
        val builtIns: List<UiState> = listOf(HOVER, ACTIVE, FOCUS, SELECTED, DISABLED, DRAGGING, CLOSING)
    }
}

/** Node types the built-in composables produce; what a `type` selector may name. */
val UiBuiltInElementTypes: List<String> = listOf(
    UiBoxType,
    UiTextType,
    UiSpanType,
    UiImageType,
    UiItemType,
    UiEntityType,
    UiSliderType,
    UiCheckboxType,
    UiPopupType,
)

const val UiBoxType = "box"
const val UiTextType = "text"
const val UiSpanType = "span"
const val UiImageType = "image"
const val UiItemType = "item"
const val UiEntityType = "entity"
const val UiSliderType = "slider"
const val UiCheckboxType = "checkbox"
const val UiPopupType = "popup"

enum class UiBoxMode {
    FREE,
    STACK
}

enum class UiBuiltInMeasurePolicyKind {
    COLUMN,
    ROW,
    BOX,
    INLINE_FLOW
}

data class UiConstraints(
    val minWidth: Float = 0f,
    val maxWidth: Float = Float.POSITIVE_INFINITY,
    val minHeight: Float = 0f,
    val maxHeight: Float = Float.POSITIVE_INFINITY,
) {
    init {
        require(minWidth >= 0f && minHeight >= 0f) { "Constraint minimums must be non-negative" }
        require(maxWidth >= minWidth) { "maxWidth must be greater than or equal to minWidth" }
        require(maxHeight >= minHeight) { "maxHeight must be greater than or equal to minHeight" }
    }

    fun constrainWidth(width: Float): Float = width.coerceIn(minWidth, maxWidth)

    fun constrainHeight(height: Float): Float = height.coerceIn(minHeight, maxHeight)

    companion object {
        fun fixed(width: Float, height: Float): UiConstraints {
            return UiConstraints(width, width, height, height)
        }
    }
}

fun interface UiMeasurePolicy {
    fun UiMeasureScope.measure(measurables: List<UiMeasurable>, constraints: UiConstraints): UiMeasureResult
}

data class UiBuiltInMeasurePolicy(
    val kind: UiBuiltInMeasurePolicyKind,
    val boxMode: UiBoxMode = UiBoxMode.FREE,
) : UiMeasurePolicy {
    override fun UiMeasureScope.measure(
        measurables: List<UiMeasurable>,
        constraints: UiConstraints,
    ): UiMeasureResult {
        return layout(constraints.minWidth, constraints.minHeight)
    }
}

object UiMeasurePolicies {
    val Column = UiBuiltInMeasurePolicy(UiBuiltInMeasurePolicyKind.COLUMN)
    val Row = UiBuiltInMeasurePolicy(UiBuiltInMeasurePolicyKind.ROW)

    /** Line-wrapping flow: spans/widgets fill each line and the remainder wraps to the next. */
    val InlineFlow = UiBuiltInMeasurePolicy(UiBuiltInMeasurePolicyKind.INLINE_FLOW)

    fun box(mode: UiBoxMode = UiBoxMode.FREE): UiMeasurePolicy {
        return UiBuiltInMeasurePolicy(UiBuiltInMeasurePolicyKind.BOX, mode)
    }
}

interface UiMeasurable {
    val node: UiNode
    fun measure(constraints: UiConstraints): UiPlaceable
}

data class UiPlaceable(
    val width: Float,
    val height: Float,
    internal val node: UiNode,
)

data class UiMeasureResult(
    val width: Float,
    val height: Float,
    internal val placements: List<UiPlacement>,
)

data class UiPlacement(
    val placeable: UiPlaceable,
    val x: Float,
    val y: Float,
)

class UiMeasureScope {
    fun layout(width: Float, height: Float, block: UiPlacementScope.() -> Unit = {}): UiMeasureResult {
        val scope = UiPlacementScope()
        scope.block()
        return UiMeasureResult(width.coerceAtLeast(0f), height.coerceAtLeast(0f), scope.placements)
    }
}

class UiPlacementScope internal constructor() {
    internal val placements = mutableListOf<UiPlacement>()

    fun UiPlaceable.place(x: Float, y: Float) {
        placements += UiPlacement(this, x, y)
    }

    fun UiPlaceable.place(x: Int, y: Int) {
        place(x.toFloat(), y.toFloat())
    }
}

data class UiPopupAlignment(
    val anchorHorizontal: UiAlign = UiAlign.START,
    val anchorVertical: UiAlign = UiAlign.END,
    val popupHorizontal: UiAlign = UiAlign.START,
    val popupVertical: UiAlign = UiAlign.START,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    companion object {
        val BelowStart = UiPopupAlignment()
        val Cursor = UiPopupAlignment(anchorVertical = UiAlign.START, offsetX = 8f, offsetY = 8f)
    }
}

enum class UiAlign {
    AUTO,
    START,
    CENTER,
    END,
    STRETCH,
    SPACE_BETWEEN,
    SPACE_AROUND,
    SPACE_EVENLY,

    JUSTIFY
}

/**
 * How an inline group's box decoration (background, padding, border) is drawn when the group is
 * split across several lines by wrapping - mirrors CSS `box-decoration-break`.
 */
enum class UiBoxDecorationBreak {
    /** One logical box sliced at line edges: continuous background, outer padding/rounding only. */
    SLICE,

    /** Each line fragment is a complete box with its own padding, border and rounding. */
    CLONE
}

enum class UiTextAlign {
    LEFT,
    RIGHT,
    CENTER,
    JUSTIFY
}

/**
 * How runs of whitespace are treated when laying out text.
 * [COLLAPSE] folds consecutive spaces into one and drops leading/trailing spaces (prose default);
 * [PRESERVE] keeps every space verbatim, so indentation and columns survive (code editors).
 */
enum class UiWhitespace {
    COLLAPSE,
    PRESERVE,
}

enum class UiCursorShape {
    DEFAULT,
    HAND,
    MOVE,
    TEXT,
    CROSSHAIR,
    RESIZE_HORIZONTAL,
    RESIZE_VERTICAL,
    RESIZE_NESW,
    RESIZE_NWSE,
    NOT_ALLOWED
}

sealed interface UiLength {
    /** Content-sized, but may shrink to the available space and stretch under `align: stretch`. */
    data object Auto : UiLength

    /**
     * Fit-content: the box is exactly as large as its children/content and never shrinks to the
     * available space nor stretches. This is the default for `width`/`height`, so a `Row` of text
     * keeps its natural size instead of being squeezed. Use [Auto]/[Fill]/percent for flexible boxes.
     */
    data object Fit : UiLength
    data object Fill : UiLength
    data class Px(val value: Float) : UiLength
    data class Percent(val value: Float) : UiLength
    data class Addition(val first: UiLength, val second: UiLength) : UiLength
    data class Substraction(val first: UiLength, val second: UiLength) : UiLength

    fun resolve(reference: Float, autoValue: Float = 0f): Float = when (this) {
        Auto, Fit -> autoValue
        Fill -> reference
        is Px -> value
        is Percent -> reference * value
        is Addition -> first.resolve(reference, autoValue) + second.resolve(reference, autoValue)
        is Substraction -> first.resolve(reference, autoValue) - second.resolve(reference, autoValue)
    }
}

/** Content-sized axes ([UiLength.Auto] and [UiLength.Fit]) whose size is driven by their content. */
val UiLength.isContentSized: Boolean get() = this is Auto || this is Fit

operator fun UiLength.plus(other: UiLength): UiLength = Addition(this, other)
operator fun UiLength.minus(other: UiLength): UiLength = Substraction(this, other)

val Number.px: Px get() = Px(toFloat())
val Number.percent: Percent get() = Percent(toFloat() / 100f)

data class UiSize(
    val width: UiLength = Auto,
    val height: UiLength = Auto,
)

data class UiInsets(
    val left: UiLength = 0.px,
    val top: UiLength = 0.px,
    val right: UiLength = 0.px,
    val bottom: UiLength = 0.px,
) {
    companion object {
        val Zero = UiInsets()

        fun all(value: UiLength) = UiInsets(value, value, value, value)

        fun hv(horizontal: UiLength, vertical: UiLength) = UiInsets(horizontal, vertical, horizontal, vertical)
    }
}

data class UiVec3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
) {
    companion object {
        val Zero = UiVec3()
        val One = UiVec3(1f, 1f, 1f)
    }
}

data class UiPosition(
    val x: UiLength = 0.px,
    val y: UiLength = 0.px,
    val z: Float = 0f,
) {
    fun resolve(parentWidth: Float, parentHeight: Float): UiVec3 = UiVec3(
        x = x.resolve(parentWidth),
        y = y.resolve(parentHeight),
        z = z,
    )
}

data class UiTransformPivot(
    val x: UiLength = 50.percent,
    val y: UiLength = 50.percent,
    val z: UiLength = 0.px,
) {
    fun resolve(width: Float, height: Float, depth: Float = 0f): UiVec3 = UiVec3(
        x = x.resolve(width),
        y = y.resolve(height),
        z = z.resolve(depth),
    )

    companion object {
        val Center = UiTransformPivot()
        val TopLeft = UiTransformPivot(0.px, 0.px, 0.px)
        val TopCenter = UiTransformPivot(50.percent, 0.px, 0.px)
        val TopRight = UiTransformPivot(100.percent, 0.px, 0.px)
        val CenterLeft = UiTransformPivot(0.px, 50.percent, 0.px)
        val CenterRight = UiTransformPivot(100.percent, 50.percent, 0.px)
        val BottomLeft = UiTransformPivot(0.px, 100.percent, 0.px)
        val BottomCenter = UiTransformPivot(50.percent, 100.percent, 0.px)
        val BottomRight = UiTransformPivot(100.percent, 100.percent, 0.px)
    }
}

// TODO: Replace with Color from common.utils
data class UiColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float = 1f,
) {
    /**
     * Mixes in premultiplied alpha, as CSS does. A straight mix from `transparent`, which is black at
     * zero alpha, would pass through a dark translucent gray and flash darker before fading in.
     */
    fun interpolate(to: UiColor, progress: Float): UiColor {
        val mixedAlpha = alpha + (to.alpha - alpha) * progress
        if (mixedAlpha <= MinPremultipliedAlpha) {
            return UiColor(
                red = red + (to.red - red) * progress,
                green = green + (to.green - green) * progress,
                blue = blue + (to.blue - blue) * progress,
                alpha = mixedAlpha,
            )
        }
        fun channel(from: Float, target: Float) =
            (from * alpha + (target * to.alpha - from * alpha) * progress) / mixedAlpha
        return UiColor(channel(red, to.red), channel(green, to.green), channel(blue, to.blue), mixedAlpha)
    }

    fun toArgb(): Int {
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    companion object {
        val Transparent = UiColor(0f, 0f, 0f, 0f)
        val White = UiColor(1f, 1f, 1f, 1f)
        val Black = UiColor(0f, 0f, 0f, 1f)

        /** Below this the mix has no color worth recovering, and dividing by it would blow up. */
        private const val MinPremultipliedAlpha = 1e-4f

        fun fromArgb(argb: Int) = UiColor(
            red = (argb ushr 16 and 0xFF) / 255f,
            green = (argb ushr 8 and 0xFF) / 255f,
            blue = (argb and 0xFF) / 255f,
            alpha = (argb ushr 24 and 0xFF) / 255f,
        )
    }
}

/**
 * The ring drawn around a node. [paint] is a full paint, so a border can carry
 * a gradient the same way a background does.
 */
data class UiBorder(
    val width: UiInsets = UiInsets.Zero,
    val paint: UiPaint = UiPaint.None,
    val radius: Float = 0f,
    val dash: UiBorderDash? = null,
) {
    constructor(width: UiInsets, color: UiColor, radius: Float = 0f) : this(width, UiPaint.Color(color), radius)

    /** The flat color of a solid border, and `null` for a gradient one. */
    val color: UiColor? get() = (paint as? UiPaint.Color)?.color
}

/**
 * Dashes along a border, measured along its center line.
 */
data class UiBorderDash(val length: Float, val gap: Float) {
    val isSolid: Boolean get() = length <= 0f || gap <= 0f
}

data class UiTransform(
    val translate: UiVec3 = UiVec3(),
    val rotate: UiVec3 = UiVec3(),
    val scale: UiVec3 = UiVec3(1f, 1f, 1f),
    val pivot: UiTransformPivot = UiTransformPivot.Center,
    val perspective: Float = 0f,
) {
    val needsFramebuffer: Boolean get() = !rotate.x.isAlmostZero() || !rotate.y.isAlmostZero()

    val isTranslationOnly: Boolean
        get() = rotate == UiVec3.Zero && scale == UiVec3.One && perspective == 0f

    private fun Float.isAlmostZero(epsilon: Float = 0.0001f): Boolean =
        abs(this) <= epsilon

    fun matrix(
        pivotPoint: UiVec3 = pivot.resolve(0f, 0f),
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        offsetZ: Float = 0f,
    ): UiMatrix4 = UiMatrix4.compose(this, pivotPoint, offsetX, offsetY, offsetZ)
}

private fun Float.degreesToRadians(): Float = this * PI.toFloat() / 180f

class UiMatrix4(private val values: FloatArray) {
    fun toMatrix4f(): Matrix4f = Matrix4f(
        values[0], values[4], values[8], values[12],
        values[1], values[5], values[9], values[13],
        values[2], values[6], values[10], values[14],
        values[3], values[7], values[11], values[15],
    )

    /**
     * Cached [inverse] result. Matrices are immutable after construction, and input handling +
     * rendering invert the same placement matrices every event/frame, so the inversion runs once.
     * Volatile: computed lazily from both the frame-builder and render threads.
     */
    @Volatile
    private var inverseCache: Any? = null

    /** Lazily computed "translation+scale only" flag: 0 unknown, 1 yes, 2 no. */
    @Volatile
    private var axisAlignedState: Byte = 0

    /**
     * Whether the matrix maps axis-aligned rectangles to axis-aligned rectangles (no rotation,
     * shear or perspective). The common case for UI transforms; enables allocation-free point
     * containment tests.
     */
    val isAxisAligned: Boolean
        get() {
            val state = axisAlignedState
            if (state != 0.toByte()) return state == 1.toByte()
            val aligned = values[1] == 0f && values[2] == 0f &&
                    values[4] == 0f && values[6] == 0f &&
                    values[8] == 0f && values[9] == 0f &&
                    values[12] == 0f && values[13] == 0f && values[14] == 0f && values[15] == 1f
            axisAlignedState = if (aligned) 1 else 2
            return aligned
        }

    /** Whether perspective division varies across points in the local XY plane. */
    internal val hasPlanarPerspective: Boolean
        get() = values[12] != 0f || values[13] != 0f

    /** X of the transformed point without allocating a [UiVec3]; valid when [isAxisAligned]. */
    fun transformX(x: Float): Float = values[0] * x + values[3]

    /** Y of the transformed point without allocating a [UiVec3]; valid when [isAxisAligned]. */
    fun transformY(y: Float): Float = values[5] * y + values[7]

    operator fun times(other: UiMatrix4): UiMatrix4 {
        if (this === Identity) return other
        if (other === Identity) return this
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                var sum = 0f
                for (i in 0 until 4) {
                    sum += values[row * 4 + i] * other.values[i * 4 + column]
                }
                result[row * 4 + column] = sum
            }
        }
        return UiMatrix4(result)
    }

    fun translated(x: Float, y: Float, z: Float = 0f): UiMatrix4 {
        if (x == 0f && y == 0f && z == 0f) return this
        return UiMatrix4(values.copyOf()).also { it.appendTranslation(x, y, z) }
    }

    fun transform(x: Float, y: Float, z: Float = 0f): UiVec3 {
        val tx = values[0] * x + values[1] * y + values[2] * z + values[3]
        val ty = values[4] * x + values[5] * y + values[6] * z + values[7]
        val tz = values[8] * x + values[9] * y + values[10] * z + values[11]
        val tw = values[12] * x + values[13] * y + values[14] * z + values[15]
        if (tw == 0f || tw == 1f) return UiVec3(tx, ty, tz)
        return UiVec3(tx / tw, ty / tw, tz / tw)
    }

    internal fun transform(x: Float, y: Float, z: Float, destination: FloatArray, offset: Int) {
        val tx = values[0] * x + values[1] * y + values[2] * z + values[3]
        val ty = values[4] * x + values[5] * y + values[6] * z + values[7]
        val tz = values[8] * x + values[9] * y + values[10] * z + values[11]
        val tw = values[12] * x + values[13] * y + values[14] * z + values[15]
        if (tw == 0f || tw == 1f) {
            destination[offset] = tx
            destination[offset + 1] = ty
            destination[offset + 2] = tz
        } else {
            destination[offset] = tx / tw
            destination[offset + 1] = ty / tw
            destination[offset + 2] = tz / tw
        }
    }

    internal fun writeValues(destination: FloatArray, offset: Int) {
        values.copyInto(destination, offset)
    }

    internal fun axisScales(destination: FloatArray) {
        val originW = values[15]
        val originX = if (originW == 0f || originW == 1f) values[3] else values[3] / originW
        val originY = if (originW == 0f || originW == 1f) values[7] else values[7] / originW

        val xW = values[12] + values[15]
        val xPointX = values[0] + values[3]
        val xPointY = values[4] + values[7]
        val resolvedX = if (xW == 0f || xW == 1f) xPointX else xPointX / xW
        val resolvedXY = if (xW == 0f || xW == 1f) xPointY else xPointY / xW

        val yW = values[13] + values[15]
        val yPointX = values[1] + values[3]
        val yPointY = values[5] + values[7]
        val resolvedYX = if (yW == 0f || yW == 1f) yPointX else yPointX / yW
        val resolvedY = if (yW == 0f || yW == 1f) yPointY else yPointY / yW

        val xDelta = resolvedX - originX
        val xDeltaY = resolvedXY - originY
        val yDeltaX = resolvedYX - originX
        val yDelta = resolvedY - originY
        destination[0] = sqrt(xDelta * xDelta + xDeltaY * xDeltaY)
        destination[1] = sqrt(yDeltaX * yDeltaX + yDelta * yDelta)
    }

    fun inverse(): UiMatrix4? {
        if (this === Identity) return this
        when (val cached = inverseCache) {
            SingularInverse -> return null
            is UiMatrix4 -> return cached
        }
        return computeInverse().also { inverseCache = it ?: SingularInverse }
    }

    private fun computeInverse(): UiMatrix4? {
        val m = values
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
                m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
                m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
                m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
                m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
                m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
                m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
                m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
                m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
                m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
                m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
                m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
                m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
                m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
                m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
                m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
                m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        var determinant = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (determinant == 0f) return null
        determinant = 1f / determinant
        for (i in inv.indices) inv[i] *= determinant
        return UiMatrix4(inv)
    }

    companion object {
        /** Sentinel marking a computed-but-singular inverse in [inverseCache]. */
        private val SingularInverse = Any()

        private val Identity = UiMatrix4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
        )

        fun identity(): UiMatrix4 = Identity

        fun translation(x: Float, y: Float, z: Float): UiMatrix4 {
            if (x == 0f && y == 0f && z == 0f) return Identity
            return mutableIdentity().also { it.appendTranslation(x, y, z) }
        }

        fun scale(x: Float, y: Float, z: Float): UiMatrix4 {
            if (x == 1f && y == 1f && z == 1f) return Identity
            return mutableIdentity().also { it.appendScale(x, y, z) }
        }

        fun rotationX(radians: Float): UiMatrix4 {
            if (radians == 0f) return Identity
            return mutableIdentity().also { it.appendRotationX(radians) }
        }

        fun rotationY(radians: Float): UiMatrix4 {
            if (radians == 0f) return Identity
            return mutableIdentity().also { it.appendRotationY(radians) }
        }

        fun rotationZ(radians: Float): UiMatrix4 {
            if (radians == 0f) return Identity
            return mutableIdentity().also { it.appendRotationZ(radians) }
        }

        fun perspective(distance: Float) = UiMatrix4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, -1f / distance, 1f,
            )
        )

        internal fun compose(
            transform: UiTransform,
            pivot: UiVec3,
            offsetX: Float,
            offsetY: Float,
            offsetZ: Float,
        ): UiMatrix4 {
            val translateX = offsetX + transform.translate.x
            val translateY = offsetY + transform.translate.y
            val translateZ = offsetZ + transform.translate.z
            val noRotation = transform.rotate.x == 0f && transform.rotate.y == 0f && transform.rotate.z == 0f
            val noScale = transform.scale.x == 1f && transform.scale.y == 1f && transform.scale.z == 1f
            if (translateX == 0f && translateY == 0f && translateZ == 0f &&
                noRotation && noScale && transform.perspective == 0f
            ) {
                return Identity
            }

            val result = mutableIdentity()
            result.appendTranslation(translateX, translateY, translateZ)
            result.appendTranslation(pivot.x, pivot.y, pivot.z)
            if (transform.perspective != 0f) result.appendPerspective(transform.perspective)
            if (transform.rotate.x != 0f) result.appendRotationX(transform.rotate.x.degreesToRadians())
            if (transform.rotate.y != 0f) result.appendRotationY(transform.rotate.y.degreesToRadians())
            if (transform.rotate.z != 0f) result.appendRotationZ(transform.rotate.z.degreesToRadians())
            if (!noScale) result.appendScale(transform.scale.x, transform.scale.y, transform.scale.z)
            result.appendTranslation(-pivot.x, -pivot.y, -pivot.z)
            return result
        }

        private fun mutableIdentity(): UiMatrix4 = UiMatrix4(Identity.values.copyOf())
    }

    private fun appendTranslation(x: Float, y: Float, z: Float) {
        if (x == 0f && y == 0f && z == 0f) return
        for (row in 0 until 4) {
            val offset = row * 4
            values[offset + 3] += values[offset] * x + values[offset + 1] * y + values[offset + 2] * z
        }
    }

    private fun appendScale(x: Float, y: Float, z: Float) {
        for (row in 0 until 4) {
            val offset = row * 4
            values[offset] *= x
            values[offset + 1] *= y
            values[offset + 2] *= z
        }
    }

    private fun appendRotationX(radians: Float) {
        val c = cos(radians)
        val s = sin(radians)
        for (row in 0 until 4) {
            val offset = row * 4
            val y = values[offset + 1]
            val z = values[offset + 2]
            values[offset + 1] = y * c + z * s
            values[offset + 2] = z * c - y * s
        }
    }

    private fun appendRotationY(radians: Float) {
        val c = cos(radians)
        val s = sin(radians)
        for (row in 0 until 4) {
            val offset = row * 4
            val x = values[offset]
            val z = values[offset + 2]
            values[offset] = x * c - z * s
            values[offset + 2] = x * s + z * c
        }
    }

    private fun appendRotationZ(radians: Float) {
        val c = cos(radians)
        val s = sin(radians)
        for (row in 0 until 4) {
            val offset = row * 4
            val x = values[offset]
            val y = values[offset + 1]
            values[offset] = x * c + y * s
            values[offset + 1] = y * c - x * s
        }
    }

    private fun appendPerspective(distance: Float) {
        val inverseDistance = -1f / distance
        for (row in 0 until 4) {
            val offset = row * 4
            values[offset + 2] += values[offset + 3] * inverseDistance
        }
    }
}
