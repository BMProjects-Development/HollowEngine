package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.utils.math.*
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForQuatF
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForVec3f

@Registerable
@Syncable
@Serializable
@SerialName("hollowengine:transform")
class TransformComponent(
    val transform: @Serializable(TRSTransformSerializer::class) TrsTransformF = TrsTransformF(),
) {

    constructor(
        translation: Vec3f = Vec3f.ZERO,
        rotation: QuatF = QuatF.IDENTITY,
        scale: Vec3f = Vec3f.ONES,
    ) : this(TrsTransformF().setCompositionOf(translation, rotation, scale))

    val translation: Vec3f get() = transform.translation
    val rotation: QuatF get() = transform.rotation
    val scale: Vec3f get() = transform.scale

    val x: Float get() = translation.x
    val y: Float get() = translation.y
    val z: Float get() = translation.z

    fun copy(
        translation: Vec3f = this.translation,
        rotation: QuatF = this.rotation,
        scale: Vec3f = this.scale,
    ): TransformComponent = TransformComponent(translation, rotation, scale)

    fun withTranslation(translation: Vec3f): TransformComponent =
        copy(translation = Vec3f(translation))

    fun withTranslation(x: Float = this.x, y: Float = this.y, z: Float = this.z): TransformComponent =
        copy(translation = Vec3f(x, y, z))

    fun withRotation(rotation: QuatF): TransformComponent =
        copy(rotation = QuatF(rotation))

    fun withScale(scale: Vec3f): TransformComponent =
        copy(scale = Vec3f(scale))

    fun withUniformScale(scale: Float): TransformComponent =
        copy(scale = Vec3f(scale, scale, scale))

    fun withWorldPosition(position: Vec3): TransformComponent =
        withTranslation(position.x.toFloat(), position.y.toFloat(), position.z.toFloat())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransformComponent) return false
        return translation == other.translation &&
                rotation == other.rotation &&
                scale == other.scale
    }

    override fun hashCode(): Int {
        var result = translation.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + scale.hashCode()
        return result
    }

    override fun toString(): String =
        "TransformComponent(translation=$translation, rotation=$rotation, scale=$scale)"

    companion object {
        fun legacy(
            x: Float = 0f,
            y: Float = 0f,
            z: Float = 0f,
            yaw: Float = 0f,
            pitch: Float = 0f,
            scale: Float = 1f,
        ): TransformComponent {
            val rotation = MutableQuatF().setIdentity()
                .rotateByEulers(Vec3f(pitch, yaw, 0f), EulerOrder.YXZ)
            return TransformComponent(
                translation = Vec3f(x, y, z),
                rotation = rotation,
                scale = Vec3f(scale, scale, scale),
            )
        }
    }
}

object TRSTransformSerializer : KSerializer<TrsTransformF> {
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

                else -> throw SerializationException("Unknown TransformComponent field index: $index")
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