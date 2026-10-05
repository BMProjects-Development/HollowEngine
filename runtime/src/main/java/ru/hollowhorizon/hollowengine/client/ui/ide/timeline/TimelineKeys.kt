package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.history.UndoKeys

/**
 * The keyboard of the timeline window, whatever it is showing.
 */
object TimelineKeys {
    private const val NUDGE_SMALL = 0.05f
    private const val NUDGE_LARGE = 0.25f

    fun handle(timeline: TimelineController, key: Int, modifiers: Int): Boolean {
        val ctrl = modifiers and GLFW.GLFW_MOD_CONTROL != 0
        val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
        return when {
            UndoKeys.handle(timeline.history, key, modifiers) -> true

            ctrl && key == GLFW.GLFW_KEY_TAB -> {
                if (timeline.viewMode == TimelineViewMode.CURVES) timeline.viewMode = TimelineViewMode.DOPE_SHEET
                else timeline.enterCurveView()
                true
            }

            key == GLFW.GLFW_KEY_F -> {
                if (timeline.viewMode == TimelineViewMode.CURVES) timeline.frameCurves()
                timeline.requestFrameTime()
                true
            }

            key == GLFW.GLFW_KEY_UP -> timeline.jumpToKey(1)

            key == GLFW.GLFW_KEY_DOWN -> timeline.jumpToKey(-1)

            key == GLFW.GLFW_KEY_S -> {
                timeline.smoothSelectedKeyframes()
                true
            }

            key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE -> {
                timeline.deleteSelectedKeyframes()
                true
            }

            ctrl && key == GLFW.GLFW_KEY_D -> {
                timeline.duplicateSelectedKeyframes()
                true
            }

            ctrl && key == GLFW.GLFW_KEY_C -> {
                timeline.copySelectedKeyframes()
                true
            }

            ctrl && key == GLFW.GLFW_KEY_X -> {
                timeline.cutSelectedKeyframes()
                true
            }

            ctrl && key == GLFW.GLFW_KEY_V -> {
                timeline.pasteKeyframes()
                true
            }

            key == GLFW.GLFW_KEY_LEFT -> {
                moveSelectionOrPlayhead(timeline, if (shift) -NUDGE_LARGE else -NUDGE_SMALL)
                true
            }

            key == GLFW.GLFW_KEY_RIGHT -> {
                moveSelectionOrPlayhead(timeline, if (shift) NUDGE_LARGE else NUDGE_SMALL)
                true
            }

            key == GLFW.GLFW_KEY_ESCAPE -> {
                timeline.clearSelection()
                true
            }

            key == GLFW.GLFW_KEY_HOME -> {
                timeline.applyCurrentTime(0f)
                true
            }

            key == GLFW.GLFW_KEY_SPACE -> {
                timeline.togglePlayback()
                true
            }

            else -> false
        }
    }

    private fun moveSelectionOrPlayhead(timeline: TimelineController, deltaSeconds: Float) {
        if (timeline.selectedKeyframes.isEmpty()) {
            timeline.applyCurrentTime(timeline.currentTime + deltaSeconds)
        } else {
            timeline.nudgeSelectedKeyframes(deltaSeconds)
        }
    }
}
