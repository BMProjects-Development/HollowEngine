package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    val online: Boolean get() = session == RemoteProjectConnection.session && RemoteProjectConnection.projectName != null
    val writable: Boolean get() = online && !saving && !refreshing && conflict == null && !missing &&
        error != RemoteProjectError.UNSUPPORTED

    fun receive(file: HollowIdeOpenFile, latest: RemoteProjectFile) {
        if (latest.revision == revision) {
            conflict = null
        } else if (file.dirty && !encodeProjectText(path, file.text).contentEquals(latest.bytes)) {
            conflict = latest
        } else {
            revision = latest.revision
            conflict = null
            file.replaceRemoteText(requireNotNull(latest.text(path)))
        }
        missing = false
        error = null
    }

    fun useServerVersion(file: HollowIdeOpenFile) {
        val latest = conflict ?: return
        revision = latest.revision
        conflict = null
        file.replaceRemoteText(requireNotNull(latest.text(path)))
    }

    fun useLatestRevision() {
        revision = conflict?.revision ?: return
        conflict = null
    }
}
