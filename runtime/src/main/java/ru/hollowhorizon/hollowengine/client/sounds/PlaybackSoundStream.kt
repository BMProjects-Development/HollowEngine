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
    private val spatial: Boolean,
) : AudioStream {
    private var stream = open()
    private val inputFormat = stream.format
    private val inputChannels = inputFormat.channels
    private val channels = if (spatial) 1 else inputChannels
    private val sampleRate = inputFormat.sampleRate
    private val chunks = ArrayDeque<ByteBuffer>()
    private var bufferedFrames = 0L
    private var deliveredFrames = 0L
    private var completedCycles = 0L
    private var eof = false
    private var finished = false
    @Volatile
    private var closed = false
    private val fadeInFrames = (settings.fadeIn * sampleRate).toLong()
    private val fadeOutFrames = (settings.fadeOut * sampleRate).toLong()

    init {
        if (inputFormat.encoding != AudioFormat.Encoding.PCM_SIGNED || inputFormat.sampleSizeInBits != 16 || inputChannels !in 1..2 || inputFormat.isBigEndian || !sampleRate.isFinite() || sampleRate <= 0f || inputFormat.frameSize != inputChannels * 2) {
            stream.close()
            error("Sound decoders must return little-endian PCM16 mono or stereo")
        }
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
            while (!eof && bufferedFrames <= tail) {
                val chunk = stream.read(16_384).order(ByteOrder.LITTLE_ENDIAN)
                if (!chunk.hasRemaining()) eof = true else {
                    if (chunk.remaining() % (inputChannels * 2) != 0) throw IOException("Unaligned PCM frame")
                    chunks.addLast(chunk)
                    bufferedFrames += chunk.remaining() / (inputChannels * 2)
                }
            }
            if (bufferedFrames == 0L) {
                completedCycles++
                if (deliveredFrames == 0L || !settings.repeat.hasNext(completedCycles)) {
                    finished = true
                    break
                }
                stream.close()
                stream = open()
                require(stream.format.matches(inputFormat)) { "Sound format changed between cycles" }
                deliveredFrames = 0
                eof = false
                continue
            }
            var gain = 1.0
            if ((settings.fadeEachCycle || completedCycles == 0L) && fadeInFrames > 0) {
                gain = minOf(gain, deliveredFrames.toDouble() / fadeInFrames)
            }
            if (eof && tail > 0) gain = minOf(gain, (bufferedFrames - 1).toDouble() / tail)
            val chunk = chunks.first
            if (spatial && inputChannels == 2) {
                val mono = (chunk.short.toInt() + chunk.short.toInt()) / 2
                output.putShort((mono * gain).toInt().toShort())
            } else repeat(channels) { output.putShort((chunk.short * gain).toInt().toShort()) }
            if (!chunk.hasRemaining()) chunks.removeFirst()
            bufferedFrames--
            deliveredFrames++
        }
        return output.flip()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stream.close()
    }
}
