package ru.hollowhorizon.hollowengine.client.sounds

import com.mojang.blaze3d.audio.SoundBuffer
import net.minecraft.Util
import net.minecraft.client.sounds.AudioStream
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundRepeat
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Playback tokens route per-instance streams through Minecraft without registering synthetic assets. */
object SoundStreams {
    private val streams = ConcurrentHashMap<ResourceLocation, Entry>()

    fun handles(id: ResourceLocation) = id.path.substringAfterLast('.') in SoundSource.fileExtensions + SoundDecoders.extensions

    fun register(id: ResourceLocation, source: MediaSource, settings: SoundPlayback, spatial: Boolean, failure: (Throwable) -> Unit) {
        streams[id] = Entry(source, settings, spatial, failure)
    }

    fun remove(id: ResourceLocation) { streams.remove(id)?.close() }

    fun stream(id: ResourceLocation, resources: ResourceProvider, looping: Boolean): CompletableFuture<AudioStream>? {
        val entry = streams[id]
        if (entry == null && id.namespace == "hollowengine" && id.path.startsWith("playback/"))
            return CompletableFuture.failedFuture(IllegalStateException("Playback owner was released"))
        if (entry == null && !handles(id)) return null
        return CompletableFuture.supplyAsync({
            if (entry != null) entry.open(resources) else PlaybackSoundStream(
                { SoundDecoders.open(MediaSource.Resource(id), resources) },
                SoundPlayback(repeat = if (looping) SoundRepeat.Forever else SoundRepeat.Once),
                spatial = false,
            )
        }, Util.backgroundExecutor())
    }

    fun complete(id: ResourceLocation, resources: ResourceProvider): CompletableFuture<SoundBuffer>? {
        if (!handles(id)) return null
        return CompletableFuture.supplyAsync({
            SoundDecoders.open(MediaSource.Resource(id), resources).use {
                SoundBuffer(SoundDecoders.readComplete(it), it.format)
            }
        }, Util.backgroundExecutor())
    }

    private class Entry(val source: MediaSource, val settings: SoundPlayback, val spatial: Boolean, val failure: (Throwable) -> Unit) {
        private var closed = false
        private var stream: AudioStream? = null
        fun open(resources: ResourceProvider): AudioStream {
            synchronized(this) { check(!closed) { "Sound was stopped before its stream opened" } }
            val opened = try { PlaybackSoundStream({ SoundDecoders.open(source, resources) }, settings, spatial) }
            catch (error: Exception) { failure(error); throw error }
            synchronized(this) {
                if (closed) { opened.close(); error("Sound was stopped while opening its stream") }
                stream = opened
            }
            return object : AudioStream {
                override fun getFormat() = opened.format
                override fun read(size: Int) = try { opened.read(size) }
                catch (error: Exception) {
                    failure(error)
                    opened.close()
                    ByteBuffer.allocate(0)
                }
                override fun close() = opened.close()
            }
        }
        @Synchronized fun close() {
            closed = true
            stream?.let { opened -> CompletableFuture.runAsync({ opened.close() }, Util.backgroundExecutor()) }
            stream = null
        }
    }
}
