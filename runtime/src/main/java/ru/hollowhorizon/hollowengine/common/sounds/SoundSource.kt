package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.Serializable
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForResourceLocation
import ru.hollowhorizon.hollowengine.common.utils.rl

/** An event is selected through sounds.json; a resource addresses one file, including its extension. */
@Serializable
sealed interface SoundSource {
    @Serializable
    data class Event(val id: @Serializable(ForResourceLocation::class) ResourceLocation) : SoundSource

    companion object {
        /** Extension-bearing resource names are files. Extensionless names are always events. */
        fun parse(value: String): SoundSource {
            require(value.isNotBlank()) { "Sound source must not be blank" }
            if (value.startsWith("event:")) return Event(value.removePrefix("event:").rl)
            if (value.contains("://")) return MediaSource.Url(value)
            if (value.startsWith("file:")) return MediaSource.File(value.removePrefix("file:"))
            val id = value.removePrefix("resource:").rl
            val suffix = id.path.substringAfterLast('.', "").lowercase()
            if (!value.startsWith("resource:") && !id.path.startsWith("sounds/") &&
                !id.path.startsWith("audio/") && suffix !in fileExtensions) return Event(id)
            val path = when {
                id.path.startsWith("sounds/") -> id.path
                id.path.startsWith("audio/") -> "sounds/" + id.path.removePrefix("audio/")
                else -> "sounds/${id.path}"
            }
            return MediaSource.Resource(id.withPath(path))
        }

        /** Convenience syntax only; resource: accepts every extension supported by a decoder addon. */
        val fileExtensions = setOf("ogg", "wav", "mp3", "flac", "opus", "aac", "m4a", "mp4", "webm", "mkv", "avi", "mov", "aif", "aiff", "wma", "mka", "m3u8")
    }
}
