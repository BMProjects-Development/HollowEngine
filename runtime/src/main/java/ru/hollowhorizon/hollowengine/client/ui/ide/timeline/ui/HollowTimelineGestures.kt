package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.Composable
import kotlin.math.exp
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle

internal const val MinDragUnit = 3f

/** How fast a zoom drag zooms: the view doubles or halves about every hundred pixels. */
private const val DragZoomRate = 0.007f

/**
 * A drag that zooms the view, by the keymap's zoom drag (Ctrl and the middle button, as in Blender): across, the
 * time around where it started, which stays under the pointer; up and down, in the graph, the values.
 */
internal class TimelineZoomDrag {
    private var active = false
    private var zoom = 0f
    private var time = 0f
    private var anchor = 0f
    private var span = 0f

    /** Starts zooming when [event] is the zoom drag; [localX] is where it is in the lanes' content. */
    fun begin(event: UiEvent, controller: TimelineController, scroll: UiScrollHandle, localX: Float): Boolean {
        active = HollowKeys.DRAG_ZOOM.matchesMouse(event.button, event.modifiers)
        if (!active) return false
        zoom = controller.pixelsPerSecond
        time = (localX - TimelineLeftPadding) / zoom
        anchor = localX - scroll.offsetX
        span = controller.curveAxis.targetSpan
        return true
    }

    /** Follows the drag; false when this drag is not a zoom. */
    fun drag(event: UiEvent, controller: TimelineController, scroll: UiScrollHandle, values: Boolean): Boolean {
        if (!active) return false
        val next = (zoom * exp(event.dragTotalX * DragZoomRate)).coerceIn(TimelineMinZoom, TimelineMaxZoom)
        controller.pixelsPerSecond = next
        scroll.scrollTo(x = (time * next + TimelineLeftPadding - anchor).coerceAtLeast(0f))
        if (values) {
            val axis = controller.curveAxis
            val scaled = (span * exp(event.dragTotalY * DragZoomRate)).coerceIn(TimelineMinValueSpan, TimelineMaxValueSpan)
            axis.snapTo(axis.targetCenter, scaled)
        }
        return true
    }

    fun end() {
        active = false
    }
}

internal class TimelinePanGesture {
    var scrollX = 0f
    var scrollY = 0f
    var center = 0f
    var moved = false

    var pressX = 0f
        private set
    var pressY = 0f
        private set
    var pressLocalX = 0f
        private set

    fun begin(event: UiEvent, scrollX: Float, scrollY: Float, center: Float) {
        this.scrollX = scrollX
        this.scrollY = scrollY
        this.center = center
        pressX = event.x
        pressY = event.y
        pressLocalX = event.localX
        moved = false
    }

    fun advance(event: UiEvent): Boolean {
        if (abs(event.dragTotalX) > MinDragUnit || abs(event.dragTotalY) > MinDragUnit) moved = true
        return moved
    }
}

internal data class TimelineMarquee(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val additive: Boolean,
) {
    val x: Float get() = min(fromX, toX)
    val y: Float get() = min(fromY, toY)
    val width: Float get() = abs(toX - fromX)
    val height: Float get() = abs(toY - fromY)

    val isDrag: Boolean get() = width > MinDragUnit || height > MinDragUnit

    fun contains(pointX: Float, pointY: Float): Boolean =
        pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height
}

@Composable
internal fun MarqueeOverlay(marquee: TimelineMarquee?) {
    if (marquee == null || !marquee.isDrag) return
    Box(
        modifier = Modifier.position(marquee.x.px, marquee.y.px)
            .size(max(marquee.width, 1f).px, max(marquee.height, 1f).px)
            .background(TimelineColors.Accent.withAlpha(0.14f)).border(1.px, TimelineColors.Accent.withAlpha(0.7f))
            .inputTransparent(),
    )
}

internal fun UiEvent.isAdditiveSelection(): Boolean = modifiers and (GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) != 0
