package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.project.remote.*

internal class RemoteProjectPanelController(
    private val workspace: RemoteProjectWorkspace,
    private val focus: () -> Unit,
    private val pointer: () -> Pair<Float, Float>,
) {
    private var context by mutableStateOf<Pair<RemoteProjectWorkspace.Node, UiRect>?>(null)
    private var dialog by mutableStateOf<ProjectNameDialog?>(null)
    private var deletePath by mutableStateOf<String?>(null)
    private var cutPath by mutableStateOf<String?>(null)
    private var lastPath: String? = null
    private var lastClick = 0L
    private var clipboardSession = 0L

    fun closePopups(): Boolean {
        val open = context != null || dialog != null || deletePath != null
        context = null
        dialog = null
        deletePath = null
        return open
    }

    fun click(node: RemoteProjectWorkspace.Node, event: UiEvent) {
        focus()
        workspace.selectedPath = node.path
        if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            context = node to UiRect(event.x, event.y, 0f, 0f)
            lastPath = null
            return
        }
        context = null
        val now = System.nanoTime()
        if (lastPath == node.path && now - lastClick < 400_000_000) {
            lastPath = null
            if (node.directory) workspace.toggle(node.path) else workspace.open(node.path)
        } else {
            lastPath = node.path
            lastClick = now
        }
    }

    fun shortcut(key: Int, modifiers: Int, shortcutsActive: Boolean): Boolean {
        if (!workspace.selected) return false
        if (key == GLFW.GLFW_KEY_ESCAPE && closePopups()) return true
        if (dialog != null && key in listOf(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER)) {
            submit()
            return true
        }
        if (!shortcutsActive || dialog != null || deletePath != null) return false
        if (key == GLFW.GLFW_KEY_F5) {
            workspace.refresh()
            return true
        }
        val node = workspace.tree("").firstOrNull { it.path == workspace.selectedPath } ?: return false
        if (HollowKeys.COPY.matches(key, modifiers)) {
            copyPath(node.path)
            return true
        }
        return when (resolveProjectShortcut(key, modifiers, node.depth > 1)) {
            ProjectShortcut.CreateFile -> true.also { if (node.depth > 0) name(node, ProjectNameAction.CreateFile) }
            ProjectShortcut.CreateFolder -> true.also { name(node, ProjectNameAction.CreateFolder) }
            ProjectShortcut.Rename -> true.also { name(node, ProjectNameAction.Rename) }
            ProjectShortcut.Delete -> true.also { deletePath = node.path }
            ProjectShortcut.Cut -> true.also { cut(node.path) }
            ProjectShortcut.Paste -> true.also { paste(node) }
            ProjectShortcut.Copy, null -> false
        }
    }

    @Composable
    fun Tree(filter: UiTreeFilterState, focusFilter: (String) -> Unit, sourceTail: @Composable () -> Unit) {
        Column(tags = listOf("ide-panel", "project-tree-panel"), modifier = Modifier.size(100.percent, 100.percent)) {
            UiTreeView(
                tags = listOf("remote-project-tree"),
                items = workspace.tree(filter.query).map { node ->
                    UiTreeItem(
                        id = "remote-project-${node.path}",
                        label = node.label,
                        depth = node.depth,
                        payload = node,
                        icon = IconHelper.forPath(node.path, node.directory, node.expanded).toString(),
                        hasChildren = node.directory,
                        expanded = node.expanded,
                        selected = node.path == workspace.selectedPath,
                    )
                },
                onToggle = { focus(); workspace.selectedPath = it.payload.path; workspace.toggle(it.payload.path) },
                onSelect = { item, event -> click(item.payload, event) },
                fillRowWidth = false,
                filterState = filter,
                filterPlaceholder = "$RemoteLang.filter".lang,
                onFilterOpened = focusFilter,
                rowTail = {
                    if (it.depth == 0) sourceTail()
                    if (it.expanded && it.payload.loading) {
                        Text("$RemoteLang.loading".lang, tags = listOf("ide-remote-message", "ide-remote-loading"))
                    }
                },
                dragItem = { item ->
                    item.takeIf { it.depth > 1 }?.let { UiDragItem(DraggedPath(it.payload.path), it.icon, it.label) }
                },
                canDrop = { item, drag ->
                    val from = drag.payload as? DraggedPath
                    from != null && canMove(from.path, item.payload)
                },
                onDrop = { item, drag ->
                    val from = drag.payload as? DraggedPath
                    if (from == null || !canMove(from.path, item.payload)) false
                    else true.also { moveInto(from.path, item.payload.path) }
                },
            )
            Menus()
        }
    }

    @Composable
    private fun Menus() {
        context?.let { (node, anchor) ->
            ContextMenu(
                id = "remote-project-context",
                anchorBounds = anchor,
                items = buildList {
                    if (node.depth > 0) {
                        add(UiDropdownItem("$RemoteLang.new_file".lang) { name(node, ProjectNameAction.CreateFile) })
                        add(UiDropdownItem("$RemoteLang.new_folder".lang) {
                            name(
                                node,
                                ProjectNameAction.CreateFolder
                            )
                        })
                        if (cutPath != null && clipboardSession == RemoteProjectConnection.session) {
                            add(UiDropdownItem("$RemoteLang.move_here".lang) { paste(node) })
                        }
                    } else {
                        add(UiDropdownItem("$RemoteLang.new_folder".lang) {
                            name(
                                node,
                                ProjectNameAction.CreateFolder
                            )
                        })
                    }
                    if (node.depth > 1) {
                        add(UiDropdownItem("$RemoteLang.rename".lang) { name(node, ProjectNameAction.Rename) })
                        add(UiDropdownItem("$RemoteLang.cut".lang) { cut(node.path) })
                        add(UiDropdownItem("$RemoteLang.delete".lang) { deletePath = node.path })
                    }
                    if (node.depth > 0) add(UiDropdownItem("hollowengine.gui.ide.project.copy_path".lang) {
                        copyPath(
                            node.path
                        )
                    })
                    add(UiDropdownItem("$RemoteLang.refresh".lang, separatorBefore = true) { workspace.refresh() })
                },
                onExpandedChange = { if (!it) context = null },
            )
        }
        HollowIdeProjectNameDialog(
            dialog = dialog,
            onNameChange = { dialog = dialog?.copy(name = it) },
            onConfirm = ::submit,
            onCancel = { dialog = null },
            title = dialog?.action?.let { "$RemoteLang.${it.labelKey}".lang },
            confirmLabel = "$RemoteLang.confirm".lang,
            cancelLabel = "$RemoteLang.cancel".lang,
        )
        deletePath?.let { path ->
            val (x, y) = pointer()
            val anchor = remember(path) { UiRect(x, y, 0f, 0f) }
            ContextMenu(
                id = "remote-project-delete",
                anchorBounds = anchor,
                items = listOf(
                    UiDropdownItem("$RemoteLang.confirm_delete".lang(path)) {
                        workspace.mutate(RemoteProjectCommand.Delete(path))
                    },
                    UiDropdownItem("$RemoteLang.cancel".lang),
                ),
                onExpandedChange = { if (!it) deletePath = null },
            )
        }
    }

    private fun name(node: RemoteProjectWorkspace.Node, action: ProjectNameAction) {
        val (x, y) = pointer()
        val path = if (action == ProjectNameAction.Rename) node.path else node.parent
        val initial = if (action == ProjectNameAction.Rename) node.label else ""
        dialog = ProjectNameDialog(action, path, initial, x, y, isDirectory = node.directory)
    }

    private fun submit() {
        val form = dialog ?: return
        val name = form.name.trim()
        if (name.isEmpty() || name.any { it == '/' || it == '\\' }) {
            notifyRemoteProjectError(RemoteProjectError.INVALID_PATH)
            return
        }
        val command = when (form.action) {
            ProjectNameAction.CreateFile -> RemoteProjectCommand.Create(joinProjectPath(form.path, name), false)
            ProjectNameAction.CreateFolder -> RemoteProjectCommand.Create(joinProjectPath(form.path, name), true)
            ProjectNameAction.Rename -> RemoteProjectCommand.Move(
                form.path,
                joinProjectPath(projectPathParent(form.path), name)
            )
        }
        workspace.mutate(command) {
            if (dialog == form) dialog = null
            if (command is RemoteProjectCommand.Create) {
                workspace.expand(form.path)
                if (!command.directory) workspace.open(command.path)
            }
        }
    }

    private fun cut(path: String) {
        clipboardSession = RemoteProjectConnection.session
        cutPath = path
    }

    private fun copyPath(path: String) {
        Minecraft.getInstance().keyboardHandler.clipboard = path
    }

    private fun paste(node: RemoteProjectWorkspace.Node) {
        val path = cutPath?.takeIf { clipboardSession == RemoteProjectConnection.session } ?: return
        moveInto(path, node.parent) { if (cutPath == path) cutPath = null }
    }

    private fun moveInto(path: String, directory: String, completed: () -> Unit = {}) {
        val target = joinProjectPath(directory, path.substringAfterLast('/'))
        workspace.mutate(RemoteProjectCommand.Move(path, target)) {
            workspace.expand(directory)
            completed()
        }
    }

    private fun canMove(path: String, target: RemoteProjectWorkspace.Node): Boolean =
        target.directory && target.depth > 0 && !projectPathContains(
            path,
            target.path
        ) && projectPathParent(path) != target.path

    private data class DraggedPath(val path: String)
}

