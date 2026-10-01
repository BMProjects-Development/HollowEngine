package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoKeyResult
import ru.hollowhorizon.hollowengine.client.editor.GizmoKeyboardTransform
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxTransform

/**
 * T, R and S on the selected node, and the keys of a transform under way. A part of the placement the
 * timeline drives cannot be moved unless the timeline records, the same as with the handles.
 */
internal fun handleTransformKeys(
    document: HollowIdeVfxDocument,
    state: VfxEditorState,
    driven: (VfxProperty) -> VfxDrivenValue?,
    input: UiKeyInput,
): Boolean {
    if (input.repeat) return false
    val transform = state.keyboard
    if (transform != null) {
        val result = transform.keyboard.key(input.key)
        if (result == GizmoKeyResult.CHANGED) {
            place(document, transform, transform.start)
            moveKeyboardTransform(document, state, input.modifiers)
        }
        return finishKeyboardTransform(document, state, result) || result == GizmoKeyResult.CHANGED
    }
    if (input.command || input.alt) return false

    val mode = GizmoKeyboardTransform.modeFor(input.key) ?: return false
    val nodeId = state.selected ?: return false
    val node = document.effect.node(nodeId) ?: return false
    val runtime = state.preview.instance?.node(nodeId) ?: return false
    if (driven(mode.property)?.recording == false) return true

    state.transformGizmo.capture(state.preview)
    state.beginGesture()
    state.keyboard = state.transformGizmo.keyboard(
        nodeId, mode, runtime.frame, runtime.parent?.frame, node.transform, state.pointerX, state.pointerY,
    )
    return true
}

/** A click while a transform runs ends it: the left button applies it, the right one puts the node back. */
internal fun clickDuringKeyboardTransform(document: HollowIdeVfxDocument, state: VfxEditorState, button: Int): Boolean {
    val transform = state.keyboard ?: return false
    return finishKeyboardTransform(document, state, transform.keyboard.click(button))
}

internal fun moveKeyboardTransform(document: HollowIdeVfxDocument, state: VfxEditorState, modifiers: Int) {
    val transform = state.keyboard ?: return
    state.transformGizmo.update(transform, state.pointerX, state.pointerY, modifiers)?.let { place(document, transform, it) }
}

private fun finishKeyboardTransform(document: HollowIdeVfxDocument, state: VfxEditorState, result: GizmoKeyResult): Boolean {
    val transform = state.keyboard ?: return false
    when (result) {
        GizmoKeyResult.CONFIRMED -> Unit
        GizmoKeyResult.CANCELLED -> place(document, transform, transform.start)
        else -> return false
    }
    state.keyboard = null
    state.endGesture()
    return true
}

private fun place(document: HollowIdeVfxDocument, transform: VfxKeyboardTransform, placed: VfxTransform) {
    val node = document.effect.node(transform.nodeId) ?: return
    document.edit { it.withNode(node.withCommon(transform = placed)) }
}

internal val GizmoEditMode.property: VfxProperty
    get() = when (this) {
        GizmoEditMode.TRANSLATE -> VfxProperty.POSITION
        GizmoEditMode.ROTATE -> VfxProperty.ROTATION
        GizmoEditMode.SCALE -> VfxProperty.SCALE
    }
