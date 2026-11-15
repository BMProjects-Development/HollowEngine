package ru.hollowhorizon.hollowengine.client.ui.graph

/** Tracks a held graph element while the view moves independently of pointer events. */
internal class GraphDrag {
    var startX = 0f
    var startY = 0f
    private var x = 0f
    private var y = 0f
    private var action: () -> Unit = {}
    private var lastPanX = Float.NaN
    private var lastPanY = Float.NaN
    private var lastZoom = Float.NaN

    fun update(x: Float, y: Float, action: () -> Unit) {
        this.x = x
        this.y = y
        this.action = action
    }

    fun apply() = action()

    fun advance(view: GraphViewState, width: Float, height: Float, dt: Float) {
        if (width <= 0f || height <= 0f) return
        fun speed(position: Float, size: Float): Float {
            val margin = minOf(EDGE_MARGIN, size / 2f)
            return when {
                position < margin -> ((margin - position) / margin).coerceAtMost(1f)
                position > size - margin -> -((position - (size - margin)) / margin).coerceAtMost(1f)
                else -> 0f
            } * PAN_SPEED
        }
        view.panBy(speed(x, width) * dt, speed(y, height) * dt)
        if (view.panX != lastPanX || view.panY != lastPanY || view.zoom != lastZoom) {
            lastPanX = view.panX
            lastPanY = view.panY
            lastZoom = view.zoom
            apply()
        }
    }

    private companion object {
        const val EDGE_MARGIN = 48f
        const val PAN_SPEED = 600f
    }
}
