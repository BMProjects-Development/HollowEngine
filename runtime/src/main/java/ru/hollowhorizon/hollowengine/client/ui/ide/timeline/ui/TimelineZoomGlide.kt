package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * The wheel's zoom of the time, played out over a few frames about the middle of the lanes, which stays put.
 */
internal class TimelineZoomGlide {
    private var target = 0f
    private var time = 0f
    private var anchor = 0f
    private var last = 0L

    /** Zooms [notches] further out (or in, for fewer than none) about the middle of lanes [width] pixels wide. */
    fun zoomBy(controller: TimelineController, scroll: UiScrollHandle, width: Float, notches: Float) {
        if (target <= 0f) {
            anchor = width * 0.5f
            time = (anchor + scroll.offsetX - TimelineLeftPadding) / controller.pixelsPerSecond
        }
        val from = if (target > 0f) target else controller.pixelsPerSecond
        target = (from * WheelZoomFactor.pow(-notches)).coerceIn(TimelineMinZoom, TimelineMaxZoom)
    }

    /** Moves the zoom on toward where it is going; true when it moved. */
    fun advance(now: Long, controller: TimelineController, scroll: UiScrollHandle): Boolean {
        val previous = last
        last = now
        if (target <= 0f) return false
        val step = if (previous == 0L) 1f / 60f else ((now - previous) / 1e9f).coerceIn(0f, 0.1f)
        val zoom = controller.pixelsPerSecond
        val next =
            if (abs(ln(target / zoom)) < SettledRatio) target else zoom * (target / zoom).pow(min(1f, step * GlideRate))
        if (next == target) target = 0f
        controller.pixelsPerSecond = next
        scroll.jumpTo(x = time * next + TimelineLeftPadding - anchor)
        return true
    }

    fun stop() {
        target = 0f
    }

    private companion object {
        /** How much one notch zooms. */
        const val WheelZoomFactor = 1.2f

        /** How fast the zoom closes on where it is going, as a share of the way a second. */
        const val GlideRate = 16f

        const val SettledRatio = 0.002f
    }
}
