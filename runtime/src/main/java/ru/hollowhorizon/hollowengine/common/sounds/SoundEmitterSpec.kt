package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForVec3

@Serializable
sealed interface SoundEmitterSpec {
    @Serializable
    data object Listener : SoundEmitterSpec
    @Serializable
    data class Position(
        val position: @Serializable(ForVec3::class) Vec3,
        val velocity: @Serializable(ForVec3::class) Vec3 = Vec3.ZERO,
        val areas: List<SoundArea> = emptyList(),
    ) : SoundEmitterSpec
    @Serializable
    data class Entity(val id: Int, val offset: @Serializable(ForVec3::class) Vec3 = Vec3.ZERO) : SoundEmitterSpec
    @Serializable
    data class Rig(val entityId: Int, val attachmentId: String, val timeoutSeconds: Float = 5f, val modelNodeId: String? = null) : SoundEmitterSpec {
        init { require(timeoutSeconds.isFinite() && timeoutSeconds >= 0f) }
    }
    @Serializable
    data class Effect(val handle: Int, val timeoutSeconds: Float = 5f) : SoundEmitterSpec {
        init { require(timeoutSeconds.isFinite() && timeoutSeconds >= 0f) }
    }
    /** A stable attachment id published on the client by a renderer or another system. */
    @Serializable
    data class Attachment(val id: String, val timeoutSeconds: Float = 5f) : SoundEmitterSpec {
        init { require(timeoutSeconds.isFinite() && timeoutSeconds >= 0f) }
    }
}
