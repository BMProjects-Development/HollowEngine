package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.MontageLayerSpec

/**
 * The animations gameplay asked this entity to play.
 */
@Registerable
@Syncable
@Serializable
@SerialName("hollowengine:animations")
data class AnimationsComponent(
    val clips: List<ClipAnimationLayerSpec> = emptyList(),
    val montages: List<MontageLayerSpec> = emptyList(),
)

fun AnimationsComponent.withMontage(montage: MontageLayerSpec): AnimationsComponent =
    copy(montages = montages.filterNot { it.id == montage.id } + montage)

fun AnimationsComponent.withClip(clip: ClipAnimationLayerSpec): AnimationsComponent =
    copy(clips = clips.filterNot { it.id == clip.id } + clip)

fun AnimationsComponent.withoutLayer(layerId: String): AnimationsComponent =
    copy(clips = clips.filterNot { it.id == layerId })

fun AnimationsComponent.withoutClip(animation: String): AnimationsComponent =
    copy(clips = clips.filterNot { it.animation == animation })
