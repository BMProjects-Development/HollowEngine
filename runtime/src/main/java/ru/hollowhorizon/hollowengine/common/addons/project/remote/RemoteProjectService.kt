package ru.hollowhorizon.hollowengine.common.addons.project.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.collections.HashSet
import kotlin.collections.copyInto
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.collections.isNotEmpty
import kotlin.collections.mapTo
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.removeAll
import kotlin.collections.set

/** The server thread owns request admission and publication. Disk operations run one at a time on IO. */
internal class RemoteProjectService {
    private val storage by lazy { RemoteProjectStorage() }
    private val queue = ArrayDeque<Request>()
    private val queuedPlayers = HashSet<UUID>()
    private val uploads = mutableMapOf<UUID, RemoteProjectUpload>()
    private val announcedEditors = mutableSetOf<ServerGamePacketListenerImpl>()
    private var running = false

    fun synchronizeAccess(server: MinecraftServer): Boolean {
        expireUploads()
        val players = server.playerList.players
        announcedEditors.retainAll(players.mapTo(HashSet()) { it.connection })
        var changed = false
        players.forEach { player ->
            val allowed = player.canAccessRemoteProject()
            val update =
                if (allowed) announcedEditors.add(player.connection) else announcedEditors.remove(player.connection)
            if (update) {
                RemoteProjectAccessPacket(allowed).send(player)
                if (player.isConnectedPlayer()) server.commands.sendCommands(player)
                changed = true
            }
        }
        return changed
    }

    fun submit(player: ServerPlayer, requestId: Long, command: RemoteProjectCommand) {
        val id = player.uuid
        if (!player.canAccessRemoteProject()) {
            uploads.remove(id)
            reply(player, requestId, RemoteProjectReply.Failure(RemoteProjectError.ACCESS))
            return
        }
        if (queue.size >= MaxQueuedRequests || !queuedPlayers.add(id)) {
            reply(player, requestId, RemoteProjectReply.Failure(RemoteProjectError.BUSY))
            return
        }
        queue.addLast(Request(player, requestId, command))
        if (running) return
        running = true
        player.server.coroutineScope.launch {
            try {
                while (queue.isNotEmpty()) {
                    val request = queue.removeFirst()
                    val outcome = process(request)
                    queuedPlayers.remove(request.player.uuid)
                    if (request.player.canAccessRemoteProject()) {
                        outcome.replies.forEach { RemoteProjectReplyPacket(request.id, it).send(request.player) }
                    } else reply(request.player, request.id, RemoteProjectReply.Failure(RemoteProjectError.ACCESS))
                    outcome.change?.let { change ->
                        val recipients = player.server.playerList.players.filter { recipient ->
                            (change.kind != RemoteProjectChange.Kind.SAVED || recipient !== request.player) && recipient.canAccessRemoteProject()
                        }
                        RemoteProjectChangedPacket(change).send(recipients)
                    }
                }
            } finally {
                queue.clear()
                queuedPlayers.clear()
                running = false
            }
        }
    }

    private suspend fun process(request: Request): Outcome {
        val player = request.player
        expireUploads()
        if (!player.canAccessRemoteProject()) return failure(RemoteProjectError.ACCESS)
        return try {
            val command = request.command
            if (command is RemoteProjectCommand.SaveChunk) {
                val current = uploads[player.uuid]
                val upload = if (command.offset == 0) {
                    if (current == null && uploads.size >= MaxActiveUploads) return failure(RemoteProjectError.BUSY)
                    RemoteProjectUpload(player, command).also { uploads[player.uuid] = it }
                } else current?.takeIf { it.player === player } ?: return failure(RemoteProjectError.INVALID_PATH)
                upload.append(command)
                if (!upload.complete) return Outcome(RemoteProjectReply.Uploading(upload.offset))
                uploads.remove(player.uuid)
                return withContext(Dispatchers.IO) {
                    Outcome(
                        storage.save(command.path, command.revision, upload.bytes),
                        RemoteProjectChange(command.path, RemoteProjectChange.Kind.SAVED),
                    )
                }
            }
            withContext(Dispatchers.IO) { execute(command) }
        } catch (error: RemoteProjectStorageException) {
            if (request.command is RemoteProjectCommand.SaveChunk) uploads.remove(player.uuid)
            failure(error.error)
        } catch (_: NoSuchFileException) {
            failure(RemoteProjectError.MISSING)
        } catch (_: FileAlreadyExistsException) {
            failure(RemoteProjectError.EXISTS)
        } catch (error: IOException) {
            HollowEngine.LOGGER.warn("Remote project operation failed", error)
            failure(RemoteProjectError.IO)
        }
    }

