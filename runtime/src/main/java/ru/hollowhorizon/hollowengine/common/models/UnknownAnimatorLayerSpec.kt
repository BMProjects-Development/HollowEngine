package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize

/**
 * A layer whose type is not registered in this game: an addon that is missing, disabled, or older than
 * the file.
 *
 * It keeps the layer's stored form untouched and writes it back unchanged, so opening and saving a
 * `.animator` without the addon that owns one of its layers does not break that layer.
 */
class UnknownAnimatorLayerSpec(
    val typeId: String,
    val payload: CompoundTag,
) : AnimatorLayerSpec() {
    private val common: CommonLayerFields =
        runCatching { NBTFormat.deserialize<CommonLayerFields, Tag>(payload) }
            .getOrElse { CommonLayerFields() }

    override val id: String get() = common.id
    override val weight: AnimationExpression get() = common.weight
    override val priority: Int get() = common.priority
    override val blendMode: LayerBlendMode get() = common.blendMode
    override val mask: BoneMask get() = common.mask
    override val fadeIn: Float get() = common.fadeIn
    override val fadeOut: Float get() = common.fadeOut

    override fun withCommon(
        id: String,
        weight: AnimationExpression,
        priority: Int,
        blendMode: LayerBlendMode,
        mask: BoneMask,
        fadeIn: Float,
        fadeOut: Float,
    ): AnimatorLayerSpec {
        val fields = CommonLayerFields(id, weight, priority, blendMode, mask, fadeIn, fadeOut)
        val written = NBTFormat.serialize(fields) as? CompoundTag ?: return this
        val merged = payload.copy()
        CommonLayerFields.KEYS.forEach(merged::remove)
        written.allKeys.forEach { key -> merged.put(key, written.get(key)!!) }
        return UnknownAnimatorLayerSpec(typeId, merged)
    }

    override fun expressions(): List<AnimationExpression> = emptyList()

    fun serializer(): KSerializer<AnimatorLayerSpec> = Serializer(typeId)

    override fun equals(other: Any?): Boolean =
        this === other || (other is UnknownAnimatorLayerSpec && typeId == other.typeId && payload == other.payload)

    override fun hashCode(): Int = 31 * typeId.hashCode() + payload.hashCode()

    override fun toString(): String = "UnknownAnimatorLayerSpec($typeId, id=$id)"

    private class Serializer(private val typeId: String) : KSerializer<AnimatorLayerSpec> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = SerialDescriptor(typeId, ForCompoundNBT.descriptor)

        override fun serialize(encoder: Encoder, value: AnimatorLayerSpec) =
            ForCompoundNBT.serialize(encoder, (value as UnknownAnimatorLayerSpec).payload)

        override fun deserialize(decoder: Decoder): UnknownAnimatorLayerSpec =
            UnknownAnimatorLayerSpec(typeId, ForCompoundNBT.deserialize(decoder))
    }

    companion object {
        fun deserializerFor(typeId: String): DeserializationStrategy<AnimatorLayerSpec> = Serializer(typeId)
    }
}

@Serializable
internal data class CommonLayerFields(
    val id: String = "unknown",
    val weight: AnimationExpression = AnimationExpression.ONE,
    val priority: Int = 0,
    val blendMode: LayerBlendMode = LayerBlendMode.Override,
    val mask: BoneMask = BoneMask.full(),
    val fadeIn: Float = 0f,
    val fadeOut: Float = 0f,
) {
    companion object {
        val KEYS = listOf("id", "weight", "priority", "blendMode", "mask", "fadeIn", "fadeOut")
    }
}
