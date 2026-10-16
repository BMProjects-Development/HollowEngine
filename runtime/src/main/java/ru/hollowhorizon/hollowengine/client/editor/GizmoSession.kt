package ru.hollowhorizon.hollowengine.client.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope

interface GizmoTarget {
    val values: GizmoTransformValues

    /** Whether [mode] may move it now: a part with no turn of its own, or a value the timeline drives, says no. */
    fun canEdit(mode: GizmoEditMode): Boolean = true

    /** Writes [values], of which only [mode]'s part has changed. */
    fun place(values: GizmoTransformValues, mode: GizmoEditMode)

    /** Back to how it was when this target was made. */
    fun restore()

    /** Whether it has a place to rest at, which Alt+G, R and S put it back to. */
    val canReset: Boolean get() = false

    /** Puts [mode]'s part where it rests; false when that part has no such place. */
    fun reset(mode: GizmoEditMode): Boolean = false

    /** A drag, a keyboard transform or a reset starts: everything until [endEdit] goes back in one step. */
    fun beginEdit()

    fun endEdit()
}

/**
 * One gizmo over one view, the world's or a preview's: the handles, a drag of one of them, a transform from the
 * keyboard and the resets.
 */
class GizmoSession(val projector: GizmoProjector, snapping: GizmoSnapping = GizmoSnapping.WORLD) {
    val geometry = GizmoGeometry(projector)
    val manipulator = GizmoManipulator(projector, snapping)

    /** The handle under the pointer, for the view to redraw by. */
    var hovered by mutableStateOf<GizmoHandleId?>(null)
        private set

    /** The handle being dragged, if one is. */
    var drag by mutableStateOf<GizmoDrag?>(null)
        private set

    /** The transform from the keyboard under way, if one is. */
    var keyboard by mutableStateOf<GizmoKeyboardTransform?>(null)
        private set

    private var held: GizmoTarget? = null

    /** Whether a drag or a keyboard transform holds the target. */
    val isBusy: Boolean get() = drag != null || keyboard != null

    /** What the keyboard transform does and which keys do what; null while there is none. */
    var hint by mutableStateOf<String?>(null)
        private set

    fun handles(target: GizmoTarget?): List<GizmoHandle> {
        if (target == null) return emptyList()
        val modes = GizmoSettings.modes.filterTo(HashSet(), target::canEdit)
        if (modes.isEmpty()) return emptyList()
        val values = target.values
        return geometry.buildHandles(values.translation, values.rotation, modes)
    }

    fun handleAt(target: GizmoTarget?, x: Float, y: Float): GizmoHandle? = GizmoPicker.pick(handles(target), x, y)

    fun hover(target: GizmoTarget?, x: Float, y: Float) {
        if (!isBusy) hovered = handleAt(target, x, y)?.id
    }

    fun clearHover() {
        hovered = null
    }

    /**
     * A button pressed at ([x], [y]). During a keyboard transform it ends it (the right button puts things back);
     * otherwise the left one grabs the handle under it. True when the press was the gizmo's.
     */
    fun press(target: GizmoTarget?, x: Float, y: Float, button: Int): Boolean {
        keyboard?.let { return finish(it.click(button)) }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || target == null) return false
        val handle = handleAt(target, x, y) ?: return false
        held = target
        target.beginEdit()
        drag = manipulator.begin(handle, target.values, x, y)
        return true
    }

    /** The dragged handle follows the pointer; false when nothing is dragged. */
    fun dragTo(x: Float, y: Float, modifiers: Int): Boolean {
        val drag = drag ?: return false
        val target = held ?: return false
        manipulator.update(drag, x, y, modifiers)?.let { target.place(it, drag.handleId.editMode) }
        return true
    }

    fun release(): Boolean {
        if (drag == null) return false
        drag = null
        held?.endEdit()
        held = null
        return true
    }

    /** The pointer moved while a keyboard transform runs; false when none does. */
    fun moveTo(x: Float, y: Float, modifiers: Int): Boolean {
        val transform = keyboard ?: return false
        transform.update(x, y, modifiers)?.let { held?.place(it, transform.mode) }
        return true
    }

    /**
     * A key with the pointer at ([x], [y]): the keys of a transform under way, or one that starts a transform or a
     * reset on [target]. True when the gizmo took it.
     */
    fun key(target: GizmoTarget?, key: Int, modifiers: Int, x: Float, y: Float): Boolean {
        keyboard?.let { transform ->
            val result = transform.key(key, modifiers)
            if (result == GizmoKeyResult.CHANGED) {
                held?.restore()
                moveTo(x, y, modifiers)
                hint = transform.hint
            }
            return finish(result) || result == GizmoKeyResult.CHANGED
        }
        if (drag != null) return false
        if (HollowKeys.ORIENTATION.matches(key, modifiers)) {
            GizmoSettings.nextOrientation()
            return true
        }
        if (target == null) return false
        resetMode(key, modifiers)?.let { mode -> return reset(target, mode) }
        val mode = GizmoKeyboardTransform.modeFor(key, modifiers) ?: return false
        if (!target.canEdit(mode)) return true
        held = target
        target.beginEdit()
        keyboard =
            GizmoKeyboardTransform(mode, target.values, x, y, projector, geometry, manipulator).also { hint = it.hint }
        return true
    }

    /** Ends whatever is under way where it got to; for when the view moves on to another target. */
    fun settle() {
        if (keyboard != null) finish(GizmoKeyResult.CONFIRMED)
        release()
        hovered = null
    }

    /** Ends whatever is under way, putting a keyboard transform's target back; for when the view loses it. */
    fun cancel() {
        if (keyboard != null) finish(GizmoKeyResult.CANCELLED)
        release()
        hovered = null
    }

    fun draw(scope: UiCanvasDrawScope, target: GizmoTarget?) {
        keyboard?.let { transform ->
            transform.draw(scope)
            return
        }
        val drag = drag
        drag?.let { GizmoRenderer.drawRotationSector(scope, geometry, projector, it) }
        GizmoRenderer.drawHandles(scope, handles(target), hovered, drag?.handleId)
    }

    /** Where the value of the drag is shown, next to the dragged object, and the value; null while nothing is dragged. */
    fun readout(): Triple<Float, Float, Double>? {
        val drag = drag ?: return null
        val screen = projector.project(drag.origin)?.takeIf { it.onScreen } ?: return null
        return Triple(screen.x, screen.y, drag.labelValue)
    }

    private fun reset(target: GizmoTarget, mode: GizmoEditMode): Boolean {
        if (!target.canReset) return false
        if (!target.canEdit(mode)) return true
        target.beginEdit()
        val done = target.reset(mode)
        target.endEdit()
        return done
    }

    private fun finish(result: GizmoKeyResult): Boolean {
        if (keyboard == null) return false
        when (result) {
            GizmoKeyResult.CONFIRMED -> Unit
            GizmoKeyResult.CANCELLED -> held?.restore()
            else -> return false
        }
        keyboard = null
        hint = null
        held?.endEdit()
        held = null
        return true
    }

    private fun resetMode(key: Int, modifiers: Int): GizmoEditMode? = when {
        HollowKeys.RESET_LOCATION.matches(key, modifiers) -> GizmoEditMode.TRANSLATE
        HollowKeys.RESET_ROTATION.matches(key, modifiers) -> GizmoEditMode.ROTATE
        HollowKeys.RESET_SCALE.matches(key, modifiers) -> GizmoEditMode.SCALE
        else -> null
    }
}
