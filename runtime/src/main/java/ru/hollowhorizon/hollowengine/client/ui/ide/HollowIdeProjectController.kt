package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.UiEvent
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import ru.hollowhorizon.hollowengine.common.utils.DesktopUtil
import java.io.File

internal class HollowIdeProjectController(
    private val model: HollowIdeModel,
    private val focusProjectTree: () -> Unit,
    private val shortcutsActive: () -> Boolean,
    private val closeDockItem: (String) -> Unit,
    private val openFile: (HollowIdeOpenFile) -> Unit,
    private val setStatus: (String) -> Unit,
    private val pointerX: () -> Float,
    private val pointerY: () -> Float,
) {
    var contextMenu by mutableStateOf<ProjectContextMenu?>(null)
        private set
    var nameDialog by mutableStateOf<ProjectNameDialog?>(null)
        private set
    private var lastClickPath: String = ""
    private var lastClickAtMillis: Long = 0L

    fun toggle(item: UiTreeItem<HollowIdeFileNode>) {
        focusProjectTree()
        contextMenu = null
        model.toggle(item.payload)
    }

    fun select(item: UiTreeItem<HollowIdeFileNode>, event: UiEvent) {
        focusProjectTree()
        val additive = event.modifiers and GLFW.GLFW_MOD_CONTROL != 0
        if (event.button == 1) {
            model.focusSelection(item.payload)
            contextMenu = ProjectContextMenu(
                path = item.payload.path,
                x = event.x,
                y = event.y,
                canCreateScripts = isInsideScripts(item.payload.path),
                canCreateSoundEvents = canCreateSoundEvents(item.payload),
            )
            nameDialog = null
            return
        }
        contextMenu = null
        if (additive) {
            model.select(item.payload, additive = true)
            return
        }
        val doubleClick = isDoubleClick(item.payload.path)
        if (item.payload.path in model.selectedTreePaths) model.focusSelection(item.payload)
        else model.select(item.payload)
        if (!doubleClick) return
        rememberClick("")
        when (val result = model.open(item.payload)) {
            HollowIdeOpenResult.Directory -> Unit
            HollowIdeOpenResult.Unsupported -> setStatus("Unsupported or binary file: ${item.payload.path}")
            is HollowIdeOpenResult.File -> openFile(result.file)
        }
    }

    fun createSoundEvents(path: String) {
        contextMenu = null
        when (val result = model.createSoundsFile(path)) {
            is HollowIdeOpenResult.File -> openFile(result.file)
            else -> setStatus("Не удалось создать sounds.json")
        }
    }

    fun openCreateFileDialog(path: String) = openNameDialog(ProjectNameAction.CreateFile, path)

    fun openCreateScriptDialog(path: String, template: ScriptTemplate) =
        openNameDialog(ProjectNameAction.CreateFile, path, template)

    fun openCreateFolderDialog(path: String) = openNameDialog(ProjectNameAction.CreateFolder, path)

    fun openRenameDialog(path: String) = openNameDialog(ProjectNameAction.Rename, path)

    fun updateNameDialog(name: String) {
        nameDialog = nameDialog?.copy(name = name)
    }

    fun applyNameDialog() {
        val dialog = nameDialog ?: return
        val template = dialog.template
        val result = when {
            template != null -> createScript(dialog, template)
            dialog.action == ProjectNameAction.CreateFile -> model.createFile(dialog.path, dialog.name)
            dialog.action == ProjectNameAction.CreateFolder -> model.createFolder(dialog.path, dialog.name)
            else -> rename(dialog)
        }
        setStatus(result.statusText())
        if (result == HollowIdeFileOperationResult.Success) nameDialog = null
    }

    private fun createScript(dialog: ProjectNameDialog, template: ScriptTemplate): HollowIdeFileOperationResult {
        val name = template.fileName(dialog.name)
        val directory = if (dialog.path.fromReadablePath().isDirectory) dialog.path else dialog.path.substringBeforeLast('/', "")
        val path = if (directory.isBlank()) name else "$directory/$name"
        val result = model.createFile(dialog.path, name, template.render(path))
        if (result == HollowIdeFileOperationResult.Success) {
            (model.openFile(model.selectedTreePath) as? HollowIdeOpenResult.File)?.let { openFile(it.file) }
        }
        return result
    }

    fun cancelNameDialog() {
        nameDialog = null
    }

    fun copy(path: String = model.selectedTreePath, cut: Boolean = false) {
        val paths = model.selectedOr(path)
        setStatus(model.copyToClipboard(paths, cut).statusText())
        contextMenu = null
    }

    fun pasteInto(path: String = model.selectedTreePath) {
        contextMenu = null
        setStatus("Pasting...")
        model.pasteIntoAsync(path) { result ->
            setStatus(result.statusText())
        }
    }

    /** Imports a native file drop through the same serialized IO path as clipboard paste. */
    fun importFiles(files: List<File>, targetPath: String): Boolean {
        if (files.isEmpty()) return false
        contextMenu = null
        focusProjectTree()
        model.importFilesAsync(files, targetPath) { result -> setStatus(result.statusText()) }
        return true
    }

    /** Drag and drop inside the tree: moves [path] into the folder [targetPath] belongs to. */
    fun moveInto(path: String, targetPath: String): Boolean {
        if (path == targetPath) return false
        val result = model.moveInto(listOf(path), targetPath)
        setStatus(result.statusText())
        return result == HollowIdeFileOperationResult.Success
    }

    fun showInExplorer(path: String) {
        DesktopUtil.openInExplorer(path.fromReadablePath())
        contextMenu = null
    }

    fun delete(path: String = model.selectedTreePath) {
        setStatus(model.delete(model.selectedOr(path)).statusText())
        contextMenu = null
    }

    fun handleNameDialogKey(key: Int): Boolean {
        if (nameDialog == null) return false
        return when (key) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                applyNameDialog()
                true
            }
            GLFW.GLFW_KEY_ESCAPE -> {
                cancelNameDialog()
                true
            }
            else -> false
        }
    }

    fun handleShortcut(key: Int, modifiers: Int): Boolean {
        if (!shortcutsActive()) return false
        return when (resolveProjectShortcut(key, modifiers, model.selectedTreePath.isNotBlank())) {
            ProjectShortcut.Copy -> {
                copy(cut = false)
                true
            }
            ProjectShortcut.Cut -> {
                copy(cut = true)
                true
            }
            ProjectShortcut.Paste -> {
                pasteInto()
                true
            }
            ProjectShortcut.Delete -> {
                delete()
                true
            }
            ProjectShortcut.Rename -> {
                openRenameDialog(model.selectedTreePath)
                true
            }
            ProjectShortcut.CreateFolder -> {
                openCreateFolderDialog(model.selectedTreePath)
                true
            }
            ProjectShortcut.CreateFile -> {
                openCreateFileDialog(model.selectedTreePath)
                true
            }
            null -> false
        }
    }

    fun hasOpenPopup(): Boolean {
        return contextMenu != null || nameDialog != null
    }

    fun closePopups(): Boolean {
        val changed = hasOpenPopup()
        contextMenu = null
        nameDialog = null
        return changed
    }

    private fun rename(dialog: ProjectNameDialog): HollowIdeFileOperationResult {
        val oldPath = dialog.path
        val parent = oldPath.substringBeforeLast('/', "")
        val newPath = if (parent.isBlank()) dialog.name.trim() else "$parent/${dialog.name.trim()}"
        val result = model.rename(oldPath, dialog.name)
        if (result == HollowIdeFileOperationResult.Success) {
            closeDockItem(fileDockItemId(oldPath))
            model.files[newPath]?.let(openFile)
        }
        return result
    }

    private fun openNameDialog(action: ProjectNameAction, path: String, template: ScriptTemplate? = null) {
        val defaultName = when (action) {
            ProjectNameAction.Rename -> path.substringAfterLast('/')
            ProjectNameAction.CreateFile -> template?.suggestedFileName ?: "new_file.kts"
            ProjectNameAction.CreateFolder -> "new_folder"
        }
        val menu = contextMenu
        contextMenu = null
        nameDialog = ProjectNameDialog(
            action = action,
            path = path,
            name = defaultName,
            x = menu?.x ?: pointerX(),
            y = menu?.y ?: pointerY(),
            template = template,
        )
    }

    private fun canCreateSoundEvents(node: HollowIdeFileNode): Boolean {
        if (!node.isDirectory || !NamespaceFolderRegex.matches(node.path)) return false
        return !"${node.path}/sounds.json".fromReadablePath().exists()
    }

    private fun isDoubleClick(path: String): Boolean {
        val now = System.currentTimeMillis()
        val doubleClick = lastClickPath == path && now - lastClickAtMillis <= ProjectTreeDoubleClickMillis
        rememberClick(path, now)
        return doubleClick
    }

    private fun rememberClick(path: String, now: Long = System.currentTimeMillis()) {
        lastClickPath = path
        lastClickAtMillis = now
    }
}

