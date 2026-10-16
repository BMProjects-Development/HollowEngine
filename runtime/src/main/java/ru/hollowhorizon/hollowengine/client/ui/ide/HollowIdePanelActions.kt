package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton

@Composable
internal fun PanelActions(content: HollowUiContent) {
    Row(tags = listOf("panel-actions"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER), content = content)
}

@Composable
internal fun PanelActionButton(
    id: String,
    icon: String,
    tooltip: String,
    active: Boolean = false,
    onClick: () -> Unit,
) = IconButton(icon, tooltip, id = id, active = active, tags = listOf("panel-action"), onClick = onClick)
