package ru.hollowhorizon.hollowengine.common.addons.project.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
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
import kotlin.collections.removeAll
import kotlin.collections.set

/** The server thread owns request admission and publication. Disk operations run one at a time on IO. */
internal class RemoteProjectService {
    private val storage by lazy { RemoteProjectStorage() }
    private val queue = ArrayDeque<Request>()
    private val queuedPlayers = HashSet<UUID>()
    private val uploads = mutableMapOf<UUID, RemoteProjectUpload>()
    private val announcedAccess = mutableMapOf<ServerGamePacketListenerImpl, RemoteProjectAccess>()
    private val grants = mutableMapOf<ServerGamePacketListenerImpl, RemoteProjectPolicy>()
    private val lastAttempts = mutableMapOf<ServerGamePacketListenerImpl, Long>()
    private var running = false

    fun hasAccess(player: ServerPlayer): Boolean = access(player) == RemoteProjectAccess.GRANTED

    private fun access(player: ServerPlayer): RemoteProjectAccess {
        val policy = player.remoteProjectPolicy()
        val grant = grants[player.connection]
        if (grant != null) {
            if (policy === grant) return RemoteProjectAccess.GRANTED
            grants.remove(player.connection)
        }
        return if (policy == null) RemoteProjectAccess.UNAVAILABLE else RemoteProjectAccess.LOCKED
    }

    fun synchronizeAccess(server: MinecraftServer): Boolean {
        expireUploads()
        val players = server.playerList.players
        val connections = players.mapTo(HashSet()) { it.connection }
        announcedAccess.keys.retainAll(connections)
        grants.keys.retainAll(connections)
        lastAttempts.keys.retainAll(connections)
        return players.fold(false) { changed, player -> announceAccess(player) || changed }
    }

    private fun announceAccess(player: ServerPlayer): Boolean {
        val state = access(player)
        val previous = announcedAccess[player.connection] ?: RemoteProjectAccess.UNAVAILABLE
        if (state == previous) return false
        announcedAccess[player.connection] = state
        RemoteProjectAccessPacket(state).send(player)
        val changed = state == RemoteProjectAccess.GRANTED || previous == RemoteProjectAccess.GRANTED
        if (changed && player.isConnectedPlayer()) player.server.commands.sendCommands(player)
        return changed
    }

    private fun synchronizePlayerAccess(player: ServerPlayer) {
        if (announceAccess(player)) ServerRuntimeState.contextOrNull(player.server)?.scriptRuns?.requestSynchronize()
    }

    private fun authenticate(player: ServerPlayer, requestId: Long, token: String) {
        val connection = player.connection
        val policy = player.remoteProjectPolicy()
        val now = System.nanoTime()
        val previous = lastAttempts[connection]
        val result = when {
            policy == null -> RemoteProjectReply.Failure(RemoteProjectError.ACCESS)
            previous != null && now - previous < AuthenticationCooldownNanos ->
                RemoteProjectReply.Failure(RemoteProjectError.BUSY)
            else -> {
                lastAttempts[connection] = now
                if (policy.accepts(token)) {
                    grants[connection] = policy
                    RemoteProjectReply.Done
                } else RemoteProjectReply.Failure(RemoteProjectError.ACCESS)
            }
        }
        synchronizePlayerAccess(player)
        RemoteProjectReplyPacket(requestId, result).send(player)
    }

    fun submit(player: ServerPlayer, requestId: Long, command: RemoteProjectCommand) {
        if (command is RemoteProjectCommand.Authenticate) {
            authenticate(player, requestId, command.token)
            return
        }
        val id = player.uuid
        val grant = currentGrant(player)
        if (grant == null) {
            uploads.remove(id)
            synchronizePlayerAccess(player)
            reply(player, requestId, RemoteProjectReply.Failure(RemoteProjectError.ACCESS))
            return
        }
        if (queue.size >= MaxQueuedRequests || !queuedPlayers.add(id)) {
            reply(player, requestId, RemoteProjectReply.Failure(RemoteProjectError.BUSY))
            return
        }
        queue.addLast(Request(player, requestId, command, grant))
        if (running) return
        running = true
        player.server.coroutineScope.launch {
            try {
                while (queue.isNotEmpty()) {
                    val request = queue.removeFirst()
                    val outcome = process(request)
                    queuedPlayers.remove(request.player.uuid)
                    if (currentGrant(request.player) === request.grant) {
                        outcome.replies.forEach { RemoteProjectReplyPacket(request.id, it).send(request.player) }
                    } else {
                        synchronizePlayerAccess(request.player)
                        reply(request.player, request.id, RemoteProjectReply.Failure(RemoteProjectError.ACCESS))
                    }
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

    private fun currentGrant(player: ServerPlayer): RemoteProjectPolicy? =
        if (hasAccess(player)) grants[player.connection] else null

    private suspend fun process(request: Request): Outcome {
        val player = request.player
        expireUploads()
        if (currentGrant(player) !== request.grant) return failure(RemoteProjectError.ACCESS)
        return try {
            val command = request.command
            if (command is RemoteProjectCommand.SaveChunk) {
                val current = uploads[player.uuid]
                val upload = if (command.offset == 0) {
                    if (current == null && uploads.size >= MaxActiveUploads) return failure(RemoteProjectError.BUSY)
                    RemoteProjectUpload(player, request.grant, command).also { uploads[player.uuid] = it }
                } else current?.takeIf { it.player === player && it.grant === request.grant }
                    ?: return failure(RemoteProjectError.INVALID_PATH)
                upload.append(command)
                if (!upload.complete) return Outcome(RemoteProjectReply.Uploading(upload.offset))
                uploads.remove(player.uuid)
                return executeAuthorized(request) {
                    Outcome(
                        storage.save(command.path, command.revision, upload.bytes),
                        RemoteProjectChange(command.path, RemoteProjectChange.Kind.SAVED),
                    )
                }
            }
            executeAuthorized(request) { execute(command) }
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

    private suspend fun executeAuthorized(request: Request, operation: () -> Outcome): Outcome =
        withContext(Dispatchers.IO) {
            if (RemoteProjectConfig.policy === request.grant) operation() else failure(RemoteProjectError.ACCESS)
        }

    private fun expireUploads() {
        uploads.values.removeAll { it.expired() || currentGrant(it.player) !== it.grant }
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

        is RemoteProjectCommand.SaveChunk, is RemoteProjectCommand.Authenticate ->
            error("This request must be handled on the server thread")
    }

    private fun reply(player: ServerPlayer, id: Long, reply: RemoteProjectReply) {
        val result =
            if (player.canAccessRemoteProject()) reply else RemoteProjectReply.Failure(RemoteProjectError.ACCESS)
        RemoteProjectReplyPacket(id, result).send(player)
    }

    private fun changed(change: RemoteProjectChange) = Outcome(RemoteProjectReply.Done, change)
    private fun failure(error: RemoteProjectError) = Outcome(RemoteProjectReply.Failure(error))

    private data class Request(
        val player: ServerPlayer,
        val id: Long,
        val command: RemoteProjectCommand,
        val grant: RemoteProjectPolicy,
    )
    private class Outcome(val replies: Sequence<RemoteProjectReply>, val change: RemoteProjectChange? = null) {
        constructor(reply: RemoteProjectReply, change: RemoteProjectChange? = null) : this(sequenceOf(reply), change)
    }
}

private class RemoteProjectUpload(
    val player: ServerPlayer,
    val grant: RemoteProjectPolicy,
    first: RemoteProjectCommand.SaveChunk,
) {
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
private const val AuthenticationCooldownNanos = 1_000_000_000L
