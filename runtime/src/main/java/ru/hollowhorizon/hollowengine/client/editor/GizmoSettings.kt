package ru.hollowhorizon.hollowengine.client.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig

enum class GizmoEditMode {
    TRANSLATE, ROTATE, SCALE,
}

/**
 * Which axes handles follow: world's, object's own, or camera's (X to the right of view, Y up it,
 * Z toward the viewer).
 */
enum class GizmoOrientation {
    GLOBAL, LOCAL, VIEW;

    /** The one an axis key switches to when pressed again: local from anything else, global from local. */
    val alternate: GizmoOrientation get() = if (this == LOCAL) GLOBAL else LOCAL
}

/**
 * How every gizmo of editor is set, world's and previews' alike: which manipulators show and which axes
 * they follow. Kept in the config, so  editor opens the way it was left.
 */
object GizmoSettings {
    private var loaded = false
    private var modesValue by mutableStateOf(setOf(GizmoEditMode.TRANSLATE))
    private var orientationValue by mutableStateOf(GizmoOrientation.GLOBAL)

    var modes: Set<GizmoEditMode>
        get() {
            load()
            return modesValue
        }
        set(value) {
            load()
            if (modesValue == value) return
            modesValue = value
            HollowEngineConfig.gizmoModes = GizmoEditMode.entries.filter(value::contains).joinToString(",") { it.name }
        }

    var orientation: GizmoOrientation
        get() {
            load()
            return orientationValue
        }
        set(value) {
            load()
            if (orientationValue == value) return
            orientationValue = value
            HollowEngineConfig.gizmoOrientation = value.name
        }

    fun toggle(mode: GizmoEditMode) {
        modes = if (mode in modes) modes - mode else modes + mode
    }

    fun nextOrientation() {
        orientation = GizmoOrientation.entries[(orientation.ordinal + 1) % GizmoOrientation.entries.size]
    }

    private fun load() {
        if (loaded) return
        loaded = true
        modesValue = HollowEngineConfig.gizmoModes.split(',')
            .mapNotNull { name -> GizmoEditMode.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
            .toSet()
        orientationValue = GizmoOrientation.entries.firstOrNull {
            it.name.equals(
                HollowEngineConfig.gizmoOrientation,
                ignoreCase = true
            )
        } ?: GizmoOrientation.GLOBAL
    }
}
