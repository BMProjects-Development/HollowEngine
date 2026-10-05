package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoHandle
import ru.hollowhorizon.hollowengine.client.editor.GizmoKeyResult
import ru.hollowhorizon.hollowengine.client.editor.GizmoKeyboardTransform
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigPreview
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeRigDocument
import ru.hollowhorizon.hollowengine.client.ui.widgets.Model
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.client.history.UndoKeys

/**
 * The preview of the rig, and how the pointer and the keyboard edit it.
 */
@Composable
internal fun RigViewport(document: HollowIdeRigDocument, state: RigEditorState, preview: RigPreview?) {
    val viewer = state.viewer
    Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
        Model(
            state = viewer,
            id = "rig-viewport",
            tags = listOf("ide-file-viewport"),
            modifier = Modifier.size(100.percent, 100.percent).clip().input(hoverable = true, clickable = true).focus()
                .onKeyInput { input -> if (handleKey(document, state, input)) input.consume() }
                .onPress { event ->
                    state.pointer(event.localX, event.localY)
                    state.swallowClick = state.transform?.let { finishTransform(document, state, it.keyboard.click(event.button)) } == true ||
                        event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT && beginHandleDrag(document, state, event.localX, event.localY)
                    if (state.swallowClick) event.consume()
                }
                .onRelease {
                    if (state.handleDrag != null) document.endGesture()
                    state.endHandleDrag()
                }
                .onHover { event ->
                    state.pointer(event.localX, event.localY)
                    if (state.transform != null) moveTransform(document, state, event.modifiers)
                    else state.hoveredHandle = handleAt(document, state, event.localX, event.localY)?.id
                }
                .onClick { event ->
                    if (state.swallowClick || event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onClick
                    pick(document, state, event.localX, event.localY)
                    event.consume()
                },
            onDrag = { event ->
                state.pointer(event.localX, event.localY)
                val physics = preview
                when {
                    state.handleDrag != null -> {
                        dragHandle(document, state, event.localX, event.localY, event.modifiers)
                        true
                    }

                    physics != null && event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                        physics.push(Vec3f(-event.deltaX, -event.deltaY, 0f) * PUSH_STRENGTH)
                        true
                    }

                    else -> false
                }
            },
        )
        Box(
            modifier = Modifier.size(100.percent, 100.percent).inputTransparent()
                .drawBehind(key = listOf(state.frame, document.revision, state.colliderSelection, state.gizmoMode, state.hoveredHandle)) {
                    drawGizmo(document, state)
                },
        )
        state.transform?.let { transform ->
            Text(
                transform.keyboard.hint,
                tags = listOf("rig-transform-hint"),
                modifier = Modifier.position(8.px, 32.px).inputTransparent(),
            )
        }
    }
}

private fun UiCanvasDrawScope.drawGizmo(document: HollowIdeRigDocument, state: RigEditorState) {
    state.transform?.let { transform ->
        transform.keyboard.draw(this)
        return
    }
    val frame = state.selectedFrame() ?: return
    if (!state.gizmo.capture(state.viewer)) return
    state.gizmo.draw(this, state.gizmo.handles(frame, state.gizmoMode), state.hoveredHandle, state.handleDrag?.drag)
}

private fun handleAt(document: HollowIdeRigDocument, state: RigEditorState, x: Float, y: Float): GizmoHandle? {
    val frame = state.selectedFrame() ?: return null
    if (!state.gizmo.capture(state.viewer)) return null
    return state.gizmo.pick(state.gizmo.handles(frame, state.gizmoMode), x, y)
}

private fun beginHandleDrag(document: HollowIdeRigDocument, state: RigEditorState, x: Float, y: Float): Boolean {
    val selection = state.colliderSelection ?: return false
    val frame = state.selectedFrame() ?: return false
    val handle = handleAt(document, state, x, y) ?: return false
    state.handleDrag = RigHandleDrag(selection, frame, state.gizmo.begin(handle, frame, x, y))
    document.beginGesture()
    return true
}

