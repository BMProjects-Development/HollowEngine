package ru.hollowhorizon.hollowengine.api

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import java.nio.file.Path
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource

interface VideoApi {
    fun play(source: MediaSource, options: VideoPlaybackOptions = VideoPlaybackOptions()) = play(source.externalForm(), options)

    fun createPlayer(source: MediaSource, options: VideoPlaybackOptions = VideoPlaybackOptions()): VideoPlayer? =
        createPlayer(source.externalForm(), options)

    fun play(path: Path, options: VideoPlaybackOptions = VideoPlaybackOptions())

    fun play(source: String, options: VideoPlaybackOptions = VideoPlaybackOptions()) =
        play(source.fromReadablePath().toPath(), options)

    /**
     * Opens a playback session without any UI: frames are decoded and uploaded into [VideoPlayer.texture],
     * which any renderer (the fullscreen screen, the `Video` composable, world geometry) can draw.
     * Returns null when the implementation does not support embedded playback.
     */
    fun createPlayer(path: Path, options: VideoPlaybackOptions = VideoPlaybackOptions()): VideoPlayer? = null

    fun createPlayer(source: String, options: VideoPlaybackOptions = VideoPlaybackOptions()): VideoPlayer? =
        createPlayer(source.fromReadablePath().toPath(), options)

    companion object {
        fun find(): VideoApi? = HollowAddonManager.find()
    }
}

/**
 * A handle to one playback session. All members are safe to read from the client thread;
 * the implementation advances frames on its own each render tick.
 */
interface VideoPlayer : AutoCloseable {
    val source: String

    /** The texture the current frame is rendered into; null until the first frame is decoded. */
    val texture: ResourceLocation?

    val videoWidth: Int
    val videoHeight: Int

    /** Total duration in seconds, 0.0 when unknown (e.g. live streams). */
    val durationSeconds: Double

    val positionSeconds: Double

    /** True while the clock advances (not paused and not ended). */
    val playing: Boolean

    val ended: Boolean

    val error: Throwable?

    val volume: Float

    fun play()

    fun pause()

    fun seek(seconds: Double)

    fun setVolume(volume: Float)

    /**
     * A single listener invoked on the render thread after each playback tick, the cheap way for UI
     * to mirror [positionSeconds]/[texture] into observable state. Pass null to remove it.
     */
    fun setFrameListener(listener: (() -> Unit)?)
}

data class VideoPlaybackOptions(
    val closeOnEnd: Boolean = true,
    val volume: Float = 1f,
    val startSeconds: Double = 0.0,
    val autoPlay: Boolean = true,
    val maxQueuedVideoFrames: Int = 8,
    val maxQueuedAudioChunks: Int = 24,
)
