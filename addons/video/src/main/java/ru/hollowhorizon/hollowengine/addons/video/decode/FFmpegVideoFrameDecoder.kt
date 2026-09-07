package ru.hollowhorizon.hollowengine.addons.video.decode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
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
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT470BG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE170M
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import java.nio.ByteBuffer

class FFmpegVideoFrameDecoder(
    private val source: String,
    private val info: VideoStreamInfo,
    private val startSeconds: Double,
    private val bufferPool: ByteBufferPool = ByteBufferPool(maxPooledBuffers = 32),
) {
    fun frames(): Flow<YuvVideoFrame> = flow {
        decode { frame -> emit(frame) }
    }.flowOn(Dispatchers.IO)

    private suspend fun decode(emitFrame: suspend (YuvVideoFrame) -> Unit) {
        val format = FFmpegMedia.openFormat(source)
        var codec: AVCodecContext? = null
        val packet = av_packet_alloc() ?: error("Failed to allocate FFmpeg video packet")
        val decoded = av_frame_alloc() ?: error("Failed to allocate FFmpeg video frame")
        var scaled: ScaledYuvFrame? = null

        try {
            codec = FFmpegMedia.createCodec(format, info.videoStreamIndex, open = true)
            val stream = format.streams(info.videoStreamIndex)
            val timeBaseSeconds = av_q2d(stream.time_base())
            val streamStartTimestamp = FFmpegMedia.streamStartTimestamp(stream.start_time())
            val streamStartSeconds = streamStartTimestamp * timeBaseSeconds

            if (startSeconds > 0.0) {
                val seekTimestamp = FFmpegMedia.secondsToStreamTimestamp(startSeconds, timeBaseSeconds, streamStartTimestamp)
                if (av_seek_frame(format, info.videoStreamIndex, seekTimestamp, AVSEEK_FLAG_BACKWARD) >= 0) {
                    avcodec_flush_buffers(codec)
                }
            }

            val emitDecoded: suspend () -> Unit = {
                val timestamp = FFmpegMedia.frameTimestampSeconds(decoded, timeBaseSeconds, streamStartSeconds)
                if (timestamp + FrameTimestampToleranceSeconds >= startSeconds) {
                    if (scaled == null && !isDirectlyPackable(decoded)) {
                        scaled = ScaledYuvFrame(info.width, info.height)
                    }
                    val frame = packFrame(decoded, scaled, timestamp)
                    try {
                        emitFrame(frame)
                    } catch (error: Throwable) {
                        frame.close()
                        throw error
                    }
                    currentCoroutineContext().ensureActive()
                }
            }

            while (av_read_frame(format, packet) >= 0) {
                try {
                    if (packet.stream_index() == info.videoStreamIndex && avcodec_send_packet(codec, packet) == 0) {
                        while (avcodec_receive_frame(codec, decoded) == 0) emitDecoded()
                    }
                } finally {
                    av_packet_unref(packet)
                }
            }

            avcodec_send_packet(codec, null)
            while (avcodec_receive_frame(codec, decoded) == 0) emitDecoded()
        } finally {
            scaled?.close()
            av_frame_free(decoded)
            av_packet_free(packet)
            codec?.let { avcodec_free_context(it) }
            avformat_close_input(format)
        }
    }

    /** Whether the decoded frame's planes can be copied out as-is, without an sws conversion pass. */
    private fun isDirectlyPackable(decoded: AVFrame): Boolean {
        val directFormat = when (decoded.format()) {
            AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P, AV_PIX_FMT_NV12 -> true
            else -> false
        }
        if (!directFormat) return false
        val planes = if (decoded.format() == AV_PIX_FMT_NV12) 2 else 3
        for (plane in 0 until planes) {
            if (decoded.linesize(plane) <= 0) return false
        }
        return true
    }

    private fun packFrame(decoded: AVFrame, scaled: ScaledYuvFrame?, timestamp: Double): YuvVideoFrame {
        val width = info.width
        val height = info.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val target = bufferPool.acquire(YuvVideoFrame.packedSize(width, height))
        target.clear()

        val sourceFrame: AVFrame
        val format: VideoPixelFormat
        if (scaled != null) {
            scaled.scale(decoded)
            sourceFrame = scaled.frame
            format = VideoPixelFormat.YUV420P
        } else {
            sourceFrame = decoded
            format = if (decoded.format() == AV_PIX_FMT_NV12) VideoPixelFormat.NV12 else VideoPixelFormat.YUV420P
        }

        putPlane(target, sourceFrame.data(0), sourceFrame.linesize(0), width, height)
        if (format == VideoPixelFormat.NV12) {
            putPlane(target, sourceFrame.data(1), sourceFrame.linesize(1), chromaWidth * 2, chromaHeight)
        } else {
            putPlane(target, sourceFrame.data(1), sourceFrame.linesize(1), chromaWidth, chromaHeight)
            putPlane(target, sourceFrame.data(2), sourceFrame.linesize(2), chromaWidth, chromaHeight)
        }
        target.flip()

        return YuvVideoFrame(
            data = target,
            format = format,
            width = width,
            height = height,
            timestampSeconds = timestamp,
            fullRange = decoded.color_range() == AVCOL_RANGE_JPEG || decoded.format() == AV_PIX_FMT_YUVJ420P,
            bt709 = isBt709(decoded),
            release = bufferPool::release,
        )
    }

    private fun isBt709(decoded: AVFrame): Boolean = when (decoded.colorspace()) {
        AVCOL_SPC_BT709 -> true
        AVCOL_SPC_BT470BG, AVCOL_SPC_SMPTE170M -> false
        // Unspecified: HD content is almost always BT.709, SD almost always BT.601.
        else -> info.height >= 720
    }

    private fun putPlane(target: ByteBuffer, plane: BytePointer, stride: Int, rowBytes: Int, rows: Int) {
        val source = BytePointer(plane).position(0).capacity(stride.toLong() * rows).asByteBuffer()
        if (stride == rowBytes) {
            source.limit(rowBytes * rows)
            target.put(source)
            return
        }
        var offset = 0
        repeat(rows) {
            source.limit(offset + rowBytes).position(offset)
            target.put(source)
            offset += stride
        }
    }

    /** An owned YUV420P frame plus the sws context that fills it, for sources in other pixel formats. */
    private inner class ScaledYuvFrame(width: Int, height: Int) : AutoCloseable {
        val frame: AVFrame = av_frame_alloc() ?: error("Failed to allocate FFmpeg YUV frame")
        private val buffer: BytePointer
        private var scaler: SwsContext? = null
        private var scalerSourceFormat = Int.MIN_VALUE

        init {
            val size = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, width, height, 1)
            buffer = BytePointer(av_malloc(size.toLong())).capacity(size.toLong())
            av_image_fill_arrays(frame.data(), frame.linesize(), buffer, AV_PIX_FMT_YUV420P, width, height, 1)
        }

        fun scale(decoded: AVFrame) {
            val scaler = scalerFor(decoded.format())
            sws_scale(scaler, decoded.data(), decoded.linesize(), 0, info.height, frame.data(), frame.linesize())
        }

        private fun scalerFor(sourceFormat: Int): SwsContext {
            val existing = scaler
            if (existing != null && scalerSourceFormat == sourceFormat) return existing
            existing?.let { sws_freeContext(it) }
            val created = sws_getContext(
                info.width,
                info.height,
                sourceFormat,
                info.width,
                info.height,
                AV_PIX_FMT_YUV420P,
                SWS_BILINEAR,
                null,
                null,
                null as DoublePointer?,
            ) ?: error("Failed to create FFmpeg YUV scaler")
            scaler = created
            scalerSourceFormat = sourceFormat
            return created
        }

        override fun close() {
            scaler?.let { sws_freeContext(it) }
            av_frame_free(frame)
            av_free(buffer)
        }
    }
}

private const val FrameTimestampToleranceSeconds = 0.001
