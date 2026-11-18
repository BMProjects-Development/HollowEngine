package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeModel
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenResult
import ru.hollowhorizon.hollowengine.client.ui.ide.remote.RemoteProjectWorkspace
import ru.hollowhorizon.hollowengine.common.addons.project.remote.joinProjectPath
import ru.hollowhorizon.hollowengine.common.addons.project.remote.projectPathParent

internal class HollowIdePreviewDocuments(
    private val model: HollowIdeModel,
    private val remoteProject: RemoteProjectWorkspace,
    private val openTab: (HollowIdeOpenFile, Boolean) -> Unit,
    private val write: (HollowIdeOpenFile, String, UndoLabel?, String?) -> Unit,
) {
    fun canOpenSiblings(file: HollowIdeOpenFile): Boolean =
        model.files[file.path] === file && (file.remote?.online ?: !file.virtual)

    fun sibling(file: HollowIdeOpenFile, name: String): HollowIdeOpenFile? {
        val path = siblingPath(file, name) ?: return null
        val remote = file.remote ?: return model.files[path]?.takeUnless { it.virtual }
        return model.files.values.firstOrNull { it.remote?.session == remote.session && it.remote.path == path }
    }

    fun openSibling(file: HollowIdeOpenFile, name: String, completed: (Boolean) -> Unit) {
        val path = siblingPath(file, name)
        if (path == null) {
            completed(false)
        } else if (file.remote != null) {
            remoteProject.openInBackground(path, completed)
        } else {
            val opened = (model.openFile(path, selectInTree = false) as? HollowIdeOpenResult.File)?.file
            if (opened != null) openTab(opened, false)
            completed(opened != null)
        }
    }

    fun edit(origin: HollowIdeOpenFile, target: HollowIdeOpenFile, next: String, label: UndoLabel?, mergeKey: String?) {
        if (model.files[origin.path] !== origin || model.files[target.path] !== target || target.readOnly) return
        if (target.textOrNull?.let { it != next } == true) write(target, next, label, mergeKey)
    }

    private fun siblingPath(file: HollowIdeOpenFile, name: String): String? {
        if (!canOpenSiblings(file) || name.isBlank() || name in listOf(".", "..") ||
            name.any { it == '/' || it == '\\' || it == ':' }
        ) return null
        return joinProjectPath(projectPathParent(file.projectPath), name)
    }
}
