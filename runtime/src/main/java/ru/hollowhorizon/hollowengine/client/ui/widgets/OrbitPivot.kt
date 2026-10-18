package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * What every preview of the editor turns around: its own center, or what is selected in it. One setting for all of
 * them, kept in the config.
 */
object OrbitPivot {
    private var loaded = false
    private var selectionValue by mutableStateOf(false)

    var aroundSelection: Boolean
        get() {
            load()
            return selectionValue
        }
        set(value) {
            load()
            if (selectionValue == value) return
            selectionValue = value
            HollowEngineConfig.previewOrbitSelection = value
        }

    /** Where [camera] turns about now: the selection when the previews circle it and there is one, else null. */
    fun of(camera: OrbitCamera): Vec3f? =
        if (aroundSelection) camera.selectionCenter else null

    private fun load() {
        if (loaded) return
        loaded = true
        selectionValue = HollowEngineConfig.previewOrbitSelection
    }
}
