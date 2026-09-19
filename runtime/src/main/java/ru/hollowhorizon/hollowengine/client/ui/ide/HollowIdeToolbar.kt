package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.TransformGizmoEditor
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.UiBoxMode
import ru.hollowhorizon.hollowengine.client.ui.UiCursorShape
import ru.hollowhorizon.hollowengine.client.ui.align
import ru.hollowhorizon.hollowengine.client.ui.alignItems
import ru.hollowhorizon.hollowengine.client.ui.cursor
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.onClick
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang

private class GizmoModeButton(val mode: GizmoEditMode, val icon: String, val labelKey: String)

private val GizmoModeButtons = listOf(
    GizmoModeButton(GizmoEditMode.TRANSLATE, "hollowengine:textures/gui/icons/gizmo_translate.svg", "hollowengine.gui.ide.gizmo.translate"),
    GizmoModeButton(GizmoEditMode.ROTATE, "hollowengine:textures/gui/icons/gizmo_rotate.svg", "hollowengine.gui.ide.gizmo.rotate"),
    GizmoModeButton(GizmoEditMode.SCALE, "hollowengine:textures/gui/icons/gizmo_scale.svg", "hollowengine.gui.ide.gizmo.scale"),
)

@Composable
internal fun HollowIdeGizmoSwitcher() {
    Row(
        tags = listOf("ide-toolbar-group"),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER),
    ) {
        GizmoModeButtons.forEach { button ->
            ToolbarIconButton(
                id = "ide-gizmo-${button.mode.name.lowercase()}",
                icon = button.icon,
                tooltip = "hollowengine.gui.ide.gizmo".lang + ": " + button.labelKey.lang,
                active = TransformGizmoEditor.isModeShown(button.mode),
            ) {
                TransformGizmoEditor.toggleMode(button.mode)
            }
        }
    }
}

@Composable
internal fun ToolbarIconButton(
    id: String,
    icon: String,
    tooltip: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOfNotNull("ide-toolbar-button", "active".takeIf { active }),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .tooltipOnHover(tooltip)
            .onClick { event ->
                onClick()
                event.consume()
            },
    ) {
        Image(icon, tags = listOf("ide-toolbar-button-icon"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
    }
}
