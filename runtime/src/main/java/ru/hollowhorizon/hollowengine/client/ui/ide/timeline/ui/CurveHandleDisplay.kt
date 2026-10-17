package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig

/** Whether the curve graph shows the handles of every key or only of the picked ones; the user's choice, kept. */
internal object CurveHandleDisplay {
    private var loaded = false
    private var allValue by mutableStateOf(true)

    var all: Boolean
        get() {
            load()
            return allValue
        }
        set(value) {
            load()
            allValue = value
            HollowEngineConfig.timelineCurveHandles = value
        }

    private fun load() {
        if (loaded) return
        loaded = true
        allValue = HollowEngineConfig.timelineCurveHandles
    }
}
