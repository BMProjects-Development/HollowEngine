import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.minecraft.server.packs.resources.ResourceProvider
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.addons.media.decode.FFmpegInput
import ru.hollowhorizon.hollowengine.addons.media.decode.FFmpegMedia
import ru.hollowhorizon.hollowengine.addons.media.decode.FFmpegSoundStream
import ru.hollowhorizon.hollowengine.addons.media.playback.VideoPlaybackController
import ru.hollowhorizon.hollowengine.api.VideoPlaybackOptions
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoDecodeLifecycleTest {
    private val source get() = Path.of(javaClass.getResource("/audio/video.mp4")!!.toURI()).toString()

    @Test
    fun `media reads video metadata and can seek an extracted audio track`() {
        val info = FFmpegMedia.readInfo(source)
        assertEquals(16, info.width)
        assertTrue(info.hasAudio)
        FFmpegSoundStream(FFmpegInput(MediaSource.File(source), ResourceProvider.EMPTY), 0.15).use {
            val chunk = it.read(1024)
            assertTrue(chunk.hasRemaining())
            assertTrue(it.positionSeconds >= 0.15)
        }
    }

    @Test
    fun `seeks and close cancel producers blocked on full queues`() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val controller = VideoPlaybackController(
            source,
            VideoPlaybackOptions(maxQueuedVideoFrames = 1, maxQueuedAudioChunks = 1),
            scope
        )
        try {
            controller.start()
            withTimeout(10_000) { controller.states.first { it.info != null || it.error != null } }
            assertEquals(null, controller.state.error)
            delay(100)
            repeat(8) { controller.seekTo((it % 2) * 0.1); delay(10) }
            controller.close()
            withTimeout(5000) { parent.children.toList().forEach { it.join() } }
        } finally {
            controller.close(); parent.cancel()
        }
    }
}
