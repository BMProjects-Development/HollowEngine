package ru.hollowhorizon.hollowengine.client.decals

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.VideoApi
import ru.hollowhorizon.hollowengine.api.VideoPlaybackOptions
import ru.hollowhorizon.hollowengine.api.VideoPlayer
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import java.nio.ByteBuffer

/**
 * What decals draw with: a texture of the loaded packs, or the frame a video is on. A video plays on and on, without
 * sound, for as long as some decal shows it, and is let go of a little after the last one stops.
 */
internal object DecalTextures {
    private class Playing(val player: VideoPlayer, var usedAt: Long)

    private val videos = HashMap<String, Playing>()

    /** Sources a player could not be opened for, so they show the missing texture rather than being tried each frame. */
    private val broken = HashSet<String>()

    /**
     * The texture [spec] draws with this frame: the missing texture when there is none to show, or null while a video
     * has not shown its first frame yet.
     */
    fun of(spec: DecalSpec): Int? {
        if (spec.texture.isBlank()) return missing()
        if (spec.isVideo) return if (spec.texture in broken) missing() else video(spec.texture)
        val location = ResourceLocation.tryParse(spec.texture) ?: return missing()
        return Minecraft.getInstance().textureManager.getTexture(location).id
    }

    /** Where a video a decal names is: a resource of the packs unless it says it is a file or a link. */
    private fun sourceOf(text: String): MediaSource = when {
        text.startsWith("resource:") || text.startsWith("file:") || text.contains("://") -> MediaSource.parse(text)
        else -> ResourceLocation.tryParse(text)?.let(MediaSource::Resource) ?: MediaSource.File(text)
    }

    private fun missing(): Int = Minecraft.getInstance().textureManager.getTexture(MissingTextureAtlasSprite.getLocation()).id

    private fun video(source: String): Int? {
        val frame = TickHandler.renderFrame
        val playing = videos[source] ?: run {
            val api = VideoApi.find() ?: return missing()
            val player = runCatching { api.createPlayer(sourceOf(source), Options) }
                .onFailure { HollowEngine.LOGGER.warn("Decal video '{}' cannot be played: {}", source, it.message) }
                .getOrNull()
            if (player == null) {
                broken += source
                return missing()
            }
            Playing(player, frame).also { videos[source] = it }
        }
        playing.usedAt = frame
        val player = playing.player
        if (player.error != null) {
            HollowEngine.LOGGER.warn("Decal video '{}' failed: {}", source, player.error?.message)
            broken += source
            videos.remove(source)?.player?.close()
            return missing()
        }
        if (player.ended) {
            player.seek(0.0)
            player.play()
        }
        val texture = player.texture ?: return null
        return Minecraft.getInstance().textureManager.getTexture(texture).id
    }

    /** Height over width of the picture [spec] shows, one while it is not known yet. */
    fun aspect(spec: DecalSpec): Float {
        if (spec.texture.isBlank()) return 1f
        if (spec.isVideo) {
            val player = videos[spec.texture]?.player ?: return 1f
            return if (player.videoWidth > 0 && player.videoHeight > 0) player.videoHeight.toFloat() / player.videoWidth else 1f
        }
        return aspects.getOrPut(spec.texture) { imageAspect(spec.texture) }
    }

    private val aspects = HashMap<String, Float>()

    /** Reads the size of a picture from its header alone: a PNG keeps width and height right after its signature. */
    private fun imageAspect(texture: String): Float {
        val location = ResourceLocation.tryParse(texture) ?: return 1f
        return runCatching {
            Minecraft.getInstance().resourceManager.open(location).use { stream ->
                val header = stream.readNBytes(PNG_HEADER)
                if (header.size < PNG_HEADER) return@use 1f
                val buffer = ByteBuffer.wrap(header)
                val width = buffer.getInt(PNG_WIDTH_AT)
                val height = buffer.getInt(PNG_WIDTH_AT + 4)
                if (width > 0 && height > 0) height.toFloat() / width else 1f
            }
        }.getOrDefault(1f)
    }

    /** A pack reload may change any picture. */
    fun clearAspects() {
        aspects.clear()
        broken.clear()
    }

    /** Lets go of the videos no decal has shown for a while; once a frame. */
    fun release() {
        if (videos.isEmpty()) return
        val frame = TickHandler.renderFrame
        videos.entries.removeIf { (_, playing) ->
            val stale = frame - playing.usedAt > KEEP_FRAMES
            if (stale) playing.player.close()
            stale
        }
    }

    private val Options = VideoPlaybackOptions(closeOnEnd = false, volume = 0f)

    /** How many frames a video no decal shows keeps playing, so a decal that flickers out does not restart it. */
    private const val KEEP_FRAMES = 120L

    /** How much of a PNG holds its size, and where the width is: past the signature and the header chunk name. */
    private const val PNG_HEADER = 24
    private const val PNG_WIDTH_AT = 16
}
