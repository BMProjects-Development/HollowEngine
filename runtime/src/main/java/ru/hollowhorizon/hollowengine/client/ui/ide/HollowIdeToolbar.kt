package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.editor.TransformGizmoEditor
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.alignItems
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.utils.lang

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
                tooltip = "hollowengine.gui.ide.gizmo".lang + ": " + button.label,
                active = TransformGizmoEditor.isModeShown(button.mode),
            ) {
                TransformGizmoEditor.toggleMode(button.mode)
            }
        }
        GizmoOrientationButton(id = "ide-gizmo-orientation", tags = listOf("ide-toolbar-button"))
    }
}

@Composable
internal fun ToolbarIconButton(
    id: String,
    icon: String,
    tooltip: String,
    active: Boolean = false,
    onClick: () -> Unit,
) = IconButton(icon, tooltip, id = id, active = active, tags = listOf("ide-toolbar-button"), onClick = onClick)
