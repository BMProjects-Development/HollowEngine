package ru.hollowhorizon.hollowengine.addons.media.decode

import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceProvider
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.*
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.avformat_network_init
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.PointerPointer
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource

object FFmpegMedia {
    @Volatile
    private var preloaded = false

    /**
     * Forces JavaCPP to extract and load the native FFmpeg libraries. Loading takes noticeable time
     * (extraction + dlopen), so the addon calls this in the background.
     */
    fun preload() {
        if (preloaded) return
        synchronized(this) {
            if (preloaded) return
            Loader.load(avutil::class.java)
            Loader.load(avcodec::class.java)
            Loader.load(avformat::class.java)
            Loader.load(swscale::class.java)
            Loader.load(swresample::class.java)
            avformat_network_init()
            preloaded = true
        }
    }

    fun readInfo(source: String): VideoStreamInfo {
        val input = openInput(source)
        val format = input.format
        var codec: AVCodecContext? = null
        try {
            val videoStream = findStream(format, AVMEDIA_TYPE_VIDEO)
            val audioStream = findStream(format, AVMEDIA_TYPE_AUDIO, required = false)
            codec = createCodec(format, videoStream, open = false)
            val duration = if (format.duration() != AV_NOPTS_VALUE) {
                format.duration() / 1_000_000.0
            } else {
                0.0
            }
            return VideoStreamInfo(
                width = codec.width(),
                height = codec.height(),
                durationSeconds = duration,
                videoStreamIndex = videoStream,
                audioStreamIndex = audioStream,
            )
        } finally {
            codec?.let { avcodec_free_context(it) }
            input.close()
        }
    }

    fun openInput(
        source: String,
        resources: ResourceProvider = Minecraft.getInstance()?.resourceManager ?: ResourceProvider.EMPTY,
    ) = FFmpegInput(
        MediaSource.parse(source), resources,
    )

    fun findStream(context: AVFormatContext, mediaType: Int, required: Boolean = true): Int {
        for (index in 0 until context.nb_streams()) {
            if (context.streams(index).codecpar().codec_type() == mediaType) return index
        }
        if (required) error("No FFmpeg stream found for media type $mediaType")
        return -1
    }

    fun createCodec(context: AVFormatContext, streamIndex: Int, open: Boolean): AVCodecContext {
        val parameters = context.streams(streamIndex).codecpar()
        val decoder = avcodec_find_decoder(parameters.codec_id()) ?: error("FFmpeg decoder not found")
        val codec = avcodec_alloc_context3(decoder) ?: error("Failed to allocate FFmpeg codec context")
        if (avcodec_parameters_to_context(codec, parameters) < 0) {
            avcodec_free_context(codec)
            error("Failed to copy FFmpeg codec parameters")
        }
        if (open && avcodec_open2(codec, decoder, null as PointerPointer<BytePointer>?) < 0) {
            avcodec_free_context(codec)
            error("Failed to open FFmpeg decoder")
        }
        return codec
    }

    fun streamStartTimestamp(startTime: Long): Long {
        return if (startTime == AV_NOPTS_VALUE) 0L else startTime
    }

    fun secondsToStreamTimestamp(seconds: Double, timeBaseSeconds: Double, streamStartTimestamp: Long): Long {
        if (timeBaseSeconds <= 0.0) return streamStartTimestamp
        return streamStartTimestamp + (seconds / timeBaseSeconds).toLong()
    }

    fun frameTimestampSeconds(frame: AVFrame, timeBaseSeconds: Double, streamStartSeconds: Double): Double {
        val pts = when {
            frame.best_effort_timestamp() != AV_NOPTS_VALUE -> frame.best_effort_timestamp()
            frame.pts() != AV_NOPTS_VALUE -> frame.pts()
            else -> 0L
        }
        return (pts * timeBaseSeconds - streamStartSeconds).coerceAtLeast(0.0)
    }
}
