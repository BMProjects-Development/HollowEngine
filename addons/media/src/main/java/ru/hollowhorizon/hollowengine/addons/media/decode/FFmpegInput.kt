package ru.hollowhorizon.hollowengine.addons.media.decode

import net.minecraft.server.packs.resources.ResourceProvider
import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.avformat.AVFormatContext.AVFMT_FLAG_CUSTOM_IO
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import ru.hollowhorizon.hollowengine.client.sounds.SoundDecoders
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** AVIO reads pack resources incrementally and can seek by reopening a resource, without a temp file. */
class FFmpegInput(source: MediaSource, resources: ResourceProvider) : AutoCloseable {
    private var input: InputStream? = null
    private var io: AVIOContext? = null
    private var reader: Read_packet_Pointer_BytePointer_int? = null
    private var seeker: Seek_Pointer_long_int? = null
    private var offset = 0L
    private var length: Long? = null
    private var failure: Throwable? = null
    private val closed = AtomicBoolean()
    @Volatile
    private var progress = System.nanoTime()
    private var interrupt: AVIOInterruptCB.Callback_Pointer? = null
    private val reopen = { SoundDecoders.openInput(source, resources) }
    val format: AVFormatContext

    init {
        FFmpegMedia.preload()
        progress()
        val context = avformat_alloc_context() ?: error("Could not allocate media input")
        format = context
        try {
            interrupt = object : AVIOInterruptCB.Callback_Pointer() {
                override fun call(opaque: Pointer?): Int =
                    if (closed.get() || System.nanoTime() - progress > 15_000_000_000L) 1 else 0
            }
            context.interrupt_callback().callback(interrupt)
            val nativeSource = when (source) {
                is MediaSource.File -> SoundDecoders.resolveFile(source.path).toString()
                is MediaSource.Url -> source.uri
                else -> null
            }
            if (nativeSource == null) {
                input = reopen()
                reader = object : Read_packet_Pointer_BytePointer_int() {
                    override fun call(opaque: Pointer?, target: BytePointer, size: Int): Int = try {
                        val bytes = ByteArray(size)
                        val count = input!!.read(bytes)
                        if (count < 0) {
                            length = offset; AVERROR_EOF
                        } else {
                            target.put(bytes, 0, count); offset += count; count
                        }
                    } catch (error: Exception) {
                        failure = error; -5
                    }
                }
                seeker = object : Seek_Pointer_long_int() {
                    override fun call(opaque: Pointer?, position: Long, whence: Int): Long = try {
                        if (whence and AVSEEK_SIZE != 0) length ?: -38L else {
                            val target = when (whence and 3) {
                                0 -> position
                                1 -> offset + position
                                2 -> (length ?: measureLength()) + position
                                else -> -1L
                            }
                            if (target < 0) -22L else {
                                if (target < offset) {
                                    input!!.close(); input = reopen(); offset = 0
                                }
                                input!!.skipNBytes(target - offset)
                                offset = target
                                offset
                            }
                        }
                    } catch (error: Exception) {
                        failure = error; -5L
                    }
                }
                val buffer = BytePointer(av_malloc(32_768))
                check(!buffer.isNull) { "Could not allocate media IO buffer" }
                io = avio_alloc_context(buffer, 32_768, 0, null, reader, null, seeker)
                if (io == null) {
                    av_free(buffer); error("Could not allocate media IO context")
                }
                context.pb(io).flags(context.flags() or AVFMT_FLAG_CUSTOM_IO)
            }
            check(
                avformat_open_input(
                    context,
                    nativeSource,
                    null,
                    null
                ) >= 0
            ) { "Could not open media source: $source ($failure)" }
            check(
                avformat_find_stream_info(
                    context,
                    null as PointerPointer<BytePointer>?
                ) >= 0
            ) { "Could not inspect media source: $source" }
        } catch (error: Throwable) {
            close(); throw error
        }
    }

    fun progress() {
        progress = System.nanoTime()
    }

    private fun measureLength(): Long = reopen().use { stream ->
        var size = 0L
        val bytes = ByteArray(32_768)
        while (true) {
            val count = stream.read(bytes); if (count < 0) break; size += count
        }
        size.also { length = it }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        avformat_close_input(format)
        io?.let {
            // FFmpeg may replace this buffer; release the current one before the AVIO context.
            av_free(it.buffer())
            it.buffer(null)
            avio_context_free(it)
        }
        io = null
        try {
            input?.close()
        } finally {
            input = null
            reader?.close(); reader = null
            seeker?.close(); seeker = null
            interrupt?.close(); interrupt = null
        }
    }
}
