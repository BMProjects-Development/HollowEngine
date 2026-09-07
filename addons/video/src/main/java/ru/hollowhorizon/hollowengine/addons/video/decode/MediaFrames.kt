package ru.hollowhorizon.hollowengine.addons.video.decode

import java.nio.ByteBuffer

data class VideoStreamInfo(
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
    val videoStreamIndex: Int,
    val audioStreamIndex: Int,
) {
    val hasAudio: Boolean get() = audioStreamIndex >= 0
}

enum class VideoPixelFormat {
    YUV420P,
    NV12,
}

class YuvVideoFrame(
    val data: ByteBuffer,
    val format: VideoPixelFormat,
    val width: Int,
    val height: Int,
    val timestampSeconds: Double,
    val fullRange: Boolean,
    val bt709: Boolean,
    private val release: (ByteBuffer) -> Unit,
) : AutoCloseable {
    val chromaWidth: Int get() = (width + 1) / 2
    val chromaHeight: Int get() = (height + 1) / 2
    val lumaBytes: Int get() = width * height
    val chromaPlaneBytes: Int get() = chromaWidth * chromaHeight

    override fun close() = release(data)

    companion object {
        fun packedSize(width: Int, height: Int): Int {
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            return width * height + 2 * chromaWidth * chromaHeight
        }
    }
}

data class AudioChunk(
    val pcm: ByteBuffer,
    val sampleRate: Int,
    val channels: Int,
    val timestampSeconds: Double,
    val durationSeconds: Double,
    private val release: (ByteBuffer) -> Unit,
) : AutoCloseable {
    override fun close() = release(pcm)
}
