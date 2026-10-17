import net.minecraft.client.sounds.JOrbisAudioStream
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceProvider
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.sounds.PlaybackSoundStream
import ru.hollowhorizon.hollowengine.client.sounds.SoundStreams
import ru.hollowhorizon.hollowengine.client.sounds.WavSoundStream
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundWavFormatsTest {
    @Test
    fun `unsigned PCM8 and signed PCM24 and PCM32 normalize to PCM16`() {
        assertEquals(listOf(-32768, 0, 32512), decode(1, 8, byteArrayOf(0, -128, -1)))
        assertEquals(listOf(-32768, 0, 32767), decode(1, 24, byteArrayOf(0, 0, -128, 0, 0, 0, -1, -1, 127)))
        val data =
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(0).putInt(Int.MAX_VALUE)
                .array()
        assertEquals(listOf(-32768, 0, 32767), decode(1, 32, data))
    }

    @Test
    fun `floating WAV clips infinities and clears NaN`() {
        val data = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putFloat(-2f).putFloat(0f).putFloat(2f)
            .putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).array()
        assertEquals(listOf(-32767, 0, 32767, 0, 0), decode(3, 32, data))
    }

    @Test
    fun `WAVE extensible accepts left aligned valid bits`() {
        val data =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(0x7fffff00).array()
        assertEquals(listOf(-32768, 32767), decode(1, 32, data, extensible = true))
    }

    @Test
    fun `floating WAV64 normalizes to PCM16`() {
        val data =
            ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putDouble(-1.0).putDouble(0.0).putDouble(1.0).array()
        assertEquals(listOf(-32767, 0, 32767), decode(3, 64, data))
    }

    @Test
    fun `native event OGG streams also use the shared decoder route`() {
        var opened = 0
        var closed = 0
        val provider = object : ResourceProvider {
            override fun getResource(id: ResourceLocation): Optional<Resource> = Optional.empty()
            override fun open(id: ResourceLocation) = object : ByteArrayInputStream(
                javaClass.getResourceAsStream("/assets/hollowengine/sounds/button_0.ogg")!!.use { it.readAllBytes() },
            ) {
                init {
                    opened++
                }

                override fun close() {
                    closed++; super.close()
                }
            }
        }
        SoundStreams.stream(ResourceLocation.fromNamespaceAndPath("test", "sounds/button.ogg"), provider, false)!!
            .join().use {
            assertTrue(it.read(1024).hasRemaining())
        }
        assertEquals(1, opened)
        assertEquals(opened, closed)
    }

    @Test
    fun `native OGG decoder obeys the playback stream PCM contract`() {
        val resource = javaClass.getResourceAsStream("/assets/hollowengine/sounds/button_0.ogg")!!
        PlaybackSoundStream({ JOrbisAudioStream(resource) }, SoundPlayback()).use {
            assertEquals(16, it.format.sampleSizeInBits)
            var bytes = 0
            while (true) {
                val chunk = it.read(4096); if (!chunk.hasRemaining()) break; bytes += chunk.remaining()
            }
            assertTrue(bytes > 0)
        }
    }

    @Test
    fun `a WAV whose data size was never written ends with the file`() {
        val data = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(-2).putShort(3).put(9)
            .array()
        assertEquals(listOf(1, -2, 3), decode(1, 16, data, dataSize = -1))
    }

    private fun decode(
        encoding: Int,
        bits: Int,
        data: ByteArray,
        extensible: Boolean = false,
        dataSize: Int = data.size,
    ): List<Int> {
        val fmtSize = if (extensible) 40 else 16
        val bytes = ByteBuffer.allocate(28 + fmtSize + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(fmtSize)
            putShort(if (extensible) -2 else encoding.toShort()); putShort(1); putInt(1000)
            putInt(1000 * bits / 8); putShort((bits / 8).toShort()); putShort(bits.toShort())
            if (extensible) {
                putShort(22); putShort(24); putInt(4); putInt(encoding)
                putInt(0x00100000); putInt(0xAA000080.toInt()); putInt(0x719B3800)
            }
            put("data".toByteArray()); putInt(dataSize); put(data)
        }.array()
        return WavSoundStream(ByteArrayInputStream(bytes)).use { stream ->
            val samples = stream.read(1000).order(ByteOrder.LITTLE_ENDIAN)
            buildList { while (samples.hasRemaining()) add(samples.short.toInt()) }
        }
    }
}
