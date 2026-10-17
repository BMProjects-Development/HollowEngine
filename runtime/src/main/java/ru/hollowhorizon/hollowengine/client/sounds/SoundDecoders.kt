package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.sounds.AudioStream
import net.minecraft.client.sounds.JOrbisAudioStream
import net.minecraft.server.packs.resources.ResourceProvider
import ru.hollowhorizon.hollowengine.client.sounds.SoundDecoders.open
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

interface SoundDecoder {
    val extensions: Set<String>
    fun accepts(source: MediaSource): Boolean = SoundDecoders.extension(source) in extensions
    fun open(source: MediaSource, resources: ResourceProvider): AudioStream

    /**
     * Opens [source] [start] seconds in, or null when this decoder cannot go there, and what comes before is read
     * through instead.
     */
    fun openAt(source: MediaSource, resources: ResourceProvider, start: Double): AudioStream? = null
}

object SoundDecoders {
    private val builtin = object : SoundDecoder {
        override val extensions = setOf("ogg", "wav")
        override fun open(source: MediaSource, resources: ResourceProvider): AudioStream {
            val input = openInput(source, resources)
            if (extension(source) == "wav") return WavSoundStream(input)
            try {
                return JOrbisAudioStream(input)
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        }

        override fun openAt(source: MediaSource, resources: ResourceProvider, start: Double): AudioStream? {
            if (extension(source) != "wav") return null
            return WavSoundStream(openInput(source, resources)).also { it.skipSeconds(start) }
        }
    }

    val extensions: Set<String>
        get() = builtin.extensions + HollowAddonManager.findServices(SoundDecoder::class).flatMap { it.extensions }

    fun extension(source: MediaSource): String = when (source) {
        is MediaSource.Resource -> source.id.path
        is MediaSource.File -> source.path
        is MediaSource.Url -> URI(source.uri).path.orEmpty()

    }.substringAfterLast('.', "").lowercase()

    fun open(source: MediaSource, resources: ResourceProvider): AudioStream {

        val extension = extension(source)
        val addon = HollowAddonManager.findServices(SoundDecoder::class).lastOrNull { it.accepts(source) }
        if (extension in builtin.extensions) {
            try { return builtin.open(source, resources) }
            catch (error: Exception) {
                if (addon == null) throw error
                try { return addon.open(source, resources) }
                catch (fallback: Exception) { fallback.addSuppressed(error); throw fallback }
            }
        }
        return (addon ?: error("Unsupported sound format '$extension'. Install the HollowEngine Media addon: $source"))
            .open(source, resources)
    }

    /** [source] opened [start] seconds in by whichever decoder [open] would pick, or null when it cannot seek. */
    fun openAt(source: MediaSource, resources: ResourceProvider, start: Double): AudioStream? {
        val decoder = if (extension(source) in builtin.extensions) builtin
        else HollowAddonManager.findServices(SoundDecoder::class).lastOrNull { it.accepts(source) } ?: return null
        return runCatching { decoder.openAt(source, resources, start) }.getOrNull()
    }

    fun openInput(source: MediaSource, resources: ResourceProvider): InputStream = when (source) {
        is MediaSource.Resource -> resources.open(source.id)
        is MediaSource.File -> Files.newInputStream(resolveFile(source.path))
        is MediaSource.Url -> URI(source.uri).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }.getInputStream()

    }

    fun resolveFile(path: String): Path = Path.of(path).let {
        (if (it.isAbsolute) it else DirectoryManager.HOLLOW_ENGINE.resolve(it)).normalize()
    }

    /** Direct buffers follow Minecraft's GC-managed AudioStream ownership contract. */
    fun readComplete(stream: AudioStream): ByteBuffer {
        val chunks = ArrayList<ByteBuffer>()
        var size = 0
        while (true) {
            val chunk = stream.read(16_384)
            if (!chunk.hasRemaining()) break
            size = Math.addExact(size, chunk.remaining())
            chunks += chunk
        }
        return ByteBuffer.allocateDirect(size).apply { chunks.forEach(::put); flip() }
    }
}
