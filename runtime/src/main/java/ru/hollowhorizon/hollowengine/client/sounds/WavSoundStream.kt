package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.sounds.AudioStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import java.lang.Long.reverseBytes

/** RIFF/WAVE PCM and IEEE float, including WAVE_FORMAT_EXTENSIBLE. Output is interleaved PCM16. */
class WavSoundStream(input: InputStream) : AudioStream {
    private val input = DataInputStream(BufferedInputStream(input))
    private var remaining = 0L
    private var channels = 0
    private var sampleRate = 0
    private var bits = 0
    private var encoding = 0
    private var blockAlign = 0
    private var closed = false

    init {
        try { readHeader() } catch (error: Throwable) { this.input.close(); throw error }
    }

    override fun getFormat() = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

    private fun readHeader() {
        require(fourCC() == "RIFF") { "Expected RIFF WAV container" }
        uint32()
        require(fourCC() == "WAVE") { "Expected WAVE format" }
        var hasFormat = false
        while (true) {
            val kind = fourCC()
            val size = uint32()
            when (kind) {
                "fmt " -> {
                    require(size in 16..65_536) { "Invalid WAV format chunk size: $size" }
                    val data = ByteArray(size.toInt()).also(input::readFully)
                    val format = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    encoding = format.short.toInt() and 0xffff
                    channels = format.short.toInt() and 0xffff
                    sampleRate = format.int
                    format.int // byte rate
                    blockAlign = format.short.toInt() and 0xffff
                    bits = format.short.toInt() and 0xffff
                    if (encoding == 0xfffe) {
                        require(data.size >= 40 && (format.getShort(16).toInt() and 0xffff) >= 22)
                        val validBits = format.getShort(18).toInt() and 0xffff
                        require(validBits in 0..bits) { "Invalid WAV valid bits" }
                        encoding = format.getInt(24)
                        require(format.getInt(28) == 0x00100000 && format.getInt(32) == 0xAA000080.toInt() &&
                            format.getInt(36) == 0x719B3800) { "Unsupported WAV subtype GUID" }
                    }
                    require(channels in 1..2 && sampleRate > 0) { "Unsupported WAV channels or sample rate" }
                    require(encoding == 1 && bits in setOf(8, 16, 24, 32) || encoding == 3 && bits in setOf(32, 64)) {
                        "Unsupported WAV encoding=$encoding, bits=$bits"
                    }
                    require(blockAlign == channels * (bits / 8)) { "Invalid WAV block alignment" }
                    hasFormat = true
                }
                "data" -> {
                    require(hasFormat) { "WAV data appears before fmt" }
                    require(size % blockAlign == 0L) { "Incomplete WAV sample frame" }
                    remaining = size
                    return
                }
                else -> input.skipNBytes(size)
            }
            if (size and 1L != 0L) input.skipNBytes(1)
        }
    }

    override fun read(size: Int): ByteBuffer {
        check(!closed) { "WAV stream is closed" }
        require(size > 0)
        val frames = minOf(maxOf(1, size / (channels * 2)).toLong(), remaining / blockAlign).toInt()
        val output = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) {
            val sample = if (encoding == 3) {
                val value = if (bits == 32) Float.fromBits(int32()).toDouble() else Double.fromBits(int64())
                if (value.isFinite()) (value.coerceIn(-1.0, 1.0) * 32767.0).toInt() else 0
            } else when (bits) {
                8 -> (input.readUnsignedByte() - 128) shl 8
                16 -> (input.readUnsignedByte() or (input.readByte().toInt() shl 8))
                24 -> (input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or
                    (input.readByte().toInt() shl 16)) shr 8
                else -> int32() shr 16
            }
            output.putShort(sample.toShort())
        }
        remaining -= frames.toLong() * blockAlign
        return output.flip()
    }

    private fun fourCC(): String = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
    private fun int32(): Int = Integer.reverseBytes(input.readInt())
    private fun int64(): Long = reverseBytes(input.readLong())
    private fun uint32(): Long = int32().toLong() and 0xffffffffL

    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }
}
