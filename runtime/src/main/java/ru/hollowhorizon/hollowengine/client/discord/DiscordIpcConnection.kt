package ru.hollowhorizon.hollowengine.client.discord

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.ByteChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface DiscordIpcConnection : Closeable {
    suspend fun receive(): DiscordIpcFrame
    suspend fun send(frame: DiscordIpcFrame)
}

internal fun interface DiscordIpcConnector {
    suspend fun connect(): DiscordIpcConnection?
}

/** JDK transports only: named pipes on Windows, Unix-domain sockets on Linux/macOS. */
internal class LocalDiscordIpcConnector(
    private val endpointProvider: () -> List<String> = { endpoints() },
) : DiscordIpcConnector {
    override suspend fun connect(): DiscordIpcConnection? {
        val opening = AtomicReference<Closeable?>()
        return discordIo({ opening.getAndSet(null)?.close() }) {
            for (endpoint in endpointProvider()) {
                try {
                    val channel = if (endpoint.startsWith("\\\\.\\pipe\\")) {
                        NamedPipeChannel(
                            AsynchronousFileChannel.open(
                                Path.of(endpoint), StandardOpenOption.READ, StandardOpenOption.WRITE,
                            )
                        ).also { opening.set(it) }
                    } else {
                        SocketChannel.open(StandardProtocolFamily.UNIX).also {
                            opening.set(it)
                            it.connect(UnixDomainSocketAddress.of(endpoint))
                        }
                    }
                    return@discordIo ChannelDiscordIpcConnection(channel)
                } catch (_: IOException) {
                    opening.getAndSet(null)?.close()
                } catch (_: UnsupportedOperationException) {
                    opening.getAndSet(null)?.close()
                    return@discordIo null
                }
            }
            null
        }
    }

    companion object {
        internal fun endpoints(
            osName: String = System.getProperty("os.name"),
            environment: Map<String, String> = System.getenv(),
        ): List<String> {
            if (osName.startsWith("Windows", ignoreCase = true)) {
                // NIO accepts the Win32 device prefix; the extended-length \\?\pipe prefix is not a Path.
                return (0..9).map { "\\\\.\\pipe\\discord-ipc-$it" }
            }
            val roots = listOf(
                "XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"
            ).mapNotNull { environment[it]?.takeIf(String::isNotBlank) }.plus("/tmp").distinct()
            return roots.flatMap { root -> (0..9).map { Path.of(root, "discord-ipc-$it").toString() } }
        }
    }
}

private class ChannelDiscordIpcConnection(private val channel: ByteChannel) : DiscordIpcConnection {
    override suspend fun receive(): DiscordIpcFrame = discordIo(::close) { DiscordIpcFrame.read(channel) }
    override suspend fun send(frame: DiscordIpcFrame) = discordIo(::close) { frame.write(channel) }
    override fun close() = channel.close()
}

/** Overlapped Windows I/O permits simultaneous reads/writes and cancellation of pending pipe I/O. */
private class NamedPipeChannel(private val pipe: AsynchronousFileChannel) : ByteChannel {
    override fun isOpen(): Boolean = pipe.isOpen
    override fun read(destination: ByteBuffer): Int = await(pipe.read(destination, 0))
    override fun write(source: ByteBuffer): Int = await(pipe.write(source, 0))
    override fun close() = pipe.close()

    private fun await(operation: Future<Int>): Int = try {
        operation.get()
    } catch (exception: ExecutionException) {
        throw IOException("Discord named pipe I/O failed", exception.cause)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Discord named pipe I/O interrupted", exception)
    }
}

/** Closing a channel on cancellation releases pending socket/overlapped pipe I/O. */
private suspend fun <T> discordIo(close: () -> Unit, operation: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val thread = Thread.ofVirtual().name("HollowEngine-Discord-IPC").unstarted {
            try {
                if (continuation.isActive) {
                    val result = operation()
                    if (continuation.isActive) continuation.resume(result) else close()
                }
            } catch (exception: Exception) {
                continuation.resumeWithException(exception)
            }
        }
        continuation.invokeOnCancellation {
            try {
                close()
            } catch (_: IOException) {
                // The handle may already have been closed by the session or the other I/O operation.
            } finally {
                thread.interrupt()
            }
        }
        thread.start()
    }
