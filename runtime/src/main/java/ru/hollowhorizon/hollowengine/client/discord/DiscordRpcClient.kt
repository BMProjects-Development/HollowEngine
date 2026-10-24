package ru.hollowhorizon.hollowengine.client.discord

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Owns IPC and retries. Minecraft state must be captured by the caller on the client thread. */
internal class DiscordRpcClient(
    private val connector: DiscordIpcConnector = LocalDiscordIpcConnector(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timing: Timing = Timing(),
    private val nowMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    private val onError: (Exception) -> Unit = {},
) : Closeable {
    internal data class Timing(
        val retry: Duration = 5.seconds,
        val update: Duration = 15.seconds,
        val timeout: Duration = 10.seconds,
        val poll: Duration = 250.milliseconds,
    )

    private val desired = MutableStateFlow<DiscordPresence?>(null)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pid = ProcessHandle.current().pid()

    init {
        scope.launch {
            desired.map { it?.applicationId }.distinctUntilChanged().collectLatest { applicationId ->
                if (applicationId == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    try {
                        val connection = timed { connector.connect() }
                        connection?.use { session(it, applicationId) }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        onError(exception)
                    }
                    delay(timing.retry)
                }
            }
        }
    }

    fun update(presence: DiscordPresence?) {
        desired.value = presence?.copy(activity = presence.activity?.let { it.copy(buttons = it.buttons.toList()) })
    }

    override fun close() = scope.cancel()

    private suspend fun session(connection: DiscordIpcConnection, applicationId: String) = coroutineScope {
        val incoming = Channel<Result<DiscordIpcFrame>>(16)
        val reader = launch {
            try {
                while (isActive) incoming.send(Result.success(connection.receive()))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                incoming.send(Result.failure(exception))
            } finally {
                incoming.close()
            }
        }
        try {
            timed {
                connection.send(DiscordIpcFrame.json(DiscordIpcFrame.HANDSHAKE, buildJsonObject {
                    put("v", 1)
                    put("client_id", applicationId)
                }))
                await(connection, incoming) {
                    it["cmd"]?.jsonPrimitive?.content == "DISPATCH" && it["evt"]?.jsonPrimitive?.content == "READY"
                }
            }
            var published = false
            var lastActivity: DiscordActivity? = null
            var lastSentAt = 0L
            while (isActive) {
                val presence = desired.value ?: return@coroutineScope
                if (presence.applicationId != applicationId) return@coroutineScope
                if ((!published || presence.activity != lastActivity) && (!published || nowMillis() - lastSentAt >= timing.update.inWholeMilliseconds)) {
                    val nonce = UUID.randomUUID().toString()
                    lastSentAt = nowMillis()
                    timed {
                        connection.send(DiscordIpcFrame.json(DiscordIpcFrame.FRAME, presence.command(pid, nonce)))
                        await(connection, incoming) {
                            it["cmd"]?.jsonPrimitive?.content == "SET_ACTIVITY" && it["nonce"]?.jsonPrimitive?.content == nonce
                        }
                    }
                    lastActivity = presence.activity
                    published = true
                }
                val frame = withTimeoutOrNull(timing.poll) { incoming.receive().getOrThrow() }
                if (frame != null) process(connection, frame)
            }
        } finally {
            try {
                connection.close()
            } finally {
                reader.cancel()
                incoming.cancel()
            }
        }
    }

    private suspend fun await(
        connection: DiscordIpcConnection,
        incoming: Channel<Result<DiscordIpcFrame>>,
        matches: (JsonObject) -> Boolean,
    ) {
        while (true) {
            val payload = process(connection, incoming.receive().getOrThrow())
            if (payload != null && matches(payload)) return
        }
    }

    private suspend fun process(connection: DiscordIpcConnection, frame: DiscordIpcFrame): JsonObject? {
        return when (frame.opcode) {
            DiscordIpcFrame.PING -> {
                timed { connection.send(DiscordIpcFrame(DiscordIpcFrame.PONG, frame.payload)) }
                null
            }

            DiscordIpcFrame.PONG -> null
            DiscordIpcFrame.CLOSE -> throw IOException("Discord closed the IPC session: ${frame.json()}")
            DiscordIpcFrame.FRAME -> frame.json().also {
                if (it["evt"]?.jsonPrimitive?.content == "ERROR") throw IOException("Discord RPC error: $it")
            }

            else -> throw IOException("Unexpected Discord IPC opcode: ${frame.opcode}")
        }
    }

    private suspend fun <T> timed(operation: suspend () -> T): T = try {
        withTimeout(timing.timeout) { operation() }
    } catch (exception: TimeoutCancellationException) {
        throw IOException("Discord IPC request timed out", exception)
    }
}
