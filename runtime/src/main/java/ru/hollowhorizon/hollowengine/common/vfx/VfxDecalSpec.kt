package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxAnimatables.color
import ru.hollowhorizon.hollowengine.common.vfx.VfxAnimatables.scalar

/**
 * A decal the effect casts down its node: once in its own place, or on every particle of the emitter it sits right
 * under, sized, turned and colored as the particle is, as blood splashes or footprints are.
 *
 * [opacity] and [tint] multiply those of [decal], and are the place for expressions and timeline tracks.
 */
@Serializable
@SerialName("hollowengine:vfx/decal")
data class VfxDecalSpec(
    override val id: String = newVfxNodeId("decal"),
    override val name: String = "Decal",
    override val enabled: Boolean = true,
    override val transform: VfxTransform = VfxTransform.IDENTITY,
    override val children: List<VfxNodeSpec> = emptyList(),
    val decal: DecalSpec = DecalSpec(),
    val opacity: VfxValue = VfxValue.ONE,
    val tint: VfxColorValue = VfxColorValue.WHITE,
    override val particle: VfxAppearance = VfxAppearance(size = VfxVec3Value.all(1f)),
) : VfxNodeSpec(), VfxParticleRendererSpec {
    override fun withCommon(
        id: String,
        name: String,
        enabled: Boolean,
        transform: VfxTransform,
        children: List<VfxNodeSpec>,
    ) = copy(id = id, name = name, enabled = enabled, transform = transform, children = children)

    override fun withParticle(particle: VfxAppearance) = copy(particle = particle)

    override fun expressions(): List<String> = opacity.sources() + tint.sources() + particle.expressions()

    override fun animatables(): List<VfxAnimatable> = listOf(
        scalar(VfxProperty.DECAL_OPACITY, "decal_opacity", opacity),
        color(VfxProperty.TINT, "tint", tint),
    ) + particle.animatables()
}
