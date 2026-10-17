package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyAction
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys

/**
 * An arrow that moves the playhead, held down: a tap steps it one frame, and holding the arrow on plays the timeline
 * that way at its own speed until the key comes up, when the playhead lands on a frame.
 */
class TimelinePlayheadHold {
    private var action: HollowKeyAction? = null
    private var direction = 0f
    private var held = 0f
    private var last = 0L

    /** Watches the key just pressed, if it is an arrow that moved the playhead rather than keys. */
    fun press(timeline: TimelineController, key: Int, modifiers: Int) {
        if (timeline.isPlaying || timeline.selectedKeyframes.isNotEmpty()) return
        val (pressed, sign) = Arrows.firstOrNull { (arrow, _) -> arrow.matches(key, modifiers) } ?: return
        action = pressed
        direction = sign
        held = 0f
        last = 0L
    }

    /** Plays the playhead on while the arrow stays down; true when it moved. */
    fun advance(timeline: TimelineController, now: Long): Boolean {
        val action = action ?: return false
        val step = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        if (!action.isHeld()) {
            this.action = null
            if (held < HoldDelay) return false
            timeline.applyCurrentTime(TimelineSnap.snap(timeline.currentTime))
            return true
        }
        held += step
        if (held < HoldDelay) return false
        timeline.applyCurrentTime(timeline.currentTime + direction * step)
        return true
    }

    private companion object {
        /** How long an arrow is down before it plays rather than steps. */
        const val HoldDelay = 0.3f

        val Arrows = listOf(
            HollowKeys.NUDGE_LEFT to -1f,
            HollowKeys.NUDGE_RIGHT to 1f,
            HollowKeys.NUDGE_LEFT_FAR to -1f,
            HollowKeys.NUDGE_RIGHT_FAR to 1f,
        )
    }
}
