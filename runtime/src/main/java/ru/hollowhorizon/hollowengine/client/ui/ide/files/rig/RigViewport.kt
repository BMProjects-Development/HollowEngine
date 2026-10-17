package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoSettings
import ru.hollowhorizon.hollowengine.client.history.UndoKeys
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigPreview
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.GizmoPointerWrap
import ru.hollowhorizon.hollowengine.client.ui.widgets.Model
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.ui.widgets.ViewAxes
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** The preview of the rig, and how the pointer and the keyboard edit it. */
@Composable
internal fun RigViewport(
    document: RigEditing,
    state: RigEditorState,
    preview: RigPreview?,
    onKey: (UiKeyInput) -> Boolean = { false },
) {
    val viewer = state.viewer
    Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
        Model(
            state = viewer,
            id = "rig-viewport",
            tags = listOf("ide-file-viewport"),
            modifier = Modifier.size(100.percent, 100.percent).clip().input(hoverable = true, clickable = true).focus()
                .onPlaced { state.viewportBounds = it }
                .onKeyInput { input -> if (onKey(input) || handleKey(document, state, input)) input.consume() }
                .onPress { event ->
                    state.pointer(event.localX, event.localY)
                    state.swallowClick = state.gizmo.capture(viewer) && state.gizmo.session.press(
                        state.gizmoTarget(),
                        event.localX,
                        event.localY,
                        event.button
                    )
                    if (state.swallowClick) event.consume()
                }.onRelease { state.gizmo.session.release() }.onHover { event ->
                    state.pointer(event.localX, event.localY)
                    if (!state.gizmo.capture(viewer)) return@onHover
                    val session = state.gizmo.session
                    if (!session.moveTo(event.localX, event.localY, event.modifiers)) {
                        session.hover(state.gizmoTarget(), event.localX, event.localY)
                    }
                }.onClick { event ->
                    if (state.swallowClick || event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onClick
                    pick(document, state, event.localX, event.localY)
                    event.consume()
                },
            onDrag = { event ->
                state.pointer(event.localX, event.localY)
                val physics = preview
                when {
                    state.gizmo.capture(viewer) && state.gizmo.session.dragTo(
                        event.localX,
                        event.localY,
                        event.modifiers
                    ) -> true

                    physics != null && event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                        physics.push(Vec3f(-event.deltaX, -event.deltaY, 0f) * PUSH_STRENGTH)
                        true
                    }

                    else -> false
                }
            },
        )
        val session = state.gizmo.session
        GizmoPointerWrap(session, state.viewportBounds, state.pointerX, state.pointerY)
        Box(
            modifier = Modifier.size(100.percent, 100.percent).inputTransparent().drawBehind(
                key = listOf(
                    state.frame, document.rig, state.partSelection, GizmoSettings.modes, GizmoSettings.orientation,
                    session.hovered, session.drag, session.keyboard,
                ),
            ) {
                if (state.gizmo.capture(viewer)) session.draw(this, state.gizmoTarget())
            },
        )
        session.hint?.let { hint ->
            Text(
                hint,
                tags = listOf("rig-transform-hint"),
                modifier = Modifier.position(8.px, 32.px).inputTransparent()
            )
        }
        ViewAxes(state.cameraTurn, Modifier.align(UiAlign.END, UiAlign.START).margin(8.px))
    }
}

/** Undo and redo, the numpad's views, then the gizmo's keys: a transform, a reset, the axes of one under way. */
private fun handleKey(document: RigEditing, state: RigEditorState, input: UiKeyInput): Boolean {
    if (input.repeat) return false
    val session = state.gizmo.session
    if (!session.isBusy && UndoKeys.handle(document.history, input.key, input.modifiers)) return true
    if (!session.isBusy && state.cameraTurn.handleKey(input.key, input.modifiers)) return true
    if (!state.gizmo.capture(state.viewer)) return false
    return session.key(state.gizmoTarget(), input.key, input.modifiers, state.pointerX, state.pointerY)
}

/** An IK target under the pointer wins, then a bone; a collider is picked only where there is neither. */
private fun pick(document: RigEditing, state: RigEditorState, x: Float, y: Float) {
    val viewer = state.viewer
    if (state.showSkeleton && state.gizmo.capture(viewer)) {
        state.gizmo.ikTargetAt(previewIkTargets(document.rig, viewer.nodes), x, y)?.let { target ->
            state.select(target.bone, target.id)
            return
        }
    }
    val bones = if (state.showSkeleton) viewer.bonesAt(x, y) else emptyList()
    if (bones.isNotEmpty()) {
        val index = bones.indexOfFirst { it.name == state.selected }
        state.select(bones[(index + 1) % bones.size].name)
        return
    }

    // A click on nothing lets go of what was picked, back to the model itself.
    if (!state.showColliders || !state.gizmo.capture(viewer)) return state.select(null)
    val (start, end) = state.gizmo.ray(x, y) ?: return state.select(null)
    val hits = previewColliders(document.rig, viewer.nodes).mapNotNull { collider ->
            collider.volume.clip(start, end)?.let { collider to it.distanceToSqr(start) }
        }.sortedBy { it.second }.map { it.first }
    if (hits.isEmpty()) return state.select(null)

    // Clicking the same spot again goes through to the collider behind the selected one.
    val index = hits.indexOfFirst { it.bone == state.selected && it.name == state.selectedPart }
    val next = hits[(index + 1) % hits.size]
    state.select(next.bone, next.name)
}

private const val PUSH_STRENGTH = 0.05f
