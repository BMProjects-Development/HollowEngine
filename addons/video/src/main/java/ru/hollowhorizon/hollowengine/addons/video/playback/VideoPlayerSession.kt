package ru.hollowhorizon.hollowengine.addons.video.playback

import kotlinx.coroutines.CoroutineScope
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.video.events.VideoPlaybackEvent
import ru.hollowhorizon.hollowengine.api.VideoPlaybackOptions
import ru.hollowhorizon.hollowengine.api.VideoPlayer

/**
 * One playback session: decoding, audio and GPU surface binded to a playback clock.
 * [renderTick] must be called on the render thread every frame; [ru.hollowhorizon.hollowengine.addons.video.api.HollowVideo]
 * does that for every open session.
 */
class VideoPlayerSession(
    override val source: String,
    options: VideoPlaybackOptions,
    parentScope: CoroutineScope,
    private val onClosed: (VideoPlayerSession) -> Unit = {},
) : VideoPlayer {
    private val controller = VideoPlaybackController(source, options, parentScope)
    private val audioOutput = VideoAudioOutput(volume = options.volume)
    private val surface = VideoSurfaceTexture()

    private var started = false
    private var closed = false
    private var paused = !options.autoPlay
    private var endedInternal = false
    private var failurePosted = false
    private var seekPreviewPending = false
    private var frameListener: (() -> Unit)? = null

    private var clockBase = options.startSeconds
    private var clockMarkNanos = 0L

    private var monotonicFloorSeconds = options.startSeconds

    override val texture: ResourceLocation? get() = if (surface.ready) surface.location else null
    override val videoWidth: Int get() = controller.state.info?.width ?: 0
    override val videoHeight: Int get() = controller.state.info?.height ?: 0
    override val durationSeconds: Double get() = controller.state.info?.durationSeconds ?: 0.0
    override val positionSeconds: Double get() = playbackSeconds()
    override val playing: Boolean get() = started && !paused && !endedInternal && error == null
    override val ended: Boolean get() = endedInternal
    override val error: Throwable? get() = controller.state.error
    override val volume: Float get() = audioOutput.volume

    /** Runs one playback step on the render thread. Returns false once the session is closed. */
    fun renderTick(): Boolean {
        if (closed) return false
        if (!started) {
            started = true
            audioOutput.initialize()
            controller.start()
            VideoPlaybackEvent.Started.post(VideoPlaybackEvent.Started(source))
        }

        val state = controller.state
        state.error?.let { error ->
            postFailure(error)
            frameListener?.invoke()
            return true
        }

        if (!paused) {
            audioOutput.pump(controller.pendingAudio)
            val frame = controller.pollFrame(playbackSeconds())
            if (frame != null) {
                if (clockMarkNanos == 0L) clockMarkNanos = System.nanoTime()
                surface.upload(frame)
            }
            if (!endedInternal && controller.shouldFinish(audioOutput.isDrained())) {
                endedInternal = true
                clockBase = playbackSeconds()
                clockMarkNanos = 0L
                VideoPlaybackEvent.Finished.post(VideoPlaybackEvent.Finished(source))
            }
        } else if (seekPreviewPending || !surface.ready) {
            controller.pollFrame(clockBase)?.let { frame ->
                seekPreviewPending = false
                surface.upload(frame)
            }
        }

        frameListener?.invoke()
        return true
    }

    override fun play() {
        if (closed) return
        if (endedInternal) {
            seek(0.0)
            paused = false
            return
        }
        if (!paused) return
        paused = false
        clockMarkNanos = if (surface.ready) System.nanoTime() else 0L
        audioOutput.resume()
    }

    override fun pause() {
        if (closed || paused) return
        clockBase = playbackSeconds()
        clockMarkNanos = 0L
        paused = true
        audioOutput.pause()
    }

    override fun seek(seconds: Double) {
        if (closed) return
        val duration = durationSeconds
        val target = seconds.coerceAtLeast(0.0).let { if (duration > 0.0) it.coerceAtMost(duration) else it }
        controller.seekTo(target)
        audioOutput.flush()
        clockBase = target
        clockMarkNanos = 0L
        monotonicFloorSeconds = target
        endedInternal = false
        seekPreviewPending = paused
    }

    override fun setVolume(volume: Float) {
        audioOutput.setVolume(volume)
    }

    override fun setFrameListener(listener: (() -> Unit)?) {
        frameListener = listener
    }

    override fun close() {
        if (closed) return
        closed = true
        controller.close()
        audioOutput.close()
        surface.close()
        controller.state.error?.let(::postFailure)
        onClosed(this)
    }

    private fun playbackSeconds(): Double {
        if (paused || endedInternal) return clockBase
        val fallback = if (clockMarkNanos == 0L) {
            clockBase
        } else {
            clockBase + (System.nanoTime() - clockMarkNanos) / NanosPerSecond
        }
        val clock = if (controller.state.info?.hasAudio == true) audioOutput.clockSeconds(fallback) else fallback
        if (clock > monotonicFloorSeconds) monotonicFloorSeconds = clock
        return monotonicFloorSeconds
    }

    private fun postFailure(error: Throwable) {
        if (failurePosted) return
        failurePosted = true
        HollowEngine.LOGGER.error("Video playback failed for '{}'", source, error)
        VideoPlaybackEvent.Failed.post(VideoPlaybackEvent.Failed(source, error))
    }
}

private const val NanosPerSecond = 1_000_000_000.0
