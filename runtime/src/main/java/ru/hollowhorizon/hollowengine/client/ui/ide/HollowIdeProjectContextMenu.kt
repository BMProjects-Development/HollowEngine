package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.utils.IconHelper.Icons
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.COPY
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.CREATE_FILE
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.CREATE_FOLDER
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.CUT
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.FILE_SOUND
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.FOLDER
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.PASTE
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.REMOVE
import ru.hollowhorizon.hollowengine.generated.Assets.Hollowengine.Textures.Gui.Icons.RENAME

@Composable
internal fun HollowIdeProjectContextMenu(
    menu: ProjectContextMenu?,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateScript: (String, ScriptTemplate) -> Unit,
    onCreateSoundEvents: (String) -> Unit,
    onRename: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCut: (String) -> Unit,
    onPaste: (String) -> Unit,
    onShowInExplorer: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (menu == null) return
    var scriptsOpen by remember(menu) { mutableStateOf(false) }
    var scriptsAnchor by remember(menu) { mutableStateOf<UiRect?>(null) }
    val closeScripts = { scriptsOpen = false }
    Popup(
        anchorBounds = UiRect(menu.x, menu.y, 0f, 0f),
        alignment = UiPopupAlignment.Cursor,
        id = "project-context-menu",
        tags = listOf("dropdown-popup", "project-context-menu"),
        onDismiss = onDismiss,
    ) {
        ProjectMenuItem("New File", "Alt+Insert", CREATE_FILE.toString(), closeScripts) { onCreateFile(menu.path) }
        ProjectMenuItem("New Folder", "Alt+Shift+Insert", CREATE_FOLDER.toString(), closeScripts) { onCreateFolder(menu.path) }
        if (menu.canCreateScripts) {
            ProjectMenuItem(
                label = "New Script",
                shortcut = "›",
                icon = Icons.FILE_KTS.toString(),
                onEnter = { scriptsOpen = true },
                onPlaced = { scriptsAnchor = it },
            ) { scriptsOpen = !scriptsOpen }
        }
        if (menu.canCreateSoundEvents) {
            ProjectMenuItem("New Sound Events", "", FILE_SOUND.toString(), closeScripts) { onCreateSoundEvents(menu.path) }
        }
        ProjectMenuItem("Rename", "F2", RENAME.toString(), closeScripts) { onRename(menu.path) }
        ProjectMenuItem("Copy", "Ctrl+C", COPY.toString(), closeScripts) { onCopy(menu.path) }
        ProjectMenuItem("Cut", "Ctrl+X", CUT.toString(), closeScripts) { onCut(menu.path) }
        ProjectMenuItem("Paste", "Ctrl+V", PASTE.toString(), closeScripts) { onPaste(menu.path) }
        ProjectMenuItem("Show in Explorer", "", FOLDER.toString(), closeScripts) { onShowInExplorer(menu.path) }
        ProjectMenuItem("Delete", "Del", REMOVE.toString(), closeScripts) { onDelete(menu.path) }
    }

    val anchor = scriptsAnchor
    if (scriptsOpen && anchor != null) {
        Popup(
            anchorBounds = anchor,
            alignment = SubmenuAlignment,
            layer = 1,
            id = "project-new-script-menu",
            tags = listOf("dropdown-popup", "project-context-menu"),
            onDismiss = onDismiss,
        ) {
            ScriptTemplate.entries.forEach { template ->
                ProjectMenuItem(template.label, template.extension, template.icon) { onCreateScript(menu.path, template) }
            }
        }
    }
}

/** Opens a submenu to the right of the item it belongs to, level with it. */
private val SubmenuAlignment = UiPopupAlignment(
    anchorHorizontal = UiAlign.END,
    anchorVertical = UiAlign.START,
    offsetX = 6f,
    offsetY = -4f,
)

internal const val ProjectNameDialogInputId = "project-name-dialog-input"

@Composable
internal fun HollowIdeProjectNameDialog(
    dialog: ProjectNameDialog?,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (dialog == null) return
    Popup(
        anchorBounds = UiRect(dialog.x, dialog.y, 0f, 0f),
        alignment = UiPopupAlignment.Cursor,
        id = "project-name-dialog",
        tags = listOf("dropdown-popup", "project-name-dialog"),
        modal = true,
    ) {
        Text(dialog.title, tags = listOf("project-name-dialog-title"))
        TextField(
            value = dialog.name,
            onChange = onNameChange,
            id = ProjectNameDialogInputId,
            tags = listOf("project-name-dialog-input"),
            modifier = Modifier.size(180.px, 24.px).onKeyInput { input ->
                when (input.key) {
                    GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        onConfirm()
                        input.consume()
                    }

                    GLFW.GLFW_KEY_ESCAPE -> {
                        onCancel()
                        input.consume()
                    }
                }
            },
        )
        Row(tags = listOf("project-name-dialog-actions")) {
            ProjectMenuItem("OK", "", icon = null, action = onConfirm)
            ProjectMenuItem("Cancel", "", icon = null, action = onCancel)
        }
    }
}

@Composable
private fun ProjectMenuItem(
    label: String,
    shortcut: String,
    icon: String? = null,
    onEnter: () -> Unit = {},
    onPlaced: ((UiRect) -> Unit)? = null,
    action: () -> Unit,
) {
    Row(
        tags = listOf("dropdown-item", "project-context-menu-item"),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .alignItems(vertical = UiAlign.CENTER)
            .onEnter { onEnter() }
            .let { if (onPlaced != null) it.onPlaced(onPlaced) else it }
            .onClick { event ->
                action()
                event.consume()
            }
    ) {
        if (icon != null) Image(icon, tags = listOf("dropdown-item-icon", "project-context-menu-icon"))
        Text(label, tags = listOf("dropdown-item-label", "project-context-menu-label"))
        if (shortcut.isNotBlank()) Text(shortcut, tags = listOf("dropdown-item-shortcut", "project-context-menu-shortcut"))
    }
}

internal data class ProjectContextMenu(
    val path: String,
    val x: Float,
    val y: Float,
    val canCreateScripts: Boolean = false,
    val canCreateSoundEvents: Boolean = false,
)

internal data class ProjectNameDialog(
    val action: ProjectNameAction,
    val path: String,
    val name: String,
    val x: Float,
    val y: Float,
    val template: ScriptTemplate? = null,
) {
    val title: String
        get() = when (action) {
            ProjectNameAction.CreateFile -> template?.let { "New ${it.label}" } ?: "New File"
            ProjectNameAction.CreateFolder -> "New Folder"
            ProjectNameAction.Rename -> "Rename"
        }
}

internal enum class ProjectNameAction {
    CreateFile,
    CreateFolder,
    Rename,
}

internal fun HollowIdeFileOperationResult.statusText(): String {
    return when (this) {
        HollowIdeFileOperationResult.Success -> ""
        HollowIdeFileOperationResult.InvalidName -> "Invalid file name"
        HollowIdeFileOperationResult.AlreadyExists -> "File already exists"
        HollowIdeFileOperationResult.NotFound -> "File not found"
    }
}
