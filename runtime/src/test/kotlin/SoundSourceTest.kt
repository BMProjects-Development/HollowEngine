import kotlinx.serialization.json.Json
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.sounds.SoundAttachments
import ru.hollowhorizon.hollowengine.client.sounds.SoundEmitterState
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundRepeat
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SoundSourceTest {
    @Test
    fun `event names containing dots stay events`() {
        assertIs<SoundSource.Event>(SoundSource.parse("minecraft:entity.villager.yes"))
        assertIs<SoundSource.Event>(SoundSource.parse("event:demo:voice.mp3"))
    }

    @Test
    fun `file sources retain extension and normalize the sound root`() {
        assertEquals(
            "sounds/dialogue/hello.ogg",
            assertIs<MediaSource.Resource>(SoundSource.parse("demo:dialogue/hello.ogg")).id.path
        )
        assertEquals(
            "sounds/ui/connect.wav",
            assertIs<MediaSource.Resource>(SoundSource.parse("demo:audio/ui/connect.wav")).id.path
        )
        assertIs<MediaSource.Resource>(SoundSource.parse("resource:demo:music.custom"))
        assertIs<MediaSource.File>(SoundSource.parse("file:music/track.mp3"))
        assertIs<MediaSource.Url>(SoundSource.parse("https://example.org/track.mp3?token=1"))
    }

    @Test
    fun `playback contract roundtrips and rejects invalid settings`() {
        val playback = SoundPlayback(SoundRepeat.Count(3), fadeIn = 0.3f, fadeOut = 0.5f)
        assertEquals(playback, Json.decodeFromString<SoundPlayback>(Json.encodeToString(playback)))
        assertFailsWith<IllegalArgumentException> { SoundRepeat.Count(0) }
        assertFailsWith<IllegalArgumentException> { SoundPlayback(fadeIn = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { SoundPlayback(pitch = 0f) }
        for (source in listOf(
            SoundSource.parse("demo:voice.mp3"),
            SoundSource.parse("minecraft:entity.villager.yes")
        )) {
            assertEquals(source, Json.decodeFromString<SoundSource>(Json.encodeToString<SoundSource>(source)))
        }
    }

    @Test
    fun `attachment consumers distinguish waiting from removed owners and do not follow reused ids`() {
        SoundAttachments.clear()
        val emitter = SoundAttachments.emitter("test")
        assertEquals(SoundEmitterState.Pending, emitter.sample())
        val binding = SoundAttachments.register("test")
        binding.update(Vec3(1.0, 2.0, 3.0))
        assertIs<SoundEmitterState.Available>(emitter.sample())
        binding.close()
        assertEquals(SoundEmitterState.Removed, emitter.sample())
        SoundAttachments.register("test").use { assertEquals(SoundEmitterState.Removed, emitter.sample()) }
        SoundAttachments.clear()
    }

    @Test
    fun `rig selectors support nested paths and reject ambiguous model nodes`() {
        SoundAttachments.clear()
        try {
            SoundAttachments.register("entity/model1/hand/tool/engine").update(Vec3(1.0, 0.0, 0.0))
            SoundAttachments.register("entity/model2/hand/tool/engine").update(Vec3(2.0, 0.0, 0.0))
            assertFailsWith<IllegalStateException> {
                SoundAttachments.rigEmitter("entity", "hand/tool/engine", null).sample()
            }
            val state = SoundAttachments.rigEmitter("entity", "hand/tool/engine", "model2").sample()
            assertEquals(Vec3(2.0, 0.0, 0.0), assertIs<SoundEmitterState.Available>(state).position)
        } finally {
            SoundAttachments.clear()
        }
    }
}

