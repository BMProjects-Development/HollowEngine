package ru.hollowhorizon.hollowengine.addons.media.decode

import net.minecraft.client.sounds.AudioStream
import net.minecraft.server.packs.resources.ResourceProvider
import ru.hollowhorizon.hollowengine.client.sounds.SoundDecoder

import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class FFmpegSoundDecoder : SoundDecoder, AutoCloseable {
    private val closed = AtomicBoolean()
    private val streams = ConcurrentHashMap.newKeySet<AudioStream>()
    override val extensions = setOf("mp3", "flac", "opus", "aac", "m4a", "mp4", "webm", "mkv", "avi", "mov", "aif", "aiff", "wma", "mka")
    override fun accepts(source: MediaSource) = true // FFmpeg probes the container, including extensionless URLs.
    override fun open(source: MediaSource, resources: ResourceProvider): AudioStream {
        check(!closed.get()) { "Media addon is unloading" }
        val stream = FFmpegSoundStream(FFmpegInput(source, resources))
        val owned = object : AudioStream {
            override fun getFormat() = stream.format
            override fun read(size: Int) = stream.read(size)
            override fun close() { streams.remove(this); stream.close() }
        }
        streams += owned
        if (closed.get()) { owned.close(); error("Media addon was unloaded while opening audio") }
        return owned
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        streams.toList().forEach(AudioStream::close)
    }
}
