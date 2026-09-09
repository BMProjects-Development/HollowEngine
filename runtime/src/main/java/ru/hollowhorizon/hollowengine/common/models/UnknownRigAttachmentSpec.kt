package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize

/**
 * An attachment whose type is not registered in this game: an addon that is missing, disabled, or older
 * than the file.
 */
class UnknownRigAttachmentSpec(
    val typeId: String,
    val payload: CompoundTag,
) : RigAttachmentSpec() {
    private val common: CommonAttachmentFields =
        runCatching { NBTFormat.deserialize<CommonAttachmentFields, Tag>(payload) }
            .getOrElse { CommonAttachmentFields() }

    override val id: String get() = common.id

    override fun withId(id: String): RigAttachmentSpec {
        val renamed = payload.copy()
        renamed.put("id", StringTag.valueOf(id))
        return UnknownRigAttachmentSpec(typeId, renamed)
    }

    fun serializer(): KSerializer<RigAttachmentSpec> = Serializer(typeId)

    override fun equals(other: Any?): Boolean =
        this === other || (other is UnknownRigAttachmentSpec && typeId == other.typeId && payload == other.payload)

    override fun hashCode(): Int = 31 * typeId.hashCode() + payload.hashCode()

    override fun toString(): String = "UnknownRigAttachmentSpec($typeId, id=$id)"

    private class Serializer(private val typeId: String) : KSerializer<RigAttachmentSpec> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = SerialDescriptor(typeId, ForCompoundNBT.descriptor)

        override fun serialize(encoder: Encoder, value: RigAttachmentSpec) =
            ForCompoundNBT.serialize(encoder, (value as UnknownRigAttachmentSpec).payload)

        override fun deserialize(decoder: Decoder): UnknownRigAttachmentSpec =
            UnknownRigAttachmentSpec(typeId, ForCompoundNBT.deserialize(decoder))
    }

    companion object {
        fun deserializerFor(typeId: String): DeserializationStrategy<RigAttachmentSpec> = Serializer(typeId)
    }
}

@Serializable
internal data class CommonAttachmentFields(val id: String = "unknown")
