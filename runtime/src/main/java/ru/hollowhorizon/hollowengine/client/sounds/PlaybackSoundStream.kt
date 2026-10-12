package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.sounds.AudioStream
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import javax.sound.sampled.AudioFormat

/** Reopens the decoder at cycle boundaries; fades are applied to samples rather than decode time. */
class PlaybackSoundStream(
    private val open: () -> AudioStream,
    private val settings: SoundPlayback,
    start: Double = 0.0,
    openAt: ((Double) -> AudioStream?)? = null,
) : AudioStream {
    private var stream: AudioStream
    private val inputFormat: AudioFormat
    private val channels: Int
    private val sampleRate: Float
    private val chunks = ArrayDeque<ByteBuffer>()
    private var bufferedFrames = 0L
    private var deliveredFrames = 0L

    /** Frames the decoder gave in this play, so a file with none in it ends rather than reopens forever. */
    private var cycleFrames = 0L
    private var completedCycles = 0L
    private var eof = false
    private var finished = false
    @Volatile
    private var closed = false
    private val fadeInFrames: Long
    private val fadeOutFrames: Long

    /** Frames still to be read through and dropped before anything is heard. */
    private var skipFrames = 0L

    /** The start, kept while the decoder that went to it has given nothing, in case it lies past the first play. */
    private var pastEnd = 0L

    init {
        val seconds = start.coerceAtLeast(0.0)
        val sought = if (seconds > 0.0) openAt?.invoke(seconds) else null
        stream = sought ?: open()
        inputFormat = stream.format
        channels = inputFormat.channels
        sampleRate = inputFormat.sampleRate
        if (inputFormat.encoding != AudioFormat.Encoding.PCM_SIGNED || inputFormat.sampleSizeInBits != 16 || channels !in 1..2 || inputFormat.isBigEndian || !sampleRate.isFinite() || sampleRate <= 0f || inputFormat.frameSize != channels * 2) {
            stream.close()
            error("Sound decoders must return little-endian PCM16 mono or stereo")
        }
        fadeInFrames = (settings.fadeIn * sampleRate).toLong()
        fadeOutFrames = (settings.fadeOut * sampleRate).toLong()
        val startFrames = (seconds * sampleRate).toLong()
        if (sought != null) {
            deliveredFrames = startFrames
            pastEnd = startFrames
        } else {
            skipFrames = startFrames
        }
        if (startFrames > 0L) deliveredFrames = maxOf(deliveredFrames, fadeInFrames)
    }

    override fun getFormat() = AudioFormat(sampleRate, 16, channels, true, false)

    @Synchronized
    override fun read(size: Int): ByteBuffer {
        require(size > 0)
        val output = ByteBuffer.allocateDirect(maxOf(channels * 2, size / (channels * 2) * channels * 2))
            .order(ByteOrder.LITTLE_ENDIAN)
        while (output.remaining() >= channels * 2 && !closed && !finished) {
            val finalCycle = !settings.repeat.hasNext(completedCycles + 1)
            val tail = if (settings.fadeEachCycle || finalCycle) fadeOutFrames else 0L
            while (!eof && bufferedFrames <= tail + skipFrames) {
                val chunk = stream.read(16_384).order(ByteOrder.LITTLE_ENDIAN)
                if (!chunk.hasRemaining()) eof = true else {
                    if (chunk.remaining() % (channels * 2) != 0) throw IOException("Unaligned PCM frame")
                    chunks.addLast(chunk)
                    bufferedFrames += chunk.remaining() / (channels * 2)
                    cycleFrames += chunk.remaining() / (channels * 2)
                    pastEnd = 0L
                }
            }
            if (skipFrames > 0L && bufferedFrames > 0L) {
                skip()
                continue
            }
            if (bufferedFrames == 0L) {
                if (cycleFrames == 0L && pastEnd > 0L) {
                    reopen()
                    skipFrames = pastEnd
                    pastEnd = 0L
                    continue
                }
                completedCycles++
                if (cycleFrames == 0L || !settings.repeat.hasNext(completedCycles)) {
                    finished = true
                    break
                }
                reopen()
                continue
            }
            var gain = 1.0
            if ((settings.fadeEachCycle || completedCycles == 0L) && fadeInFrames > 0) {
                gain = minOf(gain, deliveredFrames.toDouble() / fadeInFrames)
            }
            if (eof && tail > 0) gain = minOf(gain, (bufferedFrames - 1).toDouble() / tail)
            val chunk = chunks.first
            repeat(channels) { output.putShort((chunk.short * gain).toInt().toShort()) }
            if (!chunk.hasRemaining()) chunks.removeFirst()
            bufferedFrames--
            deliveredFrames++
        }
        return output.flip()
    }

    /** Opens the file again at its start, for the next play. */
    private fun reopen() {
        stream.close()
        stream = open()
        require(stream.format.matches(inputFormat)) { "Sound format changed between cycles" }
        deliveredFrames = 0
        cycleFrames = 0
        eof = false
    }

    /** Drops what is buffered of the frames before the start, a chunk at a time. */
    private fun skip() {
        while (skipFrames > 0L && chunks.isNotEmpty()) {
            val chunk = chunks.first
            val frames = minOf(skipFrames, (chunk.remaining() / (channels * 2)).toLong())
            chunk.position(chunk.position() + (frames * channels * 2).toInt())
            if (!chunk.hasRemaining()) chunks.removeFirst()
            bufferedFrames -= frames
            deliveredFrames += frames
            skipFrames -= frames
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stream.close()
    }
}
