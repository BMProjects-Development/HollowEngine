package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.Keyframe
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CutsceneMigrationTest {

    @Test
    fun `a version 1 file keeps every frame where it was recorded`() {
        val legacy = legacyCutscene(Vec3f(120f, 70f, -33f), Vec3f(126f, 72f, -30f))

        val migrated = CutsceneMigrations.convert(legacy, version = 1)
        val playback = CutscenePlaybackController().apply { setupTracks(migrated) }

        assertEquals(120f, migrated.origin.x, 0.001f)
        assertEquals(0f, playback.translation.curves[0].keyframes.first().value, 0.001f)

        playback.seek(0f)
        assertEquals(120f, playback.currentPose.position.x, 0.001f)
        playback.seek(2f)
        assertEquals(126f, playback.currentPose.position.x, 0.001f)
        assertEquals(72f, playback.currentPose.position.y, 0.001f)
    }

    @Test
    fun `a vector key becomes one key per channel`() {
        val migrated = CutsceneMigrations.convert(legacyCutscene(Vec3f(1f, 2f, 3f)), version = 1)
        val playback = CutscenePlaybackController().apply { setupTracks(migrated) }

        val channels = playback.translation.curves
        assertEquals(3, channels.size)
        assertTrue(channels.all { it.keyframes.size == 1 })
    }

    @Test
    fun `an old easing becomes the handles of the segment it governed`() {
        val legacy = legacyCutscene(Vec3f(0f, 0f, 0f), Vec3f(10f, 0f, 0f), easing = "easeInOutCubic")

        val migrated = CutsceneMigrations.convert(legacy, version = 1)
        val playback = CutscenePlaybackController().apply { setupTracks(migrated) }
        val x = playback.translation.curves[0]

        assertEquals(5f, x.valueAt(1f, 0f), 0.2f)
        assertTrue(x.valueAt(0.4f, 0f) < 1.5f, "an ease-in should still be crawling at 20% of the way")
    }

    @Test
    fun `a hidden channel is still hidden after a round trip`() {
        val playback = CutscenePlaybackController()
        val channels = playback.translation.curves
        channels[0].keyframes += Keyframe(0f, 5f)
        channels[1].keyframes += Keyframe(0f, 7f)
        channels[1].isVisible = false
        val saved = playback.toData("Hidden")

        val reloaded = CutscenePlaybackController().apply { setupTracks(saved) }
        val loaded = reloaded.translation.curves

        assertTrue(loaded[0].isVisible)
        assertTrue(!loaded[1].isVisible, "the channel comes back hidden")
        assertEquals(playback.translation.valueAt(0f).y, reloaded.translation.valueAt(0f).y, 0.001f)
    }

    private fun legacyCutscene(vararg positions: Vec3f, easing: String = "linear") = LegacyCutsceneData(
        name = "Legacy",
        duration = 2f,
        nodes = listOf(
            LegacyNodeData(
                id = "camera",
                name = "Camera",
                children = listOf(
                    LegacyNodeData(
                        id = "camera.position",
                        name = "Position",
                        track = LegacyTrackData(
                            type = "hollowengine:camera.position",
                            keyframes = positions.mapIndexed { index, position ->
                                LegacyKeyData(
                                    time = index * 2f,
                                    value = LegacyKeyframeSnapshot.Vec3fSnapshot(position.x, position.y, position.z),
                                    easing = easing,
                                )
                            },
                        ),
                    ),
                ),
            ),
        ),
    )
}
