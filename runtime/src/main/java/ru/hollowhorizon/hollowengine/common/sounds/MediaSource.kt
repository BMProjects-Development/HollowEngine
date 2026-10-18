package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.Serializable
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForResourceLocation
import ru.hollowhorizon.hollowengine.common.utils.rl

/** A physical media source, independent of whether its audio track or video track is requested. */
@Serializable
sealed interface MediaSource : SoundSource {
    @Serializable data class Resource(val id: @Serializable(ForResourceLocation::class) ResourceLocation) : MediaSource
    @Serializable data class File(val path: String) : MediaSource
    @Serializable data class Url(val uri: String) : MediaSource

    fun externalForm(): String = when (this) {
        is Resource -> "resource:$id"
        is File -> "file:$path"
        is Url -> uri
    }

    companion object {
        fun parse(value: String): MediaSource {
            require(value.isNotBlank()) { "Media source must not be blank" }
            return when {
                value.startsWith("resource:") -> Resource(value.removePrefix("resource:").rl)
                value.contains("://") -> Url(value)
                else -> File(value.removePrefix("file:"))
            }
        }
    }
}
