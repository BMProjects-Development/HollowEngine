package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.docking.DockingState
import ru.hollowhorizon.hollowengine.client.ui.ide.remote.message
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectError

internal class HollowIdeCloseFiles(private val model: HollowIdeModel, private val dock: DockingState) {
    private var pending by mutableStateOf<List<String>>(emptyList())
    private var waitingForSaves by mutableStateOf(false)

    val needsUpdate: Boolean get() = pending.isNotEmpty() && pending.none { file(it)?.remote?.saving == true } &&
        (waitingForSaves || pending.none { file(it)?.hasUnsavedChanges == true })

    fun confirm(ids: List<String>): Boolean {
        if (ids.none { file(it)?.hasUnsavedChanges == true }) return true
        if (!waitingForSaves) pending = (pending + ids).distinct()
        return false
    }

    fun close(ids: List<String>) {
        val open = ids.filter(dock::contains)
        if (confirm(open)) open.forEach(dock::closeConfirmed)
    }

    fun cancel(): Boolean {
        if (pending.isEmpty()) return false
        pending = emptyList()
        waitingForSaves = false
        return true
    }

    fun tick() {
        if (!needsUpdate) return
        waitingForSaves = false
        if (pending.none { file(it)?.hasUnsavedChanges == true }) finish()
    }

    internal fun save() {
        if (pending.any { file(it)?.remote?.saving == true }) return
        model.saveModifiedFiles(pending.mapNotNull { file(it)?.path })
        waitingForSaves = true
        tick()
    }

    internal fun discard() {
        if (pending.any { file(it)?.remote?.saving == true }) return
        pending.mapNotNull(::file).filter(HollowIdeOpenFile::hasUnsavedChanges).forEach(model::removeFile)
        finish()
    }

    private fun finish() {
        val ids = pending
        cancel()
        ids.forEach(dock::closeConfirmed)
    }

    @Composable
    fun Dialog() {
        val files = pending.mapNotNull(::file).filter(HollowIdeOpenFile::hasUnsavedChanges)
        if (files.isEmpty()) return
        val saving = waitingForSaves || files.any { it.remote?.saving == true }
        val message = files.singleOrNull()?.let { "$Lang.close_unsaved_file".lang(it.title) }
            ?: "$Lang.close_unsaved_files".lang(files.size)
        val remoteErrors = files.mapNotNull { file ->
            val remote = file.remote ?: return@mapNotNull null
            when {
                !remote.online -> RemoteProjectError.DISCONNECTED
                remote.missing -> RemoteProjectError.MISSING
                remote.conflict != null -> RemoteProjectError.CONFLICT
                else -> remote.error
            }
        }.distinct()
        ProjectDialog("ide-close-unsaved", "$Lang.unsaved_indicator".lang, onDismiss = { cancel() }) {
            Paragraph(message)
            if (!saving) remoteErrors.forEach { Paragraph(it.message(), "project-dialog-error") }
            Actions {
                DialogButton("ide-close-cancel", "hollowengine.gui.ide.project.cancel".lang) { cancel() }
                DialogButton("ide-close-discard", "$Lang.close_discard".lang, enabled = !saving, onClick = ::discard)
                DialogButton("ide-close-save", "$Lang.close_save".lang, primary = true, enabled = !saving) { save() }
            }
        }
    }

    private fun file(id: String): HollowIdeOpenFile? = fileDockItemPath(id)?.let(model.files::get)

    private companion object {
        const val Lang = "hollowengine.gui.ide.file"
    }
}
