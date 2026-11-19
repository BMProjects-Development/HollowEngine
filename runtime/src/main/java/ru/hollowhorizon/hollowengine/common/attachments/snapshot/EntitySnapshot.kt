package ru.hollowhorizon.hollowengine.common.attachments.snapshot

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeCollection
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentPersistencePolicy
import ru.hollowhorizon.hollowengine.common.scene.SceneTransformSerializer
import ru.hollowhorizon.hollowengine.common.scene.hasSamePoseAs
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufDecoder
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufEncoder

@Serializable
sealed class Snapshot {
    abstract val components: List<@Polymorphic Any>
    abstract val modelPose: TrsTransformF?

    fun componentById(): LinkedHashMap<ResourceLocation, Any> =
        LinkedHashMap<ResourceLocation, Any>().apply {
            components.forEach { component ->
                val id = ComponentDescriptorRegistry.idFor(component::class)
                    ?: error("Component descriptor not found for ${component::class.qualifiedName}")
                put(id, component)
            }
        }
}

@Serializable
data class EntitySnapshot(
    @Serializable(with = ComponentListSerializer::class)
    override val components: List<@Polymorphic Any> = emptyList(),
    @Serializable(with = SceneTransformSerializer::class)
    override val modelPose: TrsTransformF? = null,
) : Snapshot() {
    @Transient
    var entity: Entity? = null

    fun withEntity(entity: Entity): EntitySnapshot = copy().also { it.entity = entity }

    fun dropLooseOnDeathComponents(): EntitySnapshot = copy(
        components = components.filterNot { component ->
            ComponentDescriptorRegistry.descriptorOrNull(component::class)?.persistencePolicy == ComponentPersistencePolicy.LOOSE_ON_DEATH
        },
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is EntitySnapshot && components == other.components &&
                (modelPose === other.modelPose || modelPose?.hasSamePoseAs(other.modelPose) == true)

    override fun hashCode(): Int {
        val pose = modelPose
        if (pose == null) return 31 * components.hashCode()
        val poseHash = (pose.translation.hashCode() * 31 + pose.rotation.hashCode()) * 31 + pose.scale.hashCode()
        return 31 * components.hashCode() + poseHash
    }
}

object ComponentListSerializer : KSerializer<List<Any>> {
    private val componentSerializer = PolymorphicSerializer(Any::class)

    override val descriptor: SerialDescriptor =
        ListSerializer(componentSerializer).descriptor

    override fun serialize(encoder: Encoder, value: List<Any>) {
        val buffer = (encoder as? FriendlyByteBufEncoder)?.buf
        encoder.encodeCollection(descriptor, value.size) {
            value.forEachIndexed { index, component ->
                val lengthAt = buffer?.writerIndex()?.also { buffer.writeInt(0) }
                encodeSerializableElement(
                    descriptor,
                    index,
                    componentSerializer,
                    component
                )
                if (buffer != null && lengthAt != null) {
                    buffer.setInt(lengthAt, buffer.writerIndex() - lengthAt - Int.SIZE_BYTES)
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): List<Any> {
        val result = mutableListOf<Any>()
        val composite = decoder.beginStructure(descriptor)
        val buffer = (composite as? FriendlyByteBufDecoder)?.input

        fun decodeComponent(index: Int) {
            val endsAt = buffer?.let { it.readInt() + it.readerIndex() }
            try {
                val component = composite.decodeSerializableElement(
                    descriptor,
                    index,
                    PolymorphicSerializer(Any::class)
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
