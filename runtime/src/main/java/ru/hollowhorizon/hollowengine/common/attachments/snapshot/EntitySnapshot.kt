package ru.hollowhorizon.hollowengine.common.attachments.snapshot

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeCollection
import net.minecraft.resources.ResourceLocation
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentPersistencePolicy
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufDecoder
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufEncoder
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat

@Serializable
sealed class Snapshot {
    abstract val components: List<@Polymorphic Component>

    fun componentById(): LinkedHashMap<ResourceLocation, Component> =
        LinkedHashMap<ResourceLocation, Component>().apply {
            components.forEach { component ->
                val id = ComponentDescriptorRegistry.idOf(component)
                put(id, component)
            }
        }
}

@Serializable
data class EntitySnapshot(
    @Serializable(with = ComponentListSerializer::class)
    override val components: List<@Polymorphic Component> = emptyList(),
) : Snapshot() {
    @Transient
    var entity: Entity? = null

    fun withEntity(entity: Entity): EntitySnapshot = copy().also { it.entity = entity }

    fun dropLooseOnDeathComponents(): EntitySnapshot = copy(
        components = components.filterNot { component ->
            ComponentDescriptorRegistry.descriptorOrNull(component::class)?.persistencePolicy == ComponentPersistencePolicy.LOOSE_ON_DEATH
        },
    )
}

object ComponentListSerializer : KSerializer<List<Component>> {
    private val componentSerializer = PolymorphicSerializer(Component::class)
    /** Each wire entry is self-describing, so missing addon schemas never consume the next entry's bytes. */
    private val wireSerializer = object : KSerializer<Component> {
        override val descriptor = componentSerializer.descriptor

        override fun serialize(encoder: Encoder, value: Component) {
            val tag = NBTFormat(encoder.serializersModule).serialize(componentSerializer, value)
            (encoder as FriendlyByteBufEncoder).buf.writeNbt(tag as CompoundTag)
        }

        override fun deserialize(decoder: Decoder): Component {
            val tag = checkNotNull((decoder as FriendlyByteBufDecoder).input.readNbt())
            return NBTFormat(decoder.serializersModule).deserialize(componentSerializer, tag)
        }
    }

    override val descriptor: SerialDescriptor =
        ListSerializer(componentSerializer).descriptor

    override fun serialize(encoder: Encoder, value: List<Component>) {
        val buffer = (encoder as? FriendlyByteBufEncoder)?.buf
        encoder.encodeCollection(descriptor, value.size) {
            value.forEachIndexed { index, component ->
                val lengthAt = buffer?.writerIndex()?.also { buffer.writeInt(0) }
                encodeSerializableElement(
                    descriptor,
                    index,
                    if (buffer == null) componentSerializer else wireSerializer,
                    component
                )
                if (buffer != null && lengthAt != null) {
                    buffer.setInt(lengthAt, buffer.writerIndex() - lengthAt - Int.SIZE_BYTES)
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): List<Component> {
        val result = mutableListOf<Component>()
        val composite = decoder.beginStructure(descriptor)
        val buffer = (composite as? FriendlyByteBufDecoder)?.input

        fun decodeComponent(index: Int) {
            val endsAt = buffer?.let { it.readInt() + it.readerIndex() }
            try {
                val component = composite.decodeSerializableElement(
                    descriptor,
                    index,
                    if (buffer == null) componentSerializer else wireSerializer,
                )
                result += component
            } catch (e: Exception) {
                HollowEngine.LOGGER.warn(
                    "Failed to deserialize component at index $index: ${e.message}",
                    e
                )
            } finally {
                endsAt?.let(buffer::readerIndex)
            }
        }

        val size = composite.decodeCollectionSize(descriptor)

        if (size >= 0) {
            for (index in 0 until size) decodeComponent(index)
        } else {
            while (true) {
                val index = composite.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                decodeComponent(index)
            }
        }

        composite.endStructure(descriptor)
        return result
    }
}
