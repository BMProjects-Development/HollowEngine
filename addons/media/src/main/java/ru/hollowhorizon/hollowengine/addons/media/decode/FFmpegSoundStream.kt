package ru.hollowhorizon.hollowengine.addons.media.decode

import net.minecraft.client.sounds.AudioStream
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat

/** Pull decoding, bounded to one decoded frame; no coroutine, complete-file buffer, or OpenAL source. */
class FFmpegSoundStream(private val input: FFmpegInput, private val startSeconds: Double = 0.0) : AudioStream {
    private var codec: AVCodecContext? = null
    private var resampler: SwrContext? = null
    private lateinit var packet: AVPacket
    private lateinit var frame: AVFrame
    private var streamIndex = -1
    private var inputEnded = false
    private var decoderEnded = false
    private var packetPending = false
    private var closed = false
    private var pending = ByteBuffer.allocate(0)
    private var channels = 2
    private var sampleRate = 48_000
    private var timeBaseSeconds = 0.0
    private var streamStartSeconds = 0.0
    private var firstOutput = true
    var positionSeconds = startSeconds
        private set

    init {
        try {
            packet = av_packet_alloc() ?: error("Could not allocate audio packet")
            frame = av_frame_alloc() ?: error("Could not allocate audio frame")
            streamIndex = FFmpegMedia.findStream(input.format, AVMEDIA_TYPE_AUDIO)
            val decoder = FFmpegMedia.createCodec(input.format, streamIndex, open = true)
            codec = decoder
            channels = decoder.ch_layout().nb_channels().coerceIn(1, 2)
            sampleRate = decoder.sample_rate()
            require(sampleRate > 0) { "Invalid media sample rate" }
            val sourceStream = input.format.streams(streamIndex)
            timeBaseSeconds = av_q2d(sourceStream.time_base())
            val startTimestamp = FFmpegMedia.streamStartTimestamp(sourceStream.start_time())
            streamStartSeconds = startTimestamp * timeBaseSeconds
            if (startSeconds > 0.0) {
                val timestamp = FFmpegMedia.secondsToStreamTimestamp(startSeconds, timeBaseSeconds, startTimestamp)
                if (av_seek_frame(
                        input.format,
                        streamIndex,
                        timestamp,
                        AVSEEK_FLAG_BACKWARD
                    ) >= 0
                ) avcodec_flush_buffers(decoder)
            }
            val outputLayout = AVChannelLayout()
            try {
                av_channel_layout_default(outputLayout, channels)
                val converter = swr_alloc() ?: error("Could not allocate audio resampler")
                resampler = converter
                check(
                    swr_alloc_set_opts2(
                        converter,
                        outputLayout,
                        AV_SAMPLE_FMT_S16,
                        sampleRate,
                        decoder.ch_layout(),
                        decoder.sample_fmt(),
                        sampleRate,
                        0,
                        null
                    ) >= 0
                )
                check(swr_init(converter) >= 0) { "Could not initialize audio resampler" }
            } finally {
                av_channel_layout_uninit(outputLayout); outputLayout.close()
            }
        } catch (error: Throwable) {
            close(); throw error
        }
    }

    override fun getFormat() = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

    @Synchronized
    override fun read(size: Int): ByteBuffer {
        check(!closed)
        input.progress()
        require(size > 0)
        val output = ByteBuffer.allocateDirect(maxOf(channels * 2, size / (channels * 2) * channels * 2))
            .order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (output.hasRemaining()) {
                if (!pending.hasRemaining()) pending = nextChunk()
                if (!pending.hasRemaining()) break
                val limit = pending.limit()
                pending.limit(pending.position() + minOf(output.remaining(), pending.remaining()))
                output.put(pending)
                pending.limit(limit)
            }
        } catch (error: Throwable) {
            close(); throw error
        }
        positionSeconds += output.position().toDouble() / (channels * 2 * sampleRate)
        return output.flip()
    }

    private fun nextChunk(): ByteBuffer {
        val decoder = codec!!
        while (!decoderEnded) {
            val received = avcodec_receive_frame(decoder, frame)
            when {
                received == 0 -> {
                    try {
                        val timestamp = FFmpegMedia.frameTimestampSeconds(frame, timeBaseSeconds, streamStartSeconds)
                        val pcm = convert(frame.nb_samples())
                        val skipFrames = ((startSeconds - timestamp).coerceAtLeast(0.0) * sampleRate).toLong()
                        pcm.position(minOf(skipFrames * channels * 2, pcm.remaining().toLong()).toInt())
                        if (pcm.hasRemaining()) {
                            if (firstOutput) {
                                positionSeconds = maxOf(
                                    startSeconds,
                                    timestamp + pcm.position().toDouble() / (channels * 2 * sampleRate)
                                )
                                firstOutput = false
                            }
                            return pcm
                        }
                    } finally {
                        av_frame_unref(frame)
                    }
                    continue
                }

                received == AVERROR_EOF -> {
                    decoderEnded = true; break
                }

                received != -11 -> error("Media audio decode failed: $received")
            }
            if (!packetPending && !inputEnded) {
                while (true) {
                    val result = av_read_frame(input.format, packet)
                    input.progress()
                    if (result == AVERROR_EOF) {
                        inputEnded = true; break
                    }
                    check(result >= 0) { "Media packet read failed: $result" }
                    if (packet.stream_index() == streamIndex) {
                        packetPending = true; break
                    }
                    av_packet_unref(packet)
                }
            }
            val sent = avcodec_send_packet(decoder, if (packetPending) packet else null)
            check(sent >= 0 || sent == AVERROR_EOF || sent == -11) { "Media packet decode failed: $sent" }
            if (sent != -11 && packetPending) {
                av_packet_unref(packet); packetPending = false
            }
        }
        return convert(0)
    }

    private fun convert(samples: Int): ByteBuffer {
        val converter = resampler!!
        val capacity = av_rescale_rnd(
            swr_get_delay(converter, sampleRate.toLong()) + samples,
            sampleRate.toLong(),
            sampleRate.toLong(),
            AV_ROUND_UP
        ).toInt()
        if (capacity <= 0) return ByteBuffer.allocate(0)
        val buffer = ByteBuffer.allocateDirect(capacity * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        BytePointer(buffer).use { pointer ->
            PointerPointer<BytePointer>(1).use { pointers ->
                pointers.put(0, pointer)
                val count = swr_convert(
                    converter,
                    pointers,
                    capacity,
                    if (samples == 0) null else frame.extended_data(),
                    samples
                )
                check(count >= 0) { "Audio resampling failed: $count" }
                buffer.limit(count * channels * 2)
            }
        }
        return buffer
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (::packet.isInitialized) av_packet_free(packet)
        if (::frame.isInitialized) av_frame_free(frame)
        resampler?.let { swr_free(it) }; resampler = null
        codec?.let { avcodec_free_context(it) }; codec = null
        input.close()
    }
}
