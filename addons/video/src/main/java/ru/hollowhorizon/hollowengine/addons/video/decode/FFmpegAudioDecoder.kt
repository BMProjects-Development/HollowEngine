package ru.hollowhorizon.hollowengine.addons.video.decode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_ROUND_UP
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_check
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.global.avutil.av_rescale_rnd
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_delay
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer

class FFmpegAudioDecoder(
    private val source: String,
    private val info: VideoStreamInfo,
    private val startSeconds: Double,
    private val bufferPool: ByteBufferPool = ByteBufferPool(maxPooledBuffers = 64),
    private val outputSampleRate: Int = 48_000,
    private val outputChannels: Int = 2,
) {
    fun chunks(): Flow<AudioChunk> = flow {
        if (!info.hasAudio) return@flow
        decode { chunk -> emit(chunk) }
    }.flowOn(Dispatchers.IO)

    private suspend fun decode(emitChunk: suspend (AudioChunk) -> Unit) {
        val format = FFmpegMedia.openFormat(source)
        var codec: AVCodecContext? = null
        var resampler: SwrContext? = null
        val packet = av_packet_alloc() ?: error("Failed to allocate FFmpeg audio packet")
        val frame = av_frame_alloc() ?: error("Failed to allocate FFmpeg audio frame")

        try {
            codec = FFmpegMedia.createCodec(format, info.audioStreamIndex, open = true)
            resampler = createResampler(codec)
            val stream = format.streams(info.audioStreamIndex)
            val timeBaseSeconds = av_q2d(stream.time_base())
            val streamStartTimestamp = FFmpegMedia.streamStartTimestamp(stream.start_time())
            val streamStartSeconds = streamStartTimestamp * timeBaseSeconds

            if (startSeconds > 0.0) {
                val seekTimestamp = FFmpegMedia.secondsToStreamTimestamp(startSeconds, timeBaseSeconds, streamStartTimestamp)
                if (av_seek_frame(format, info.audioStreamIndex, seekTimestamp, AVSEEK_FLAG_BACKWARD) >= 0) {
                    avcodec_flush_buffers(codec)
                }
            }

            while (av_read_frame(format, packet) >= 0) {
                try {
                    if (packet.stream_index() == info.audioStreamIndex && avcodec_send_packet(codec, packet) == 0) {
                        receiveAudio(codec, resampler, frame, timeBaseSeconds, streamStartSeconds, emitChunk)
                    }
                } finally {
                    av_packet_unref(packet)
                }
            }

            avcodec_send_packet(codec, null)
            receiveAudio(codec, resampler, frame, timeBaseSeconds, streamStartSeconds, emitChunk)
        } finally {
            resampler?.let { swr_free(it) }
            av_frame_free(frame)
            av_packet_free(packet)
            codec?.let { avcodec_free_context(it) }
            avformat_close_input(format)
        }
    }

    private fun createResampler(codec: AVCodecContext): SwrContext {
        val inputLayout = codec.ch_layout()
        if (av_channel_layout_check(inputLayout) == 0) {
            av_channel_layout_default(inputLayout, inputLayout.nb_channels().coerceAtLeast(1))
        }
        val outputLayout = AVChannelLayout()
        av_channel_layout_default(outputLayout, outputChannels)

        val resampler = swr_alloc() ?: error("Failed to allocate FFmpeg audio resampler")
        if (swr_alloc_set_opts2(
                resampler,
                outputLayout,
                AV_SAMPLE_FMT_S16,
                outputSampleRate,
                inputLayout,
                codec.sample_fmt(),
                codec.sample_rate(),
                0,
                null,
            ) < 0
        ) {
            swr_free(resampler)
            error("Failed to configure FFmpeg audio resampler")
        }
        if (swr_init(resampler) < 0) {
            swr_free(resampler)
            error("Failed to initialize FFmpeg audio resampler")
        }
        return resampler
    }

    private suspend fun receiveAudio(
        codec: AVCodecContext,
        resampler: SwrContext,
        frame: AVFrame,
        timeBaseSeconds: Double,
        streamStartSeconds: Double,
        emitChunk: suspend (AudioChunk) -> Unit,
    ) {
        while (avcodec_receive_frame(codec, frame) == 0) {
            val timestamp = audioTimestampSeconds(frame, timeBaseSeconds, streamStartSeconds)
            if (timestamp + FrameTimestampToleranceSeconds < startSeconds) continue

            val outputSamples = av_rescale_rnd(
                swr_get_delay(resampler, codec.sample_rate().toLong()) + frame.nb_samples(),
                outputSampleRate.toLong(),
                codec.sample_rate().toLong(),
                AV_ROUND_UP,
            ).toInt()
            val maxOutputSize = outputSamples * outputChannels * Short.SIZE_BYTES
            val buffer = bufferPool.acquire(maxOutputSize)
            val outputPointer = BytePointer(buffer)
            val outputPointers = PointerPointer<BytePointer>(1).put(0, outputPointer)

            val convertedSamples = swr_convert(resampler, outputPointers, outputSamples, frame.extended_data(), frame.nb_samples())
            if (convertedSamples <= 0) {
                bufferPool.release(buffer)
                continue
            }

            val bytes = convertedSamples * outputChannels * Short.SIZE_BYTES
            buffer.position(0)
            buffer.limit(bytes)
            val chunk = AudioChunk(
                pcm = buffer,
                sampleRate = outputSampleRate,
                channels = outputChannels,
                timestampSeconds = timestamp,
                durationSeconds = convertedSamples.toDouble() / outputSampleRate,
                release = bufferPool::release,
            )
            try {
                emitChunk(chunk)
            } catch (error: Throwable) {
                chunk.close()
                throw error
            }
            currentCoroutineContext().ensureActive()
        }
    }

    private fun audioTimestampSeconds(frame: AVFrame, timeBaseSeconds: Double, streamStartSeconds: Double): Double {
        val pts = when {
            frame.best_effort_timestamp() != AV_NOPTS_VALUE -> frame.best_effort_timestamp()
            frame.pts() != AV_NOPTS_VALUE -> frame.pts()
            else -> 0L
        }
        return (pts * timeBaseSeconds - streamStartSeconds).coerceAtLeast(0.0)
    }
}

private const val FrameTimestampToleranceSeconds = 0.001
