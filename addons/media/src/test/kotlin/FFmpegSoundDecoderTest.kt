import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceProvider
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.addons.media.decode.FFmpegSoundDecoder
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import java.io.InputStream
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FFmpegSoundDecoderTest {
    @Test
    fun `decodes CBR mono MP3 VBR MP3 FLAC and audio-only M4A in small buffers`() {
        for ((name, channels) in listOf(
            "mono.mp3" to 1,
            "vbr.mp3" to 2,
            "tone.flac" to 2,
            "tone.m4a" to 2,
            "video.mp4" to 2
        )) {
            FFmpegSoundDecoder().use { decoder ->
                val path = Path.of(javaClass.getResource("/audio/$name")!!.toURI())
                decoder.open(MediaSource.File(path.toString()), ResourceProvider.EMPTY).use { stream ->
                    assertEquals(channels, stream.format.channels, name)
                    assertEquals(16, stream.format.sampleSizeInBits, name)
                    var frames = 0
                    var audible = false
                    while (true) {
                        val buffer = stream.read(512).order(ByteOrder.LITTLE_ENDIAN)
                        if (!buffer.hasRemaining()) break
                        assertEquals(0, buffer.remaining() % (channels * 2), name)
                        frames += buffer.remaining() / (channels * 2)
                        while (buffer.hasRemaining()) if (buffer.short.toInt() != 0) audible = true
                    }
                    assertTrue(frames >= 4000, "$name decoded only $frames frames")
                    assertTrue(audible, "$name decoded silence")
                }
            }
        }
    }

    @Test
    fun `pack resource MP3 and M4A use custom streaming IO and release every opened stream`() {
        for (name in listOf("vbr.mp3", "tone.m4a")) {
            var opens = 0
            var closes = 0
            val resources = object : ResourceProvider {
                override fun getResource(id: ResourceLocation): Optional<Resource> = Optional.empty()
                override fun open(id: ResourceLocation): InputStream {
                    opens++
                    val source = javaClass.getResourceAsStream("/audio/$name")!!
                    return object : InputStream() {
                        override fun read() = source.read()
                        override fun read(bytes: ByteArray, offset: Int, size: Int) = source.read(bytes, offset, size)
                        override fun skip(size: Long) = source.skip(size)
                        override fun close() {
                            closes++; source.close()
                        }
                    }
                }
            }
            FFmpegSoundDecoder().use { decoder ->
                decoder.open(
                    MediaSource.Resource(ResourceLocation.fromNamespaceAndPath("test", "sounds/$name")),
                    resources
                ).use {
                    assertTrue(it.read(1024).hasRemaining())
                }
            }
            assertEquals(opens, closes, "$name leaked a resource stream")
        }
    }

    @Test
    fun `unloading decoder closes active streams and rejects new playback`() {
        val decoder = FFmpegSoundDecoder()
        val source = MediaSource.File(Path.of(javaClass.getResource("/audio/vbr.mp3")!!.toURI()).toString())
        val stream = decoder.open(source, ResourceProvider.EMPTY)
        decoder.close(); decoder.close()
        assertFailsWith<IllegalStateException> { stream.read(512) }
        assertFailsWith<IllegalStateException> { decoder.open(source, ResourceProvider.EMPTY) }
        stream.close()
    }
}
