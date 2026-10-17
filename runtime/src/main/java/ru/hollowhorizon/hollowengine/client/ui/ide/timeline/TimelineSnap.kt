package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import kotlin.math.round

/**
 * Where times on every timeline land: on whole frames of [fps] while [enabled], both kept in the config as the user's
 * own choice. Holding [HollowKeys.FREE_MOVE] lets a drag go anywhere, and the playhead off the keys it sticks to.
 */
object TimelineSnap {
    const val MaxFps = 240

    private var loaded = false
    private var enabledValue by mutableStateOf(true)
    private var fpsValue by mutableStateOf(DefaultFps)

    var enabled: Boolean
        get() {
            load()
            return enabledValue
        }
        set(value) {
            load()
            enabledValue = value
            HollowEngineConfig.timelineSnap = value
        }

    /** Frames per second to snap to. */
    var fps: Int
        get() {
            load()
            return fpsValue
        }
        set(value) {
            load()
            fpsValue = value.coerceIn(1, MaxFps)
            HollowEngineConfig.timelineSnapFps = fpsValue
        }

    /** One frame in seconds, or null while nothing is snapped. */
    val frame: Float? get() = if (enabled) 1f / fps else null

    /** Whether snapping is let go of right now, by the free-move key held down. */
    val isFree: Boolean get() = HollowKeys.FREE_MOVE.isHeld()

    /** [time] on the nearest frame, unless nothing is snapped or the free-move key is held. */
    fun snap(time: Float): Float = if (!enabled || isFree) time else snap(time, fps)

    /** [time] on the nearest whole frame of [fps]; as it is for 0. */
    fun snap(time: Float, fps: Int): Float = if (fps <= 0) time else round(time * fps) / fps

    private fun load() {
        if (loaded) return
        loaded = true
        enabledValue = HollowEngineConfig.timelineSnap
        fpsValue = HollowEngineConfig.timelineSnapFps.coerceIn(1, MaxFps)
    }

    /** 20 is the game's own tick. */
    private const val DefaultFps = 20
}
