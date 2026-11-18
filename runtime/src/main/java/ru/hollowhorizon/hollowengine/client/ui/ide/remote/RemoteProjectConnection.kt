package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import ru.hollowhorizon.hollowengine.common.addons.project.remote.*
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent

/** At most one request is outstanding. Replies from an earlier connection have different IDs. */
@ClientOnly
internal object RemoteProjectConnection {
    private val queue = ArrayDeque<Pending>()
    private var pending: Pending? = null
    private var connection: ClientPacketListener? = null
    private var sequence = 0L

    var projectName by mutableStateOf<String?>(null)
        private set
    var access by mutableStateOf(RemoteProjectAccess.UNAVAILABLE)
        private set
    var session by mutableStateOf(0L)
        private set

    var onReset: (() -> Unit)? = null
    var onChange: ((RemoteProjectChange) -> Unit)? = null

    @SubscribeEvent
    @ClientOnly
    fun tick(event: TickEvent.Client) {
        synchronize(event.minecraft)
        if (connection == null) return
        val now = System.nanoTime()
        if (pending?.let { now - it.deadline >= 0 } == true) finish(RemoteProjectReply.Failure(RemoteProjectError.TIMEOUT))
    }

    fun receiveAccess(next: RemoteProjectAccess) {
        synchronize(Minecraft.getInstance())
        if (connection == null) return
        access = next
        if (next != RemoteProjectAccess.GRANTED) revoke()
        else if (projectName == null) {
            projectName = "HollowEngine"
            refreshProjectName()
        }
    }

    fun authenticate(token: String, completed: (RemoteProjectReply) -> Unit) {
        enqueue(RemoteProjectCommand.Authenticate(token), false, completed)
    }

    fun refreshProjectName() {
        request(RemoteProjectCommand.ProjectName) { reply ->
            if (reply is RemoteProjectReply.ProjectName && projectName != null) projectName = reply.value
        }
    }

    fun request(command: RemoteProjectCommand, priority: Boolean = false, completed: (RemoteProjectReply) -> Unit) {
        enqueue(command, priority, completed)
    }

    fun read(path: String, priority: Boolean = false, completed: (RemoteProjectFile?, RemoteProjectError?) -> Unit) {
        enqueue(RemoteProjectCommand.Read(path), priority, { reply ->
            completed(null, (reply as? RemoteProjectReply.Failure)?.error ?: RemoteProjectError.IO)
        }, { file -> completed(file, null) })
    }

    private fun enqueue(
        command: RemoteProjectCommand,
        priority: Boolean,
        completed: (RemoteProjectReply) -> Unit,
        fileCompleted: ((RemoteProjectFile) -> Unit)? = null,
    ) {
        synchronize(Minecraft.getInstance())
        if (connection == null || !canSend(command)) {
            completed(RemoteProjectReply.Failure(if (connection == null) RemoteProjectError.DISCONNECTED else RemoteProjectError.ACCESS))
            return
        }
        val request = Pending(++sequence, command, completed, fileCompleted)
        if (priority) queue.addFirst(request) else queue.addLast(request)
        dispatch()
    }

    fun receive(id: Long, reply: RemoteProjectReply) {
        synchronize(Minecraft.getInstance())
        val request = pending?.takeIf { it.id == id } ?: return
        if (reply is RemoteProjectReply.FileChunk) {
            receiveFileChunk(request, reply)
            return
        }
        finish(reply)
    }

    private fun receiveFileChunk(request: Pending, chunk: RemoteProjectReply.FileChunk) {
        val completed = request.fileCompleted ?: return
        val file = try {
            val download = request.download ?: RemoteProjectDownload(chunk).also { request.download = it }
            download.append(chunk)
        } catch (_: IllegalArgumentException) {
            finish(RemoteProjectReply.Failure(RemoteProjectError.IO))
            return
        }
        if (file == null) {
            request.deadline = requestDeadline()
            return
        }
        pending = null
        completed(file)
        dispatch()
    }

    fun receive(change: RemoteProjectChange) {
        synchronize(Minecraft.getInstance())
        if (projectName != null) onChange?.invoke(change)
    }

    private fun revoke() {
        if (projectName == null) return
        projectName = null
        session++
        resetRequests(RemoteProjectError.ACCESS)
    }

    private fun synchronize(minecraft: Minecraft) {
        val current = minecraft.connection.takeUnless { minecraft.hasSingleplayerServer() || minecraft.player == null }
        if (connection === current) return
        connection = current
        session++
        access = RemoteProjectAccess.UNAVAILABLE
        projectName = null
        resetRequests(RemoteProjectError.DISCONNECTED)
    }

    private fun resetRequests(error: RemoteProjectError) {
        val cancelled = listOfNotNull(pending) + queue
        pending = null
        queue.clear()
        onReset?.invoke()
        val failure = RemoteProjectReply.Failure(error)
        cancelled.forEach { it.completed(failure) }
    }

    private fun finish(reply: RemoteProjectReply) {
        val request = pending ?: return
        pending = null
        request.completed(reply)
        dispatch()
    }

    private fun dispatch() {
        while (pending == null && queue.isNotEmpty()) {
            val request = queue.removeFirst()
            if (!canSend(request.command)) {
                request.completed(RemoteProjectReply.Failure(RemoteProjectError.ACCESS))
                continue
            }
            pending = request
            request.deadline = requestDeadline()
            RemoteProjectRequestPacket(request.id, request.command).send()
            return
        }
    }

    private fun canSend(command: RemoteProjectCommand): Boolean =
        if (command is RemoteProjectCommand.Authenticate) access == RemoteProjectAccess.LOCKED else projectName != null

    private class Pending(
        val id: Long,
        val command: RemoteProjectCommand,
        val completed: (RemoteProjectReply) -> Unit,
        val fileCompleted: ((RemoteProjectFile) -> Unit)?,
        var deadline: Long = 0L,
    ) {
        var download: RemoteProjectDownload? = null
    }

    private fun requestDeadline(): Long = System.nanoTime() + RemoteProjectRequestTimeoutMillis * 1_000_000
}
