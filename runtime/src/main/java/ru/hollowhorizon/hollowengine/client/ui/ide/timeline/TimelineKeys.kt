package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.history.UndoKeys
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyAction
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys

/**
 * The keyboard of the timeline window, whatever it is showing.
 */
object TimelineKeys {
    private const val NUDGE_SMALL = 0.05f
    private const val NUDGE_LARGE = 0.25f
    private const val FRAMES_FAR = 10

    /** One frame while times snap to frames, otherwise a twentieth of a second. */
    private val nudge: Float get() = TimelineSnap.frame ?: NUDGE_SMALL

    /** Ten frames while times snap to frames, otherwise a quarter of a second. */
    private val nudgeFar: Float get() = TimelineSnap.frame?.let { it * FRAMES_FAR } ?: NUDGE_LARGE

    /** What each action does to the timeline; false when it had nothing to act on, so the key goes on. */
    private val commands: List<Pair<HollowKeyAction, (TimelineController) -> Boolean>> = listOf(
        HollowKeys.TOGGLE_CURVES to { timeline ->
            if (timeline.viewMode == TimelineViewMode.CURVES) timeline.viewMode = TimelineViewMode.DOPE_SHEET
            else timeline.enterCurveView()
            true
        },
        HollowKeys.FRAME to { timeline ->
            if (timeline.viewMode == TimelineViewMode.CURVES) timeline.frameCurves()
            timeline.requestFrameTime()
            true
        },
        HollowKeys.NEXT_KEY to { it.jumpToKey(1) },
        HollowKeys.PREVIOUS_KEY to { it.jumpToKey(-1) },
        HollowKeys.SMOOTH_KEYS to { it.smoothSelectedKeyframes().let { true } },
        HollowKeys.DELETE to { it.deleteSelectedKeyframes().let { true } },
        HollowKeys.DUPLICATE to { it.duplicateSelectedKeyframes().let { true } },
        HollowKeys.COPY to { it.copySelectedKeyframes().let { true } },
        HollowKeys.CUT to { it.cutSelectedKeyframes().let { true } },
        HollowKeys.PASTE to { it.pasteKeyframes().let { true } },
        HollowKeys.NUDGE_LEFT to { moveSelectionOrPlayhead(it, -nudge) },
        HollowKeys.NUDGE_RIGHT to { moveSelectionOrPlayhead(it, nudge) },
        HollowKeys.NUDGE_LEFT_FAR to { moveSelectionOrPlayhead(it, -nudgeFar) },
        HollowKeys.NUDGE_RIGHT_FAR to { moveSelectionOrPlayhead(it, nudgeFar) },
        HollowKeys.TO_START to { it.applyCurrentTime(0f).let { true } },
        HollowKeys.CENTER_PLAYHEAD to { it.requestCenterPlayhead().let { true } },
    )

    fun handle(timeline: TimelineController, key: Int, modifiers: Int): Boolean {
        if (UndoKeys.handle(timeline.history, key, modifiers) || handlePlayback(timeline, key, modifiers)) return true
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            timeline.clearSelection()
            return true
        }
        return commands.any { (action, command) -> action.matches(key, modifiers) && command(timeline) }
    }

    /**
     * The keys an editor whose timeline is in the window takes for it while the focus is on the editor, so playing
     * does not need a click on the timeline first.
     */
    fun handlePlayback(timeline: TimelineController, key: Int, modifiers: Int): Boolean {
        if (!HollowKeys.PLAY.matches(key, modifiers)) return false
        timeline.togglePlayback()
        return true
    }

    private fun moveSelectionOrPlayhead(timeline: TimelineController, deltaSeconds: Float): Boolean {
        if (timeline.selectedKeyframes.isEmpty()) {
            timeline.applyCurrentTime(timeline.currentTime + deltaSeconds)
        } else {
            timeline.nudgeSelectedKeyframes(deltaSeconds)
        }
        return true
    }
}
