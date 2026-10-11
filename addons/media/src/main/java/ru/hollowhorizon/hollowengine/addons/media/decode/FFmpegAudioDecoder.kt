package ru.hollowhorizon.hollowengine.addons.media.decode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Video timing adapter over the same decoder used for standalone sound playback. */
class FFmpegAudioDecoder(
    private val source: String,
    private val info: VideoStreamInfo,
    private val startSeconds: Double,
) {
    fun chunks(): Flow<AudioChunk> = flow {
        if (!info.hasAudio) return@flow
        FFmpegSoundStream(FFmpegMedia.openInput(source), startSeconds).use { stream ->
            val format = stream.format
            while (true) {
                currentCoroutineContext().ensureActive()
                val pcm = stream.read(16_384)
                if (!pcm.hasRemaining()) break
                val duration = pcm.remaining().toDouble() / (format.channels * 2 * format.sampleRate)
                emit(AudioChunk(pcm, format.sampleRate.toInt(), format.channels, stream.positionSeconds - duration, duration) {})
            }
        }
    }.flowOn(Dispatchers.IO)
}
