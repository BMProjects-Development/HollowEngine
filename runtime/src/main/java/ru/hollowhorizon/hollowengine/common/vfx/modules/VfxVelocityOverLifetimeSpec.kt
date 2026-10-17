package ru.hollowhorizon.hollowengine.common.vfx.modules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.vfx.*

/**
 * An extra velocity added while integrating, on top of the particle own.
 */
@Serializable
@SerialName("hollowengine:vfx/velocity_over_lifetime")
data class VfxVelocityOverLifetimeSpec(
    override val id: String = "velocity_over_lifetime",
    override val enabled: Boolean = true,
    val velocity: VfxVec3Value = VfxVec3Value.ZERO,
    val space: VfxSimulationSpace = VfxSimulationSpace.LOCAL,
) : VfxModuleSpec() {
    override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)

    override fun expressions(): List<String> = velocity.sources()

    override fun animatables() = listOf(
        VfxModuleAnimatable("velocity", "hollowengine.gui.vfx.velocity", VfxAnimatableKind.VEC3) {
            (it as VfxVelocityOverLifetimeSpec).velocity.constants()
        },
    )
}