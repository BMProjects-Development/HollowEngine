package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect

@Composable
internal fun HollowIdeFileContextMenu(
    menu: FileContextMenu?,
    onAction: (HollowIdeFileAction) -> Unit,
    onDismiss: () -> Unit,
) {
    if (menu == null || menu.actions.isEmpty()) return
    Popup(
        anchorBounds = UiRect(menu.x, menu.y, 0f, 0f),
        alignment = UiPopupAlignment.Cursor,
        id = "file-context-menu",
        tags = listOf("dropdown-popup", "file-context-menu"),
        onDismiss = onDismiss,
    ) {
        menu.actions.forEach { entry ->
            if (entry.action.separatorBefore) {
                Box(tags = listOf("dropdown-separator", "file-context-menu-separator"))
            }
            FileMenuItem(entry) { onAction(entry.action) }
        }
    }
}

@Composable
private fun FileMenuItem(entry: FileContextMenuEntry, onClick: () -> Unit) {
    val action = entry.action
    Row(
        tags = buildList {
            add("dropdown-item")
            add("file-context-menu-item")
            if (!entry.enabled) add("disabled")
        },
        modifier = Modifier.input(hoverable = entry.enabled, clickable = entry.enabled)
            .cursor(if (entry.enabled) UiCursorShape.HAND else UiCursorShape.DEFAULT)
            .alignItems(vertical = UiAlign.CENTER)
            .onClick { event ->
                if (entry.enabled) onClick()
                event.consume()
            },
    ) {
        if (action.icon == null) {
            Box(tags = listOf("file-context-menu-icon"))
        } else {
            Image(action.icon, tags = listOf("dropdown-item-icon", "file-context-menu-icon"))
        }
        Text(action.label, tags = listOf("dropdown-item-label", "file-context-menu-label"))
        if (action.shortcutText.isNotBlank()) {
            Text(action.shortcutText, tags = listOf("dropdown-item-shortcut", "file-context-menu-shortcut"))
        }
    }
}

internal data class FileContextMenu(
    val path: String,
    val x: Float,
    val y: Float,
    val actions: List<FileContextMenuEntry>,
)

internal data class FileContextMenuEntry(
    val action: HollowIdeFileAction,
    val enabled: Boolean,
)
