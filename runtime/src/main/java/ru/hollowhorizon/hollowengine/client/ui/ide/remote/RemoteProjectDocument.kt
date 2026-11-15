package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectError
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectFile

internal class RemoteProjectDocument(val session: Long, path: String, var revision: String) {
    var path by mutableStateOf(path)
    var saving by mutableStateOf(false)
    var conflict by mutableStateOf<RemoteProjectFile?>(null)
    var error by mutableStateOf<RemoteProjectError?>(null)
    var missing by mutableStateOf(false)
    var refreshing = false
    var refreshAgain = false
    var saveAgain = false

    val online: Boolean get() = session == RemoteProjectConnection.session && RemoteProjectConnection.projectName != null
    val writable: Boolean get() = online && !saving && !refreshing && conflict == null && !missing &&
        error != RemoteProjectError.UNSUPPORTED

    fun receive(file: HollowIdeOpenFile, latest: RemoteProjectFile) {
        if (latest.revision == revision) {
            conflict = null
        } else if (file.hasUnsavedChanges) {
            val matches = runCatching { file.encode().contentEquals(latest.bytes) }.getOrDefault(false)
            if (matches) {
                revision = latest.revision
                conflict = null
                file.markSaved()
            } else conflict = latest
        } else {
            if (!accept(file, latest)) return
        }
        missing = false
        error = null
    }

    fun useServerVersion(file: HollowIdeOpenFile) {
        val latest = conflict ?: return
        if (accept(file, latest)) error = null
    }

    fun useLatestRevision() {
        revision = conflict?.revision ?: return
        conflict = null
    }

    private fun accept(file: HollowIdeOpenFile, latest: RemoteProjectFile): Boolean {
        try {
            file.replaceRemoteContent(latest.bytes)
        } catch (failure: Exception) {
            HollowEngine.LOGGER.error("Could not reload server file {}", path, failure)
            error = RemoteProjectError.UNSUPPORTED
            return false
        }
        revision = latest.revision
        conflict = null
        return true
    }
}
