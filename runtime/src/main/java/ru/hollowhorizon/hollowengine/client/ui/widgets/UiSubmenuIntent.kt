package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect

/**
 * Tells whether the pointer is on its way to the open submenu. Between where it last was and the near edge of the
 * submenu lies a triangle: while the pointer moves inside it the items it crosses do not take the submenu over.
 */
internal class UiSubmenuIntent {
    /** Where the open submenu lies on the screen, once it has been placed. */
    var submenu: UiRect? = null

    private var lastX = Float.NaN
    private var lastY = Float.NaN

    /** Remembers where the pointer is, as the tip of the next triangle. */
    fun track(x: Float, y: Float) {
        lastX = x
        lastY = y
    }

    /** Whether the pointer, now at [x] and [y], heads for the submenu. The new point becomes the tip either way. */
    fun aims(x: Float, y: Float): Boolean {
        val rect = submenu
        val fromX = lastX
        val fromY = lastY
        track(x, y)
        if (rect == null || fromX.isNaN()) return false
        val edge = if (rect.x >= fromX) rect.x else rect.x + rect.width
        return inside(x, y, fromX, fromY, edge, rect.y - GAP, edge, rect.y + rect.height + GAP)
    }

    fun reset() {
        submenu = null
        lastX = Float.NaN
        lastY = Float.NaN
    }

    private fun inside(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val first = side(px, py, ax, ay, bx, by)
        val second = side(px, py, bx, by, cx, cy)
        val third = side(px, py, cx, cy, ax, ay)
        val negative = first < 0f || second < 0f || third < 0f
        val positive = first > 0f || second > 0f || third > 0f
        return !(negative && positive)
    }

    private fun side(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        (px - bx) * (ay - by) - (ax - bx) * (py - by)

    private companion object {
        const val GAP = 8f
    }
}