@Composable
internal fun RemoteProjectSourceDropdown(workspace: RemoteProjectWorkspace, switch: (Boolean) -> Unit) {
    if (workspace.projectName == null) return
    var expanded by remember { mutableStateOf(false) }
    UiDropdown(
        id = "ide-project-source",
        label = "$RemoteLang.${if (workspace.selected) "server" else "local"}".lang,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        items = listOf(
            UiDropdownItem("$RemoteLang.local".lang, mark = UiMenuParts.radio(!workspace.selected)) { switch(false) },
            UiDropdownItem("$RemoteLang.server".lang, mark = UiMenuParts.radio(workspace.selected)) { switch(true) },
        ),
        tags = listOf("ide-project-source"),
    )
}

@Composable
internal fun RemoteProjectEditorStatus(file: HollowIdeOpenFile, workspace: RemoteProjectWorkspace) {
    val remote = file.remote ?: return
    val message = when {
        !remote.online -> RemoteProjectError.DISCONNECTED.message()
        remote.saving -> "$RemoteLang.saving".lang
        remote.missing -> RemoteProjectError.MISSING.message()
        remote.conflict != null -> RemoteProjectError.CONFLICT.message()
        else -> remote.error?.message()
    } ?: return
    Row(
        tags = listOf("ide-remote-status"),
        modifier = Modifier.size(100.percent, 26.px).alignItems(vertical = UiAlign.CENTER),
    ) {
        Text(message, tags = listOf("ide-remote-message"))
        if (remote.conflict != null && remote.online && !remote.saving) {
            var expanded by remember { mutableStateOf(false) }
            UiDropdown(
                id = "ide-remote-conflict-${file.id}",
                label = "$RemoteLang.resolve".lang,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                items = listOf(
                    UiDropdownItem("$RemoteLang.view_server".lang) { workspace.showServerVersion(file) },
                    UiDropdownItem("$RemoteLang.use_server".lang) { remote.useServerVersion(file) },
                    UiDropdownItem("$RemoteLang.keep_local".lang) { remote.useLatestRevision() },
                ),
            )
        }
    }
}

private const val RemoteLang = "hollowengine.gui.ide.remote"

private val ProjectNameAction.labelKey: String
    get() = when (this) {
        ProjectNameAction.CreateFile -> "new_file"
        ProjectNameAction.CreateFolder -> "new_folder"
        ProjectNameAction.Rename -> "rename"
    }