internal enum class ProjectShortcut {
    Copy,
    Cut,
    Paste,
    Delete,
    Rename,
    CreateFolder,
    CreateFile,
}

internal fun resolveProjectShortcut(
    key: Int,
    modifiers: Int,
    hasSelectedPath: Boolean,
): ProjectShortcut? {
    val command = modifiers and GLFW.GLFW_MOD_CONTROL != 0
    val alt = modifiers and GLFW.GLFW_MOD_ALT != 0
    val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
    return when {
        command && key == GLFW.GLFW_KEY_C && hasSelectedPath -> ProjectShortcut.Copy
        command && key == GLFW.GLFW_KEY_X && hasSelectedPath -> ProjectShortcut.Cut
        command && key == GLFW.GLFW_KEY_V -> ProjectShortcut.Paste
        key == GLFW.GLFW_KEY_DELETE && hasSelectedPath -> ProjectShortcut.Delete
        key == GLFW.GLFW_KEY_F2 && hasSelectedPath -> ProjectShortcut.Rename
        key == GLFW.GLFW_KEY_INSERT && alt && shift -> ProjectShortcut.CreateFolder
        key == GLFW.GLFW_KEY_INSERT && alt -> ProjectShortcut.CreateFile
        else -> null
    }
}

private const val ProjectTreeDoubleClickMillis = 350L

/** Resource-namespace folders like `assets/<modid>` where a `sounds.json` belongs. */
private val NamespaceFolderRegex = Regex("^assets/[^/]+$")
