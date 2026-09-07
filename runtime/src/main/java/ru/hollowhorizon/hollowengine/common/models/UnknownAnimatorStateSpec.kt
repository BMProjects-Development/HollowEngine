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
 * A controller state whose type is not registered in this game: an addon that is missing, disabled, or
 * older than the file.
 *
 * It keeps the state's stored form untouched and writes it back unchanged, so editing a controller
 * without the addon that owns one of its states does not throw that state away. It poses nothing, and
 * the controller simply holds still while it is in one.
 */
class UnknownAnimatorStateSpec(
    /** The `@SerialName` the file used, kept so the state is written back under it. */
    val typeId: String,
    /** Everything the file had for this state. */
    val payload: CompoundTag,
) : AnimationControllerStateSpec() {
    private val common: CommonStateFields =
        runCatching { NBTFormat.deserialize<CommonStateFields, Tag>(payload) }
            .getOrElse { CommonStateFields() }

    override val id: String get() = common.id

    override fun withId(id: String): AnimationControllerStateSpec {
        val renamed = payload.copy()
        renamed.put("id", StringTag.valueOf(id))
        return UnknownAnimatorStateSpec(typeId, renamed)
    }

    fun serializer(): KSerializer<AnimationControllerStateSpec> = Serializer(typeId)

    override fun equals(other: Any?): Boolean =
        this === other || (other is UnknownAnimatorStateSpec && typeId == other.typeId && payload == other.payload)

    override fun hashCode(): Int = 31 * typeId.hashCode() + payload.hashCode()

    override fun toString(): String = "UnknownAnimatorStateSpec($typeId, id=$id)"

    /**
     * Reads and writes the state as the raw tag it was stored as, under the type name it came with.
     */
    private class Serializer(private val typeId: String) : KSerializer<AnimationControllerStateSpec> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = SerialDescriptor(typeId, ForCompoundNBT.descriptor)

        override fun serialize(encoder: Encoder, value: AnimationControllerStateSpec) =
            ForCompoundNBT.serialize(encoder, (value as UnknownAnimatorStateSpec).payload)

        override fun deserialize(decoder: Decoder): UnknownAnimatorStateSpec =
            UnknownAnimatorStateSpec(typeId, ForCompoundNBT.deserialize(decoder))
    }

    companion object {
        /** What [AnimatorStateTypes] falls back to when a file names a type nothing registered. */
        fun deserializerFor(typeId: String): DeserializationStrategy<AnimationControllerStateSpec> =
            Serializer(typeId)
    }
}

/**
 * The part of a state every kind has, read out of a state this build cannot otherwise understand.
 */
@Serializable
internal data class CommonStateFields(val id: String = "unknown")
