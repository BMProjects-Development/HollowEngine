package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.sounds.AudioStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat

/** RIFF/WAVE PCM and IEEE float, including WAVE_FORMAT_EXTENSIBLE. Output is interleaved PCM16. */
class WavSoundStream(input: InputStream) : AudioStream {
    private val input = DataInputStream(BufferedInputStream(input))
    private var remaining = 0L

    /** Whether the file never says how long its data is, which then runs to the end of the file. */
    private var open = false
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
                    // A file written as it was recorded may say its data never ends; it ends with the file.
                    open = size == UNKNOWN_SIZE
                    require(open || size % blockAlign == 0L) { "Incomplete WAV sample frame" }
                    remaining = if (open) Long.MAX_VALUE else size
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
        val wanted = minOf(maxOf(1, size / (channels * 2)).toLong(), remaining / blockAlign).toInt()
        val bytes = input.readNBytes(wanted * blockAlign)
        if (!open && bytes.size < wanted * blockAlign) throw EOFException("WAV data ends before its declared size")
        val frames = bytes.size / blockAlign
        remaining = if (frames < wanted) 0L else remaining - frames.toLong() * blockAlign
        val samples = ByteBuffer.wrap(bytes, 0, frames * blockAlign).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) {
            val sample = if (encoding == 3) {
                val value = if (bits == 32) samples.float.toDouble() else samples.double
                if (value.isFinite()) (value.coerceIn(-1.0, 1.0) * 32767.0).toInt() else 0
            } else when (bits) {
                8 -> ((samples.get().toInt() and 0xff) - 128) shl 8
                16 -> samples.short.toInt()
                24 -> ((samples.get().toInt() and 0xff) or ((samples.get().toInt() and 0xff) shl 8) or
                    (samples.get().toInt() shl 16)) shr 8
                else -> samples.int shr 16
            }
            output.putShort(sample.toShort())
        }
        return output.flip()
    }

    /** Moves [seconds] into the data without reading it, or to its end when it is shorter. */
    fun skipSeconds(seconds: Double) {
        val frames = (seconds.coerceAtLeast(0.0) * sampleRate).toLong().coerceAtMost(remaining / blockAlign)
        input.skipNBytes(frames * blockAlign)
        if (!open) remaining -= frames * blockAlign
    }

    private fun fourCC(): String = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
    private fun int32(): Int = Integer.reverseBytes(input.readInt())
    private fun uint32(): Long = int32().toLong() and 0xffffffffL

    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private companion object {
        /** The data size a WAV written to a pipe carries, since it is not known until the recording stops. */
        const val UNKNOWN_SIZE = 0xFFFFFFFFL
    }
}
