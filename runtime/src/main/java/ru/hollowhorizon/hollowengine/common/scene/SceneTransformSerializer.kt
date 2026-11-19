package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForQuatF

/** The saved and network representation of a node's local transform. */
internal object SceneTransformSerializer : KSerializer<TrsTransformF> {
    @Serializable
    private data class Fields(
        val position: Vec3f = Vec3f.ZERO,
        @Serializable(with = ForQuatF::class) val rotation: QuatF = QuatF.IDENTITY,
        val scale: Vec3f = Vec3f.ONES,
    )

    override val descriptor = Fields.serializer().descriptor

    override fun serialize(encoder: Encoder, value: TrsTransformF) {
        Fields.serializer().serialize(encoder, Fields(value.translation, value.rotation, value.scale))
    }

    override fun deserialize(decoder: Decoder): TrsTransformF {
        val fields = Fields.serializer().deserialize(decoder)
        return TrsTransformF().setCompositionOf(fields.position, fields.rotation, fields.scale)
    }
}

internal fun TrsTransformF.hasSamePoseAs(other: TrsTransformF?): Boolean =
    other != null && translation == other.translation && rotation == other.rotation && scale == other.scale

internal fun TrsTransformF.hasIdentityPose(): Boolean =
    translation == Vec3f.ZERO && rotation == QuatF.IDENTITY && scale == Vec3f.ONES

internal fun TrsTransformF.copyValidatedPoseTo(destination: TrsTransformF): TrsTransformF? {
    val norm = rotation.dot(rotation)
    if (!norm.isFinite() || norm == 0f || !translation.x.isFinite() || !translation.y.isFinite() ||
        !translation.z.isFinite() || !scale.x.isFinite() || !scale.y.isFinite() || !scale.z.isFinite()
    ) return null
    return destination.setCompositionOf(translation, rotation.normed(), scale)
}
