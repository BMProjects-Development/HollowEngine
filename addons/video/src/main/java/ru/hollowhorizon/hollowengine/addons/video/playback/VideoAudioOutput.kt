package ru.hollowhorizon.hollowengine.addons.video.playback

import org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED
import org.lwjgl.openal.AL10.AL_FORMAT_MONO16
import org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
import org.lwjgl.openal.AL10.AL_GAIN
import org.lwjgl.openal.AL10.AL_PLAYING
import org.lwjgl.openal.AL10.AL_SOURCE_STATE
import org.lwjgl.openal.AL10.AL_STOPPED
import org.lwjgl.openal.AL10.alBufferData
import org.lwjgl.openal.AL10.alDeleteBuffers
import org.lwjgl.openal.AL10.alDeleteSources
import org.lwjgl.openal.AL10.alGenBuffers
import org.lwjgl.openal.AL10.alGenSources
import org.lwjgl.openal.AL10.alGetSourcef
import org.lwjgl.openal.AL10.alGetSourcei
import org.lwjgl.openal.AL10.alSourcePause
import org.lwjgl.openal.AL10.alSourcePlay
import org.lwjgl.openal.AL10.alSourceQueueBuffers
import org.lwjgl.openal.AL10.alSourceStop
import org.lwjgl.openal.AL10.alSourceUnqueueBuffers
import org.lwjgl.openal.AL10.alSourcef
import org.lwjgl.openal.AL11.AL_SEC_OFFSET
import ru.hollowhorizon.hollowengine.addons.video.decode.AudioChunk
import java.util.ArrayDeque
import java.util.Queue

class VideoAudioOutput(
    private val bufferCount: Int = 8,
    volume: Float = 1f,
) : AutoCloseable {
    private var source = 0
    private val buffers = IntArray(bufferCount)
    private val freeBuffers = ArrayDeque<Int>(bufferCount)
    private val queued = ArrayDeque<AudioBufferMeta>(bufferCount)
    private var lastClockSeconds = 0.0
    private var paused = false

    var volume: Float = volume.coerceIn(0f, 1f)
        private set

    fun initialize() {
        if (source != 0) return
        source = alGenSources()
        alSourcef(source, AL_GAIN, volume)
        for (index in buffers.indices) {
            buffers[index] = alGenBuffers()
            freeBuffers.addLast(buffers[index])
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        if (source != 0) alSourcef(source, AL_GAIN, volume)
    }

    fun pause() {
        paused = true
        if (source != 0) alSourcePause(source)
    }

    fun resume() {
        paused = false
        if (source != 0 && queued.isNotEmpty() && alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
            alSourcePlay(source)
        }
    }

    /** Drops everything queued (for seeks); the clock restarts from the next queued chunk. */
    fun flush() {
        if (source == 0) return
        alSourceStop(source)
        while (queued.isNotEmpty()) {
            freeBuffers.addLast(alSourceUnqueueBuffers(source))
            queued.removeFirst()
        }
        lastClockSeconds = 0.0
    }

    fun pump(chunks: Queue<AudioChunk>) {
        if (source == 0) initialize()
        recycleProcessedBuffers()
        while (freeBuffers.isNotEmpty()) {
            val chunk = chunks.poll() ?: break
            queue(chunk)
        }
        if (!paused && queued.isNotEmpty() && alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
            alSourcePlay(source)
        }
    }

    fun clockSeconds(fallbackSeconds: Double): Double {
        if (source == 0) return fallbackSeconds
        recycleProcessedBuffers()
        val current = queued.firstOrNull() ?: return lastClockSeconds.takeIf { it > 0.0 } ?: fallbackSeconds
        val state = alGetSourcei(source, AL_SOURCE_STATE)
        val offset = if (state == AL_PLAYING) alGetSourcef(source, AL_SEC_OFFSET).toDouble() else 0.0
        lastClockSeconds = current.startSeconds + offset
        return lastClockSeconds
    }

    fun isDrained(): Boolean {
        if (source == 0) return true
        recycleProcessedBuffers()
        return queued.isEmpty()
    }

    private fun queue(chunk: AudioChunk) {
        val buffer = freeBuffers.removeFirst()
        val format = when (chunk.channels) {
            1 -> AL_FORMAT_MONO16
            2 -> AL_FORMAT_STEREO16
            else -> {
                freeBuffers.addLast(buffer)
                chunk.close()
                error("Unsupported OpenAL channel count: ${chunk.channels}")
            }
        }
        chunk.pcm.position(0)
        alBufferData(buffer, format, chunk.pcm, chunk.sampleRate)
        alSourceQueueBuffers(source, buffer)
        queued.addLast(AudioBufferMeta(buffer, chunk.timestampSeconds, chunk.durationSeconds))
        chunk.close()
    }

    private fun recycleProcessedBuffers() {
        if (source == 0) return
        val processed = alGetSourcei(source, AL_BUFFERS_PROCESSED)
        repeat(processed) {
            val buffer = alSourceUnqueueBuffers(source)
            val meta = if (queued.isEmpty()) null else queued.removeFirst()
            if (meta != null) lastClockSeconds = meta.startSeconds + meta.durationSeconds
            freeBuffers.addLast(buffer)
        }
        if (!paused && queued.isNotEmpty() && alGetSourcei(source, AL_SOURCE_STATE) == AL_STOPPED) {
            alSourcePlay(source)
        }
    }

    override fun close() {
        if (source != 0) {
            alSourceStop(source)
            while (queued.isNotEmpty()) {
                freeBuffers.addLast(alSourceUnqueueBuffers(source))
                queued.removeFirst()
            }
            alDeleteSources(source)
            source = 0
        }
        if (buffers.any { it != 0 }) {
            alDeleteBuffers(buffers)
        }
    }

    private data class AudioBufferMeta(
        val buffer: Int,
        val startSeconds: Double,
        val durationSeconds: Double,
    )
}
