package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Pan and zoom of one graph: a point of the graph lands on the canvas at `point * zoom + pan`.
 *
 * Zoom follows a spring toward where the wheel asked it to be, anchored at the point under the cursor,
 * so that point stays put while the rest grows around it. It runs off [advance], once a frame.
 *
 * A pan grabs the graph point under the pointer and keeps it there; that point becomes the anchor, so
 * a zoom still easing in keeps easing around the hand rather than fighting it.
 */
class GraphViewState(
    private val homeX: Float = 60f,
    private val homeY: Float = 60f,
    private val minZoom: Float = 0.3f,
    private val maxZoom: Float = 2.5f,
) {
    var panX by mutableStateOf(homeX)
    var panY by mutableStateOf(homeY)
    var zoom by mutableStateOf(1f)
        private set

    private var targetZoom = 1f
    private var velocity = 0f
    private var anchorCanvasX = 0f
    private var anchorCanvasY = 0f
    private var anchorGraphX = 0f
    private var anchorGraphY = 0f
    private var anchored = false
    private var lastFrame = 0L

    fun toCanvasX(x: Float) = x * zoom + panX
    fun toCanvasY(y: Float) = y * zoom + panY
    fun toGraphX(x: Float) = (x - panX) / zoom
    fun toGraphY(y: Float) = (y - panY) / zoom

    /** Whether any of [rect] comes within [margin] of a canvas of the size [width] by [height]. */
    fun sees(rect: GraphRect, width: Float, height: Float, margin: Float): Boolean {
        val left = toCanvasX(rect.x)
        val top = toCanvasY(rect.y)
        return left + rect.width * zoom > -margin && left < width + margin &&
                top + rect.height * zoom > -margin && top < height + margin
    }

    /** Zooms by [step] times, keeping the graph point under ([aroundX], [aroundY]) of the canvas in place. */
    fun zoomBy(step: Float, aroundX: Float, aroundY: Float) {
        anchorCanvasX = aroundX
        anchorCanvasY = aroundY
        anchorGraphX = toGraphX(aroundX)
        anchorGraphY = toGraphY(aroundY)
        anchored = true
        targetZoom = (targetZoom * step).coerceIn(minZoom, maxZoom)
    }

    /** Takes hold of the graph point under ([canvasX], [canvasY]); [dragTo] then moves it. */
    fun grab(canvasX: Float, canvasY: Float) {
        anchorGraphX = toGraphX(canvasX)
        anchorGraphY = toGraphY(canvasY)
        anchorCanvasX = canvasX
        anchorCanvasY = canvasY
        anchored = true
    }

    /** Puts the grabbed point under ([canvasX], [canvasY]) at whatever zoom the view is at right now. */
    fun dragTo(canvasX: Float, canvasY: Float) {
        anchorCanvasX = canvasX
        anchorCanvasY = canvasY
        reanchor()
    }

    /** Back to where a new view starts. */
    fun reset() {
        targetZoom = 1f
        velocity = 0f
        anchored = false
        panX = homeX
        panY = homeY
        zoom = 1f
    }

    /** Puts the graph point ([x], [y]) in the middle of a canvas [width] by [height]. */
    fun centerOn(x: Float, y: Float, width: Float, height: Float) {
        anchored = false
        panX = width / 2f - x * zoom
        panY = height / 2f - y * zoom
    }

    /** One step of the spring; returns whether anything is still moving. */
    fun advance(frameNanos: Long): Boolean {
        val previous = lastFrame
        lastFrame = frameNanos
        if (previous == 0L) return false

        val delta = targetZoom - zoom
        if (abs(delta) < 0.0005f && abs(velocity) < 0.0005f) {
            if (zoom != targetZoom) {
                zoom = targetZoom
                reanchor()
            }
            velocity = 0f
            return false
        }

        val dt = ((frameNanos - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
        velocity += (delta * STIFFNESS - velocity * DAMPING) * dt
        zoom += velocity * dt
        reanchor()
        return true
    }

    private fun reanchor() {
        if (!anchored) return
        panX = anchorCanvasX - anchorGraphX * zoom
        panY = anchorCanvasY - anchorGraphY * zoom
    }

    private companion object {
        const val STIFFNESS = 260f
        const val DAMPING = 30f
    }
}
