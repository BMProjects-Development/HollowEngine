package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeModel
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.isProbablyText
import ru.hollowhorizon.hollowengine.client.ui.notification.HollowNotifications
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectProperties
import ru.hollowhorizon.hollowengine.common.addons.project.remote.*

internal class RemoteProjectWorkspace(
    private val model: HollowIdeModel,
    private val openTab: (HollowIdeOpenFile) -> Unit,
    private val tabChanged: (HollowIdeOpenFile) -> Unit,
) {
    private val directories = mutableStateMapOf<String, Directory>()
    private val pendingReads = HashSet<String>()
    private var nextTabId = 0L

    val projectName: String? get() = RemoteProjectConnection.projectName
    var selected by mutableStateOf(false)
        private set
    var selectedPath by mutableStateOf("")
    val loading: Boolean get() = directories.values.any { it.loading }

    init {
        RemoteProjectConnection.onReset = ::reset
        RemoteProjectConnection.onChange = ::changed
    }

    fun select(remote: Boolean) {
        selected = remote && projectName != null
        if (selected) expand("")
    }

    fun expand(path: String) {
        val directory = directories.getOrPut(path, ::Directory)
        directory.expanded = true
        if (!directory.loaded && !directory.loading) load(path, directory)
    }

    fun toggle(path: String) {
        val directory = directories[path]
        if (directory?.expanded != true) {
            expand(path)
            return
        }
        directories.forEach { (candidate, child) ->
            if (projectPathContains(path, candidate)) child.expanded = false
        }
    }

    fun refresh() {
        directories.toMap().forEach { (path, directory) ->
            if (directory.expanded) refreshDirectory(path)
        }
        model.files.values.toList().filter { it.remote?.online == true }.forEach(::refreshFile)
    }

    fun discard(file: HollowIdeOpenFile) {
        if (file.remote?.saving == false) model.removeRemote(file)
    }

    fun tree(filter: String): List<Node> = buildList {
        val name = projectName ?: return@buildList
        val query = filter.trim()
        fun collect(path: String, label: String, depth: Int, isDirectory: Boolean) {
            val directory = directories[path]
            val index = size
            add(Node(path, label, depth, isDirectory, directory?.expanded == true || query.isNotEmpty()))
            if (isDirectory && (directory?.expanded == true || query.isNotEmpty())) {
                directory?.entries?.forEach { child ->
                    collect(joinProjectPath(path, child.name), child.name, depth + 1, child.directory)
                }
            }
            if (size == index + 1 && path.isNotEmpty() && !path.contains(query, ignoreCase = true)) {
                removeAt(index)
            }
        }
        collect("", name, 0, true)
    }

    fun open(path: String) {
        val session = RemoteProjectConnection.session
        model.files.values.firstOrNull { it.remote?.session == session && it.remote.path == path }?.let {
            openTab(it)
            refreshFile(it)
            return
        }
        if (!pendingReads.add(path)) return
        RemoteProjectConnection.read(path) { snapshot, error ->
            pendingReads.remove(path)
            if (snapshot == null) {
                notifyRemoteProjectError(error ?: RemoteProjectError.IO)
            } else {
                val text = snapshot.text(path)
                if (text == null) notifyRemoteProjectError(RemoteProjectError.UNSUPPORTED)
                else {
                    val identity = "remote-project://$session/${++nextTabId}/$path"
                    val remote = RemoteProjectDocument(session, path, snapshot.revision)
                    openTab(model.openRemote(identity, text, remote, ::save))
                }
            }
        }
    }

    fun showServerVersion(file: HollowIdeOpenFile) {
        val remote = file.remote ?: return
        val text = remote.conflict?.text(remote.path) ?: return
        openTab(model.openReadOnly("remote-project://${remote.session}/server-version/${remote.path}", text))
    }

    fun mutate(command: RemoteProjectCommand, completed: () -> Unit = {}) {
        RemoteProjectConnection.request(command) { reply ->
            when (reply) {
                RemoteProjectReply.Done -> completed()
                is RemoteProjectReply.Failure -> notifyRemoteProjectError(reply.error)
                else -> Unit
            }
        }
    }

    fun refreshFile(file: HollowIdeOpenFile) {
        val remote = file.remote ?: return
        if (!remote.online) return
        if (remote.refreshing || remote.saving) {
            remote.refreshAgain = true
            return
        }
        remote.refreshAgain = false
        remote.refreshing = true
        val requestedPath = remote.path
        RemoteProjectConnection.read(requestedPath, priority = true) { snapshot, error ->
            remote.refreshing = false
            if (remote.path == requestedPath && model.files[file.path] === file) {
                when {
                    snapshot != null -> if (snapshot.text(requestedPath) != null) remote.receive(file, snapshot) else {
                        remote.conflict = null
                        remote.error = RemoteProjectError.UNSUPPORTED
                    }

                    error != null -> {
                        if (error == RemoteProjectError.MISSING) {
                            remote.missing = true
                            remote.conflict = null
                        }
                        remote.error = error
                    }

                    else -> Unit
                }
            }
            if (remote.refreshAgain) refreshFile(file)
        }
    }

    private fun save(file: HollowIdeOpenFile): Boolean {
        val remote = file.remote ?: return false
        if (!file.dirty) return true
        if (!remote.writable) return false
        val text = file.text
        val bytes = encodeProjectText(remote.path, text)
        if (bytes.size > RemoteProjectFileLimit) {
            remote.error = RemoteProjectError.TOO_LARGE
            return false
        }
        val path = remote.path
        val revision = remote.revision
        remote.saving = true
        remote.error = null

        fun chunk(offset: Int) {
            val end = minOf(offset + RemoteProjectChunkSize, bytes.size)
            val command =
                RemoteProjectCommand.SaveChunk(path, revision, offset, bytes.size, bytes.copyOfRange(offset, end))
            RemoteProjectConnection.request(command, priority = offset > 0) { reply ->
                when (reply) {
                    is RemoteProjectReply.Uploading -> if (reply.nextOffset == end && end < bytes.size) {
                        chunk(end)
                        return@request
                    } else remote.error = RemoteProjectError.IO

                    is RemoteProjectReply.Saved -> {
                        remote.revision = reply.revision
                        file.markSavedIfText(text)
                        tabChanged(file)
                        if (path == ProjectProperties.PATH) RemoteProjectConnection.refreshProjectName()
                    }

                    is RemoteProjectReply.Failure -> {
                        remote.error = reply.error
                        if (reply.error == RemoteProjectError.CONFLICT || reply.error == RemoteProjectError.MISSING) {
                            remote.refreshAgain = true
                        }
                    }

                    else -> remote.error = RemoteProjectError.IO
                }
                remote.saving = false
                if (remote.refreshAgain) refreshFile(file)
            }
        }
        chunk(0)
        return false
    }

    private fun changed(change: RemoteProjectChange) {
        val path = change.path
        val destination = change.destination
        if (projectPathContains(path, ProjectProperties.PATH) || destination?.let {
                projectPathContains(
                    it,
                    ProjectProperties.PATH
                )
            } == true) RemoteProjectConnection.refreshProjectName()
        if (change.kind != RemoteProjectChange.Kind.SAVED) {
            directories.keys.filter { projectPathContains(path, it) }.forEach(directories::remove)
            if (change.kind != RemoteProjectChange.Kind.CREATED && projectPathContains(path, selectedPath)) {
                selectedPath = destination?.plus(selectedPath.removePrefix(path)) ?: projectPathParent(path)
            }
            listOfNotNull(projectPathParent(path), destination?.let(::projectPathParent)).distinct()
                .forEach(::refreshDirectory)
        }
        model.files.values.toList().forEach { file ->
            val remote = file.remote?.takeIf { it.online } ?: return@forEach
            when {
                change.kind == RemoteProjectChange.Kind.SAVED && remote.path == path -> refreshFile(file)
                projectPathContains(path, remote.path) && destination != null -> {
                    remote.path = destination + remote.path.removePrefix(path)
                    tabChanged(file)
                    refreshFile(file)
                }

                projectPathContains(path, remote.path) && change.kind == RemoteProjectChange.Kind.DELETED -> {
                    remote.missing = true
                    remote.conflict = null
                }

                projectPathContains(
                    path,
                    remote.path
                ) && change.kind == RemoteProjectChange.Kind.CREATED -> refreshFile(file)
            }
        }
    }

    private fun refreshDirectory(path: String) {
        val directory = directories[path] ?: return
        directory.loaded = false
        if (!directory.expanded) return
        if (directory.loading) directory.refreshAgain = true else load(path, directory)
    }

    private fun load(path: String, directory: Directory) {
        val entries = mutableListOf<RemoteProjectEntry>()
        directory.loading = true
        fun page(after: String?) {
            RemoteProjectConnection.request(RemoteProjectCommand.ListDirectory(path, after)) { reply ->
                if (directories[path] !== directory) return@request
                if (reply is RemoteProjectReply.Directory) {
                    entries.addAll(reply.entries)
                    if (reply.next != null && directory.expanded && !directory.refreshAgain) {
                        page(reply.next)
                        return@request
                    }
                    if (reply.next == null) {
                        directory.entries =
                            entries.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }, { it.name }))
                        directory.loaded = true
                    }
                } else if (reply is RemoteProjectReply.Failure && reply.error != RemoteProjectError.DISCONNECTED) {
                    notifyRemoteProjectError(reply.error)
                }
                directory.loading = false
                if (directory.refreshAgain) {
                    directory.refreshAgain = false
                    refreshDirectory(path)
                }
            }
        }
        page(null)
    }

    private fun reset() {
        selected = false
        selectedPath = ""
        directories.clear()
        pendingReads.clear()
        model.files.values.toList().filter { it.remote != null && !it.dirty }.forEach(model::removeRemote)
    }

    data class Node(
        val path: String,
        val label: String,
        val depth: Int,
        val directory: Boolean,
        val expanded: Boolean,
    ) {
        val parent: String get() = if (directory) path else projectPathParent(path)
    }

    private class Directory {
        var entries by mutableStateOf<List<RemoteProjectEntry>>(emptyList())
        var expanded by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var loaded = false
        var refreshAgain = false
    }
}

internal fun RemoteProjectError.message(): String {
    val key = "hollowengine.gui.ide.remote.error.${name.lowercase()}"
    return if (this == RemoteProjectError.TOO_LARGE) key.lang(RemoteProjectFileLimit / (1024 * 1024)) else key.lang
}

internal fun notifyRemoteProjectError(error: RemoteProjectError) = HollowNotifications.error(error.message())

internal fun RemoteProjectFile.text(path: String): String? {
    if (!bytes.isProbablyText()) return null
    if (path == ProjectProperties.PATH) return bytes.toString(Charsets.ISO_8859_1)
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

internal fun encodeProjectText(path: String, text: String): ByteArray = if (path == ProjectProperties.PATH) {
    buildString {
        text.forEach { character ->
            if (character.code <= 0xff) append(character) else append("\\u%04x".format(character.code))
        }
    }.toByteArray(Charsets.ISO_8859_1)
} else text.encodeToByteArray()
