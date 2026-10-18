package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.vfx.VfxAnimatables.scalar

/**
 * A sound the effect plays where the node is, from the moment the node turns on. It keeps time with the effect: it
 * stands while the effect is held and goes where a preview's playhead goes.
 *
 * [volume] and [pitch] multiply those of [playback], and are the place for expressions and timeline tracks.
 */
@Serializable
@SerialName("hollowengine:vfx/sound")
data class VfxSoundSpec(
    override val id: String = newVfxNodeId("sound"),
    override val name: String = "Sound",
    override val enabled: Boolean = true,
    override val transform: VfxTransform = VfxTransform.IDENTITY,
    override val children: List<VfxNodeSpec> = emptyList(),
    val source: String = "",
    val volume: VfxValue = VfxValue.ONE,
    val pitch: VfxValue = VfxValue.ONE,
    val playback: SoundPlayback = SoundPlayback(),
) : VfxNodeSpec() {
    override fun withCommon(
        id: String,
        name: String,
        enabled: Boolean,
        transform: VfxTransform,
        children: List<VfxNodeSpec>,
    ) = copy(id = id, name = name, enabled = enabled, transform = transform, children = children)

    override fun expressions(): List<String> = volume.sources() + pitch.sources()

    override fun animatables(): List<VfxAnimatable> = listOf(
        scalar(VfxProperty.SOUND_VOLUME, "sound_volume", volume),
        scalar(VfxProperty.SOUND_PITCH, "sound_pitch", pitch),
    )
}