    private fun expireUploads() {
        uploads.values.removeAll { it.expired() || !it.player.canAccessRemoteProject() }
    }

    private fun execute(command: RemoteProjectCommand): Outcome = when (command) {
        RemoteProjectCommand.ProjectName -> Outcome(RemoteProjectReply.ProjectName(storage.projectName()))
        is RemoteProjectCommand.ListDirectory -> Outcome(storage.list(command.path, command.after))
        is RemoteProjectCommand.Read -> Outcome(storage.read(command.path).chunks())
        is RemoteProjectCommand.Create -> {
            storage.create(command.path, command.directory)
            changed(RemoteProjectChange(command.path, RemoteProjectChange.Kind.CREATED))
        }

        is RemoteProjectCommand.Move -> {
            storage.move(command.path, command.destination)
            changed(RemoteProjectChange(command.path, RemoteProjectChange.Kind.MOVED, command.destination))
        }

        is RemoteProjectCommand.Delete -> {
            storage.delete(command.path)
            changed(RemoteProjectChange(command.path, RemoteProjectChange.Kind.DELETED))
        }

        is RemoteProjectCommand.SaveChunk -> error("Save uploads must be assembled before disk IO")
    }

    private fun reply(player: ServerPlayer, id: Long, reply: RemoteProjectReply) {
        val result =
            if (player.canAccessRemoteProject()) reply else RemoteProjectReply.Failure(RemoteProjectError.ACCESS)
        RemoteProjectReplyPacket(id, result).send(player)
    }

    private fun changed(change: RemoteProjectChange) = Outcome(RemoteProjectReply.Done, change)
    private fun failure(error: RemoteProjectError) = Outcome(RemoteProjectReply.Failure(error))

    private data class Request(val player: ServerPlayer, val id: Long, val command: RemoteProjectCommand)
    private class Outcome(val replies: Sequence<RemoteProjectReply>, val change: RemoteProjectChange? = null) {
        constructor(reply: RemoteProjectReply, change: RemoteProjectChange? = null) : this(sequenceOf(reply), change)
    }
}

private class RemoteProjectUpload(val player: ServerPlayer, first: RemoteProjectCommand.SaveChunk) {
    private val path = first.path
    private val revision = first.revision
    private var expires = deadline()
    val bytes: ByteArray
    var offset = 0
        private set

    init {
        if (first.size !in 0..RemoteProjectFileLimit) throw RemoteProjectStorageException(RemoteProjectError.TOO_LARGE)
        if (path.length > RemoteProjectPathLimit || revision.length != RemoteProjectRevisionLength) {
            throw RemoteProjectStorageException(RemoteProjectError.INVALID_PATH)
        }
        bytes = ByteArray(first.size)
    }

    val complete: Boolean get() = offset == bytes.size

    fun expired(): Boolean = System.nanoTime() - expires >= 0

    fun append(chunk: RemoteProjectCommand.SaveChunk) {
        if (path != chunk.path || revision != chunk.revision || chunk.size != bytes.size || offset != chunk.offset || chunk.bytes.size != minOf(
                RemoteProjectChunkSize,
                bytes.size - offset
            )
        ) throw RemoteProjectStorageException(RemoteProjectError.INVALID_PATH)
        chunk.bytes.copyInto(bytes, offset)
        offset += chunk.bytes.size
        expires = deadline()
    }

    private fun deadline(): Long = System.nanoTime() + RemoteProjectRequestTimeoutMillis * 1_000_000
}

private const val MaxQueuedRequests = 64
private const val MaxActiveUploads = 16
