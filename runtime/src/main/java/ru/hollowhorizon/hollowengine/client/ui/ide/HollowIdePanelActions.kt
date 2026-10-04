package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

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
) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOfNotNull("panel-action", "active".takeIf { active }),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).tooltipOnHover(tooltip)
            .onClick { event ->
                onClick()
                event.consume()
            },
    ) {
        Image(icon, tags = listOf("panel-action-icon"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
    }
}
