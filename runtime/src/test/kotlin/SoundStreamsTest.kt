import net.minecraft.client.sounds.AudioStream
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.sounds.PlaybackSoundStream
import ru.hollowhorizon.hollowengine.client.sounds.WavSoundStream
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundRepeat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.lang.Short.reverseBytes as reverseShortBytes


class SoundStreamsTest {
    @Test
    fun `wav skips padded metadata and streams partial reads`() {
        val bytes = wav(shortArrayOf(-32768, -1, 0, 32767), junk = true)
        WavSoundStream(ByteArrayInputStream(bytes)).use { stream ->
            assertEquals(1, stream.format.channels)
            assertEquals(listOf(-32768, -1, 0, 32767), readSamples(stream, 2))
        }
    }

    @Test
    fun `wav rejects truncated sample data`() {
        val bytes = wav(shortArrayOf(100, 200)).dropLast(1).toByteArray()
        WavSoundStream(ByteArrayInputStream(bytes)).use { stream ->
            assertFailsWith<EOFException> { readSamples(stream, 20) }
        }
    }

    @Test
    fun `fixed repeats fill buffers without gaps and release each decoder`() {
        var opened = 0
        var closed = 0
        val stream = PlaybackSoundStream({
            opened++
            pcm(shortArrayOf(100, 200, 300)) { closed++ }
        }, SoundPlayback(repeat = SoundRepeat.Count(3)), false)
        assertEquals(List(3) { listOf(100, 200, 300) }.flatten(), readSamples(stream, 10))
        stream.close(); stream.close()
        assertEquals(3, opened)
        assertEquals(3, closed)
    }

    @Test
    fun `empty infinite source terminates instead of spinning`() {
        PlaybackSoundStream({ pcm(shortArrayOf()) }, SoundPlayback(repeat = SoundRepeat.Forever), false).use {
            assertEquals(emptyList(), readSamples(it, 20))
        }
    }

    @Test
    fun `fades use sample time and preserve output length`() {
        PlaybackSoundStream(
            { pcm(ShortArray(8) { 1000 }) },
            SoundPlayback(fadeIn = 0.004f, fadeOut = 0.004f),
            false
        ).use {
            assertEquals(listOf(0, 250, 500, 750, 750, 500, 250, 0), readSamples(it, 6))
        }
    }

    @Test
    fun `spatial playback downmixes stereo without clipping`() {
        PlaybackSoundStream(
            { pcm(shortArrayOf(32767, 32767, -32768, 32767), channels = 2) },
            SoundPlayback(),
            true
        ).use {
            assertEquals(1, it.format.channels)
            assertEquals(listOf(32767, 0), readSamples(it, 8))
        }
    }

    @Test
    fun `repeat envelopes cover the whole playback or each cycle as configured`() {
        fun samples(eachCycle: Boolean) = PlaybackSoundStream(
            { pcm(ShortArray(4) { 1000 }) },
            SoundPlayback(SoundRepeat.Count(2), fadeIn = 0.002f, fadeOut = 0.002f, fadeEachCycle = eachCycle),
            false,
        ).use { readSamples(it, 6) }
        assertEquals(listOf(0, 500, 1000, 1000, 1000, 1000, 500, 0), samples(false))
        assertEquals(listOf(0, 500, 500, 0, 0, 500, 500, 0), samples(true))
    }

    @Test
    fun `looping source reads only requested frames without a whole-file cache`() {
        var reads = 0
        val source = object : AudioStream {
            override fun getFormat() = AudioFormat(1000f, 16, 1, true, false)
            override fun read(size: Int): ByteBuffer {
                reads++
                return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(size / 2) { putShort(1) }; flip()
                }
            }

            override fun close() = Unit
        }
        PlaybackSoundStream({ source }, SoundPlayback(repeat = SoundRepeat.Forever), false).use {
            assertEquals(100, it.read(200).remaining() / 2)
            assertEquals(1, reads)
        }
    }

    private fun readSamples(stream: AudioStream, size: Int): List<Int> = buildList {
        while (true) {
            val buffer = stream.read(size).order(ByteOrder.LITTLE_ENDIAN)
            if (!buffer.hasRemaining()) break
            while (buffer.hasRemaining()) add(buffer.short.toInt())
        }
    }

    private fun pcm(samples: ShortArray, channels: Int = 1, closed: () -> Unit = {}): AudioStream =
        object : AudioStream {
            var position = 0
            var disposed = false
            override fun getFormat() = AudioFormat(1000f, 16, channels, true, false)
            override fun read(size: Int): ByteBuffer {
                check(!disposed)
                val count = minOf(size / 2, samples.size - position)
                return ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(count) { putShort(samples[position++]) }; flip()
                }
            }

            override fun close() {
                if (!disposed) {
                    disposed = true; closed()
                }
            }
        }

    private fun wav(samples: ShortArray, junk: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        val data = DataOutputStream(body)
        fun int(value: Int) = data.writeInt(Integer.reverseBytes(value))
        fun short(value: Int) = data.writeShort(reverseShortBytes(value.toShort()).toInt())
        data.writeBytes("WAVE")
        if (junk) {
            data.writeBytes("JUNK"); int(3); data.write(byteArrayOf(1, 2, 3, 0))
        }
        data.writeBytes("fmt "); int(16); short(1); short(1); int(1000); int(2000); short(2); short(16)
        data.writeBytes("data"); int(samples.size * 2); samples.forEach { short(it.toInt()) }
        val result = ByteArrayOutputStream()
        DataOutputStream(result).apply { writeBytes("RIFF"); writeInt(Integer.reverseBytes(body.size())); write(body.toByteArray()) }
        return result.toByteArray()
    }
}

