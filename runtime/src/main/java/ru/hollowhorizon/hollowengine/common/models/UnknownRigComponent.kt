package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT

/**
 * An attachment whose type is not registered in this game: an addon that is missing, disabled, or older
 * than the file.
 */
class UnknownRigComponent(
    val typeId: String,
    val payload: CompoundTag,
) {
    val id: String = if (payload.contains("id", Tag.TAG_STRING.toInt())) payload.getString("id") else "unknown"

    fun serializer(): KSerializer<Any> = Serializer(typeId)

    override fun equals(other: Any?): Boolean =
        this === other || (other is UnknownRigComponent && typeId == other.typeId && payload == other.payload)

    override fun hashCode(): Int = 31 * typeId.hashCode() + payload.hashCode()

    override fun toString(): String = "UnknownRigComponent($typeId, id=$id)"

    private class Serializer(private val typeId: String) : KSerializer<Any> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = SerialDescriptor(typeId, ForCompoundNBT.descriptor)

        override fun serialize(encoder: Encoder, value: Any) =
            ForCompoundNBT.serialize(encoder, (value as UnknownRigComponent).payload)

        override fun deserialize(decoder: Decoder): UnknownRigComponent =
            UnknownRigComponent(typeId, ForCompoundNBT.deserialize(decoder))
    }

    companion object {
        fun deserializerFor(typeId: String): DeserializationStrategy<Any> = Serializer(typeId)
    }
}

