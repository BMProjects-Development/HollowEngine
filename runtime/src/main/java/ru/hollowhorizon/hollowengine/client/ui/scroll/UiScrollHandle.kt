package ru.hollowhorizon.hollowengine.client.ui.scroll

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.UiVec3
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import kotlin.math.hypot

/**
 * The scroll position of one scroll container.
 */
class UiScrollHandle {
    var offsetX: Float by mutableStateOf(0f)
        internal set
    var offsetY: Float by mutableStateOf(0f)
        internal set

    /** The scroll container's content box in root coordinates (updated after each layout). */
    var viewport: UiRect by mutableStateOf(UiRect.Zero)
        internal set

    /** From the container to the screen, through every transform above it; see [toScreen]. */
    internal var screenTransform: UiMatrix4 = UiMatrix4.identity()
    internal var contentOffsetX = 0f
    internal var contentOffsetY = 0f

    fun toScreen(x: Float, y: Float): UiVec3 = screenTransform.transform(contentOffsetX + x, contentOffsetY + y)

    /** How many screen pixels one unit of the content spans, which a transform above can change. */
    val screenScale: Float
        get() {
            val origin = toScreen(0f, 0f)
            val unit = toScreen(1f, 0f)
            return hypot(unit.x - origin.x, unit.y - origin.y).takeIf { it > 0f } ?: 1f
        }

    /** How far each axis can still travel: the content size minus the viewport, never negative. */
    var range: UiScrollOffset by mutableStateOf(UiScrollOffset.Zero)
        internal set

    val offset: UiScrollOffset get() = UiScrollOffset(offsetX, offsetY)

    internal var target = UiScrollOffset.Zero
    internal var animationStart = UiScrollOffset.Zero
    internal var animationStartedAt = 0L
    internal var animating = false

    internal var pendingX: Float? = null
    internal var pendingY: Float? = null

    /** Whether [jumpTo] moved the offset, which the scroll state still has to count as a change. */
    internal var jumped = false

    /** What the last `scrollTo` asked for, kept until the layout has measured the content it scrolls. */
    internal var requestedX: Float? = null
    internal var requestedY: Float? = null
    internal var pendingAnimatedDeltaX = 0f
    internal var pendingAnimatedDeltaY = 0f

    fun scrollTo(x: Float? = null, y: Float? = null) {
        if (x != null) pendingX = x
        if (y != null) pendingY = y
    }

    /**
     * Moves to the offset at once, so the composition about to run and its layout both see it, as a view that changes
     * the size of its content in the same step must (a zoom driven from a frame callback): [scrollTo] lands only with
     * the next frame. The layout clamps the offset to the content it measures.
     */
    fun jumpTo(x: Float? = null, y: Float? = null) {
        val next = UiScrollOffset((x ?: offsetX).coerceAtLeast(0f), (y ?: offsetY).coerceAtLeast(0f))
        animating = false
        target = next
        pendingX = null
        pendingY = null
        requestedX = null
        requestedY = null
        if (applyOffset(next)) jumped = true
    }

    /**
     * The content moved ([deltaX], [deltaY]) under the view, as content that grows at its start does: the offset, where an
     * animation runs from and to and what was asked for follow it, so nothing on screen moves.
     */
    fun shiftContent(deltaX: Float = 0f, deltaY: Float = 0f) {
        target = UiScrollOffset(target.x + deltaX, target.y + deltaY)
        animationStart = UiScrollOffset(animationStart.x + deltaX, animationStart.y + deltaY)
        pendingX = pendingX?.plus(deltaX)
        pendingY = pendingY?.plus(deltaY)
        requestedX = requestedX?.plus(deltaX)
        requestedY = requestedY?.plus(deltaY)
        if (applyOffset(UiScrollOffset(offsetX + deltaX, offsetY + deltaY))) jumped = true
    }

    fun scrollBy(deltaX: Float = 0f, deltaY: Float = 0f) {
        scrollTo(
            x = (pendingX ?: offsetX) + deltaX,
            y = (pendingY ?: offsetY) + deltaY,
        )
    }

    fun animateScrollBy(deltaX: Float = 0f, deltaY: Float = 0f) {
        pendingAnimatedDeltaX += deltaX
        pendingAnimatedDeltaY += deltaY
    }

    internal fun applyOffset(next: UiScrollOffset): Boolean {
        if (offsetX == next.x && offsetY == next.y) return false
        offsetX = next.x
        offsetY = next.y
        return true
    }

    internal fun clampedTo(range: UiScrollOffset, x: Float? = null, y: Float? = null) = UiScrollOffset(
        x = (x ?: target.x).coerceIn(0f, range.x),
        y = (y ?: target.y).coerceIn(0f, range.y),
    )
}

@Composable
fun rememberScrollState(): UiScrollHandle = remember { UiScrollHandle() }
