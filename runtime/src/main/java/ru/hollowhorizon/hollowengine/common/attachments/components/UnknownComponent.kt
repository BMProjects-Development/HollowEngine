package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT

/** An absent addon's component, retained verbatim until its descriptor is registered again. */
data class UnknownComponent(val typeId: String, val payload: CompoundTag) {
    fun serializer(): KSerializer<Component> = Serializer(typeId)

    private class Serializer(private val typeId: String) : KSerializer<Component> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor = SerialDescriptor(typeId, ForCompoundNBT.descriptor)

        override fun serialize(encoder: Encoder, value: Component) =
            ForCompoundNBT.serialize(encoder, (value as UnknownComponent).payload)

        override fun deserialize(decoder: Decoder): UnknownComponent =
            UnknownComponent(typeId, ForCompoundNBT.deserialize(decoder))
    }

    companion object {
        fun deserializerFor(typeId: String): DeserializationStrategy<Component> = Serializer(typeId)
    }
}
