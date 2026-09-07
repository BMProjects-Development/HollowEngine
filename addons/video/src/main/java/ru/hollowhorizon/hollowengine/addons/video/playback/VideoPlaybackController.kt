package ru.hollowhorizon.hollowengine.addons.video.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.hollowhorizon.hollowengine.addons.video.decode.*
import ru.hollowhorizon.hollowengine.api.VideoPlaybackOptions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class VideoPlaybackController(
    private val source: String,
    private val options: VideoPlaybackOptions,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow(VideoPlaybackState(source = source))
    private var currentFrame: YuvVideoFrame? = null

    @Volatile
    private var videoQueue = newVideoQueue()

    @Volatile
    private var audioQueue = newAudioQueue()

    private var decodeJob: Job? = null

    val states: StateFlow<VideoPlaybackState> = stateFlow.asStateFlow()
    val state: VideoPlaybackState get() = stateFlow.value
    val pendingAudio: ArrayBlockingQueue<AudioChunk> get() = audioQueue

    fun start() {
        scope.launch {
            runCatching {
                val info = withContext(Dispatchers.IO) { FFmpegMedia.readInfo(source) }
                update { it.copy(info = info, status = VideoPlaybackStatus.BUFFERING) }
                launchDecoders(info, options.startSeconds)
            }.onFailure(::failUnlessCancelled)
        }
    }

    /**
     * Resumes decoding from the [seconds] mark. Outdated frames that are still being streamed
     * are placed in the abandoned queue and discarded; consumers must reset their playback timers to [seconds].
     */
    fun seekTo(seconds: Double) {
        val info = state.info ?: return
        if (state.status == VideoPlaybackStatus.FAILED) return
        decodeJob?.cancel()
        val oldVideoQueue = videoQueue
        val oldAudioQueue = audioQueue
        videoQueue = newVideoQueue()
        audioQueue = newAudioQueue()
        drainQueue(oldVideoQueue)
        drainQueue(oldAudioQueue)
        currentFrame?.close()
        currentFrame = null
        update {
            it.copy(
                status = VideoPlaybackStatus.BUFFERING,
                videoFinished = false,
                audioFinished = !info.hasAudio,
            )
        }
        launchDecoders(info, seconds.coerceAtLeast(0.0))
    }

    fun pollFrame(playbackSeconds: Double): YuvVideoFrame? {
        val queue = videoQueue
        val active = currentFrame
        var next: YuvVideoFrame? = null
        while (true) {
            val candidate = queue.peek() ?: break
            if (active != null && candidate.timestampSeconds > playbackSeconds + FrameLeadToleranceSeconds) break
            next?.close()
            next = queue.poll()
        }
        if (next != null) {
            currentFrame?.close()
            currentFrame = next
            if (state.status == VideoPlaybackStatus.BUFFERING) update { it.copy(status = VideoPlaybackStatus.PLAYING) }
        }
        return next
    }

    fun shouldFinish(audioDrained: Boolean): Boolean {
        val current = state
        if (current.status == VideoPlaybackStatus.FAILED) return true
        if (!current.videoFinished || !current.audioFinished) return false
        if (videoQueue.isNotEmpty()) return false
        return audioDrained || current.info?.hasAudio != true
    }

    private fun launchDecoders(info: VideoStreamInfo, startSeconds: Double) {
        val generation = SupervisorJob(job)
        decodeJob = generation
        val generationScope = CoroutineScope(scope.coroutineContext + generation)
        val targetVideoQueue = videoQueue
        val targetAudioQueue = audioQueue

        generationScope.launch {
            try {
                FFmpegVideoFrameDecoder(source, info, startSeconds).frames().collect { frame ->
                    putFrame(targetVideoQueue, frame)
                }
                update { it.copy(videoFinished = true) }
            } catch (error: Throwable) {
                failUnlessCancelled(error)
            }
        }
        if (info.hasAudio) {
            generationScope.launch {
                try {
                    FFmpegAudioDecoder(source, info, startSeconds).chunks().collect { chunk ->
                        putFrame(targetAudioQueue, chunk)
                    }
                    update { it.copy(audioFinished = true) }
                } catch (error: Throwable) {
                    failUnlessCancelled(error)
                }
            }
        } else {
            update { it.copy(audioFinished = true) }
        }
    }

    private suspend fun <T : AutoCloseable> putFrame(queue: ArrayBlockingQueue<T>, frame: T) {
        try {
            withContext(Dispatchers.IO) { queue.put(frame) }
        } catch (error: Throwable) {
            frame.close()
            throw error
        }
    }

    private fun failUnlessCancelled(error: Throwable) {
        if (error is CancellationException || closed.get()) return
        update {
            it.copy(
                status = VideoPlaybackStatus.FAILED, error = error, videoFinished = true, audioFinished = true
            )
        }
    }

    private fun update(transform: (VideoPlaybackState) -> VideoPlaybackState) {
        stateFlow.value = transform(stateFlow.value)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
        currentFrame?.close()
        currentFrame = null
        drainQueue(videoQueue)
        drainQueue(audioQueue)
    }

    private fun drainQueue(queue: ArrayBlockingQueue<out AutoCloseable>) {
        while (true) {
            queue.poll()?.close() ?: return
        }
    }

    private fun newVideoQueue() = ArrayBlockingQueue<YuvVideoFrame>(options.maxQueuedVideoFrames.coerceAtLeast(1))

    private fun newAudioQueue() = ArrayBlockingQueue<AudioChunk>(options.maxQueuedAudioChunks.coerceAtLeast(1))
}

data class VideoPlaybackState(
    val source: String,
    val info: VideoStreamInfo? = null,
    val status: VideoPlaybackStatus = VideoPlaybackStatus.OPENING,
    val videoFinished: Boolean = false,
    val audioFinished: Boolean = false,
    val error: Throwable? = null,
)

enum class VideoPlaybackStatus {
    OPENING, BUFFERING, PLAYING, FAILED,
}

private const val FrameLeadToleranceSeconds = 0.015
