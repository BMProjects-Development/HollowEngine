package ru.hollowhorizon.hollowengine.client.discord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

internal data class DiscordIpcFrame(val opcode: Int, val payload: ByteArray) {
    fun json(): JsonObject = try {
        Json.parseToJsonElement(payload.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: throw IOException("Discord IPC payload is not an object")
    } catch (exception: IllegalArgumentException) {
        throw IOException("Invalid Discord IPC JSON", exception)
    }

    companion object {
        const val HANDSHAKE = 0
        const val FRAME = 1
        const val CLOSE = 2
        const val PING = 3
        const val PONG = 4
        private const val HEADER_SIZE = 8
        private const val MAX_PAYLOAD_SIZE = 64 * 1024

        fun json(opcode: Int, payload: JsonObject) = DiscordIpcFrame(opcode, payload.toString().encodeToByteArray())

        fun read(channel: ReadableByteChannel): DiscordIpcFrame {
            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            readFully(channel, header)
            header.flip()
            val opcode = header.int
            val size = header.int
            if (opcode !in HANDSHAKE..PONG || size !in 0..MAX_PAYLOAD_SIZE) {
                throw IOException("Invalid Discord IPC header: opcode=$opcode, size=$size")
            }
            val payload = ByteBuffer.allocate(size)
            readFully(channel, payload)
            return DiscordIpcFrame(opcode, payload.array())
        }

        private fun readFully(channel: ReadableByteChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw EOFException("Discord IPC connection closed")
            }
        }
    }

    fun write(channel: WritableByteChannel) {
        if (opcode !in HANDSHAKE..PONG || payload.size > MAX_PAYLOAD_SIZE) {
            throw IOException("Discord IPC frame exceeds protocol limits")
        }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(opcode).putInt(payload.size).put(payload).flip()
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DiscordIpcFrame

        if (opcode != other.opcode) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
