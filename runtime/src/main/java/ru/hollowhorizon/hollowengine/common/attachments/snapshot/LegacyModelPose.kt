package ru.hollowhorizon.hollowengine.common.attachments.snapshot

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForQuatF
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForVec3f

/** Reads model placement from entity NBT and snapshots written before node poses. */
@Serializable
@SerialName("hollowengine:transform")
internal class LegacyModelPose(
    val transform: @Serializable(LegacyTrsSerializer::class) TrsTransformF = TrsTransformF(),
)

private object LegacyTrsSerializer : KSerializer<TrsTransformF> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("hollowengine:transform") {
        element("translation", ForVec3f.descriptor)
        element("rotation", ForQuatF.descriptor)
        element("scale", ForVec3f.descriptor)
    }

    override fun serialize(encoder: Encoder, value: TrsTransformF) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, ForVec3f, Vec3f(value.translation))
            encodeSerializableElement(descriptor, 1, ForQuatF, QuatF(value.rotation))
            encodeSerializableElement(descriptor, 2, ForVec3f, Vec3f(value.scale))
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): TrsTransformF {
        val dec = decoder.beginStructure(descriptor)

        if (dec.decodeSequentially()) {
            val translation = dec.decodeSerializableElement(descriptor, 0, ForVec3f)
            val rotation = dec.decodeSerializableElement(descriptor, 1, ForQuatF)
            val scale3d = dec.decodeSerializableElement(descriptor, 2, ForVec3f)
            dec.endStructure(descriptor)
            return TrsTransformF().apply {
                translate(translation)
                rotate(rotation)
                scale(scale3d)
            }
        }

        var translation: Vec3f? = null
        var rotation: QuatF? = null
        var scale3d: Vec3f? = null

        loop@ while (true) {
            when (val index = dec.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> translation = dec.decodeSerializableElement(descriptor, index, ForVec3f)
                1 -> rotation = dec.decodeSerializableElement(descriptor, index, ForQuatF)
                2 -> scale3d = dec.decodeSerializableElement(descriptor, index, ForVec3f)

                else -> throw SerializationException("Unknown legacy model pose field index: $index")
            }
        }
        dec.endStructure(descriptor)

        return TrsTransformF().apply {
            translate(translation ?: Vec3f.ZERO)
            rotate(rotation ?: QuatF.IDENTITY)
            scale(scale3d ?: Vec3f.ONES)
        }
    }
}