private fun dragHandle(document: HollowIdeRigDocument, state: RigEditorState, x: Float, y: Float, modifiers: Int) {
    val drag = state.handleDrag ?: return
    state.gizmo.drag(drag.drag, drag.frame, x, y, modifiers)?.let { document.placeCollider(drag.selection, it) }
}

private fun handleKey(document: HollowIdeRigDocument, state: RigEditorState, input: UiKeyInput): Boolean {
    if (input.repeat) return false
    val transform = state.transform
    if (transform != null) {
        val result = transform.keyboard.key(input.key)
        if (result == GizmoKeyResult.CHANGED) {
            document.placeCollider(transform.selection, transform.frame.spec)
            moveTransform(document, state, input.modifiers)
        }
        return finishTransform(document, state, result) || result == GizmoKeyResult.CHANGED
    }

    if (input.command) return UndoKeys.handle(document.history, input.key, input.modifiers)
    if (input.alt) return false

    val mode = GizmoKeyboardTransform.modeFor(input.key) ?: return false
    state.gizmoMode = mode
    val selection = state.colliderSelection ?: return true
    val frame = state.selectedFrame() ?: return true
    if (mode == GizmoEditMode.ROTATE && frame.isWorldAligned) return true
    if (!state.gizmo.capture(state.viewer)) return true

    document.beginGesture()
    state.transform = ColliderTransform(selection, frame, state.gizmo.keyboard(mode, frame, state.pointerX, state.pointerY))
    return true
}

/** Ends the transform under way when [result] says so; true when it did. */
private fun finishTransform(document: HollowIdeRigDocument, state: RigEditorState, result: GizmoKeyResult): Boolean {
    val transform = state.transform ?: return false
    when (result) {
        GizmoKeyResult.CONFIRMED -> Unit
        GizmoKeyResult.CANCELLED -> document.placeCollider(transform.selection, transform.frame.spec)
        else -> return false
    }
    state.transform = null
    document.endGesture()
    return true
}

private fun moveTransform(document: HollowIdeRigDocument, state: RigEditorState, modifiers: Int) {
    val transform = state.transform ?: return
    transform.keyboard.update(state.pointerX, state.pointerY, modifiers)
        ?.let { document.placeCollider(transform.selection, transform.frame.with(it)) }
}

/** A bone under the pointer wins; a collider is picked only where there is no bone. */
private fun pick(document: HollowIdeRigDocument, state: RigEditorState, x: Float, y: Float) {
    val viewer = state.viewer
    val bones = if (state.showSkeleton) viewer.bonesAt(x, y) else emptyList()
    if (bones.isNotEmpty()) {
        val index = bones.indexOfFirst { it.name == state.selected }
        state.select(bones[(index + 1) % bones.size].name)
        return
    }

    if (!state.showColliders || !state.gizmo.capture(viewer)) return
    val (start, end) = state.gizmo.ray(x, y) ?: return
    val hits = previewColliders(document.rig, viewer.nodes)
        .mapNotNull { collider -> collider.box.clip(start, end)?.let { collider to it.distanceToSqr(start) } }
        .sortedBy { it.second }
        .map { it.first }
    if (hits.isEmpty()) return

    // Clicking the same spot again goes through to the collider behind the selected one.
    val index = hits.indexOfFirst { it.bone == state.selected && it.name == state.selectedCollider }
    val next = hits[(index + 1) % hits.size]
    state.select(next.bone, next.name)
}

/** Writes [spec] over the collider [selection] names, wherever it hangs. */
internal fun HollowIdeRigDocument.placeCollider(selection: ColliderSelection, spec: ColliderAttachmentSpec) = edit { rig ->
    rig.withHolder(selection.bone, rig.holder(selection.bone).withAttachment(selection.id, spec))
}

private const val PUSH_STRENGTH = 0.05f
