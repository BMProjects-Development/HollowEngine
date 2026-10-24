package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

internal class TimelineTrackClicks {
    private var previous: Long? = null

    fun click(now: Long): Boolean {
        val double = previous?.let { now - it in 0..DoubleClickMillis } == true
        previous = if (double) null else now
        return double
    }

    private companion object {
        const val DoubleClickMillis = 300L
    }
}
