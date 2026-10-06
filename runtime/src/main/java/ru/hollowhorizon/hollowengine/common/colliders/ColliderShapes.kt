package ru.hollowhorizon.hollowengine.common.colliders

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.reflect.KClass

/**
 * What fills a collider's box. The box, its size, offset and turn, belongs to the collider.
 */
@Serializable
abstract class ColliderShapeSpec

@Serializable
@SerialName(BoxShapeSpec.TYPE_ID)
data object BoxShapeSpec : ColliderShapeSpec() {
    const val TYPE_ID = "hollowengine:collider/box"
}

/** One kind of collider shape: how it is stored, and what the editor calls it. */
class ColliderShapeType<S : ColliderShapeSpec>(
    val id: String,
    val specClass: KClass<S>,
    val serializer: KSerializer<S>,
    val titleKey: String,
) {
    val key: ResourceLocation = id.rl
}

/** The kinds of shape a collider may have; addons add their own. */
object ColliderShapeTypes {
    val point = ExtensionPoints.create<ColliderShapeType<*>>("hollowengine:collider/shape_types".rl)

    init {
        point.onChange(TagModuleRevision::invalidate)
        register(ColliderShapeType(BoxShapeSpec.TYPE_ID, BoxShapeSpec::class, BoxShapeSpec.serializer(), "hollowengine.gui.rig_editor.collider.shape.box"))
    }

    fun register(type: ColliderShapeType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<ColliderShapeType<*>> get() = point.extensions

    fun of(spec: ColliderShapeSpec): ColliderShapeType<*>? = all.firstOrNull { it.specClass.isInstance(spec) }

    fun registerInto(builder: SerializersModuleBuilder) {
        builder.polymorphic(ColliderShapeSpec::class) {
            all.forEach { type -> subclass(type) }
            defaultDeserializer { typeId -> typeId?.let(UnknownColliderShapeSpec::deserializerFor) }
        }
        builder.polymorphicDefaultSerializer(ColliderShapeSpec::class) { spec ->
            (spec as? UnknownColliderShapeSpec)?.serializer()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PolymorphicModuleBuilder<ColliderShapeSpec>.subclass(type: ColliderShapeType<*>) =
        subclass(type.specClass as KClass<ColliderShapeSpec>, type.serializer as KSerializer<ColliderShapeSpec>)
}

/**
 * A shape whose type is not registered in this game, as when the addon that owns it is missing. It is
 * written back unchanged, and the collider acts as its whole box until the addon is back.
 */
class UnknownColliderShapeSpec(val typeId: String, val payload: CompoundTag) : ColliderShapeSpec() {
    fun serializer(): KSerializer<ColliderShapeSpec> = Serializer(typeId)

    override fun equals(other: Any?): Boolean =
        this === other || (other is UnknownColliderShapeSpec && typeId == other.typeId && payload == other.payload)

    override fun hashCode(): Int = 31 * typeId.hashCode() + payload.hashCode()

    override fun toString(): String = "UnknownColliderShapeSpec($typeId)"

    private class Serializer(private val typeId: String) : KSerializer<ColliderShapeSpec> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = SerialDescriptor(typeId, ForCompoundNBT.descriptor)

        override fun serialize(encoder: Encoder, value: ColliderShapeSpec) =
            ForCompoundNBT.serialize(encoder, (value as UnknownColliderShapeSpec).payload)

        override fun deserialize(decoder: Decoder): UnknownColliderShapeSpec =
            UnknownColliderShapeSpec(typeId, ForCompoundNBT.deserialize(decoder))
    }

    companion object {
        fun deserializerFor(typeId: String): DeserializationStrategy<ColliderShapeSpec> = Serializer(typeId)
    }
}

/** Puts a shape into the box a collider fills this tick; null when it cannot, and the box stands in for it. */
fun interface ColliderShapeFactory {
    fun place(spec: ColliderShapeSpec, frame: ColliderBox): ColliderVolume?
}

/** How each kind of shape is placed in the world. A kind with no factory, or none at all, is its box. */
object ColliderShapeFactories {
    val point = ExtensionPoints.create<ColliderShapeFactory>("hollowengine:collider/shape_factories".rl)

    private val warned = HashSet<String>()

    fun register(typeId: String, factory: ColliderShapeFactory): ExtensionHandle = point.register(typeId.rl, factory)

    fun place(spec: ColliderShapeSpec, frame: ColliderBox): ColliderVolume {
        if (spec is BoxShapeSpec) return frame
        val type = ColliderShapeTypes.of(spec)
        val placed = type?.let { point.find(it.key) }?.place(spec, frame)
        if (placed == null) warnOnce((spec as? UnknownColliderShapeSpec)?.typeId ?: type?.id ?: spec.toString())
        return placed ?: frame
    }

    private fun warnOnce(typeId: String) {
        if (synchronized(warned) { warned.add(typeId) }) {
            HollowEngine.LOGGER.warn("Collider shape '{}' cannot be placed here; its collider acts as a box", typeId)
        }
    }
}
