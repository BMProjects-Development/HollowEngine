package ru.hollowhorizon.hollowengine.common.utils

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

interface Uint8Buffer : Buffer {
    operator fun get(i: Int): UByte
    operator fun set(i: Int, value: UByte)
    fun put(value: UByte): Uint8Buffer
    fun put(data: ByteArray, offset: Int, len: Int): Uint8Buffer
    fun put(data: Uint8Buffer): Uint8Buffer

    operator fun plusAssign(value: UByte) { put(value) }
    operator fun plusAssign(value: Byte) { put(value) }
    fun put(value: Byte): Uint8Buffer = put(value.toUByte())
    fun put(data: ByteArray): Uint8Buffer = put(data, 0, data.size)

    fun toArray(): ByteArray = ByteArray(capacity) { get(it).toByte() }
}

fun Uint8Buffer(capacity: Int, isAutoLimit: Boolean = false) = Uint8BufferImpl(
    buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder()),
    isAutoLimit = isAutoLimit
)

fun Uint8Buffer(data: ByteArray): Uint8BufferImpl {
    val buf = Uint8BufferImpl(ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()), false)
    buf.put(data)
    return buf
}

fun Uint8Buffer.inflate(): Uint8Buffer {
    return Uint8Buffer(GZIPInputStream(ByteArrayInputStream(toArray())).readBytes())
}

fun Uint8Buffer.decodeToString(): String {
    return toArray().decodeToString()
}

interface Buffer {
    val capacity: Int
    var position: Int
    var limit: Int
    var isAutoLimit: Boolean

    val remaining: Int
        get() = capacity - position

    fun clear()

}