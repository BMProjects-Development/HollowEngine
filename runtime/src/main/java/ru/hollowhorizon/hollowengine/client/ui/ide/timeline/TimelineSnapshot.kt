package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import ru.hollowhorizon.hollowengine.client.history.UndoLabel

internal data class KeyframeState(
    val time: Float,
    val value: Float,
    val interpolation: KeyInterpolation,
    val handleMode: HandleMode,
    val incoming: KeyTangent,
    val outgoing: KeyTangent,
) {
    fun toKeyframe() = Keyframe(time, value, interpolation, handleMode, incoming, outgoing)

    companion object {
        fun of(keyframe: Keyframe) = KeyframeState(
            time = keyframe.time,
            value = keyframe.value,
            interpolation = keyframe.interpolation,
            handleMode = keyframe.handleMode,
            incoming = keyframe.incoming,
            outgoing = keyframe.outgoing,
        )
    }
}

internal data class PropertyState(
    val name: String,
    val visible: Boolean,
    val locked: Boolean,
) {
    fun applyTo(property: AnimProperty<*>) {
        property.nameState = name
        property.isVisible = visible
        property.isLocked = locked
    }

    companion object {
        fun of(property: AnimProperty<*>) = PropertyState(
            name = property.nameState,
            visible = property.isVisible,
            locked = property.isLocked,
        )
    }
}

internal data class PropertySnapshot(
    val property: AnimProperty<*>,
    val type: PropertyType<*>,
    val state: PropertyState,
    val curves: List<ChannelCurve>,
    val keys: List<List<KeyframeState>>,
)

internal data class TimelineSnapshot(
    val properties: List<PropertySnapshot>,
    val currentTime: Float,
    val workAreaEnd: Float,
    val extra: Any? = null,
)


/** What timeline's steps are called in the history window. */
object TimelineEdits {
    private fun label(name: String) = UndoLabel("${UndoLabel.LANG}.timeline.$name")

    val ADD_KEY = label("add_key")
    val DELETE_KEYS = label("delete_keys")
    val MOVE_KEYS = label("move_keys")
    val CLONE_KEYS = label("clone_keys")
    val DUPLICATE_KEYS = label("duplicate_keys")
    val PASTE_KEYS = label("paste_keys")
    val NUDGE_KEYS = label("nudge_keys")
    val SMOOTH_KEYS = label("smooth_keys")
    val EDIT_VALUE = label("edit_value")
    val EDIT_HANDLES = label("edit_handles")
    val CURVE_PRESET = label("curve_preset")
    val ROTATION_BASIS = label("rotation_basis")
    val RECORD_KEYS = label("record_keys")
    val CAPTURE_KEY = label("capture_key")
    val MOVE_CUTSCENE = label("move_cutscene")
    val REANCHOR_CUTSCENE = label("reanchor_cutscene")
}
