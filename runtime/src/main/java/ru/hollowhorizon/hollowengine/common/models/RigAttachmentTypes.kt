package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.CompositeDecoder.Companion.DECODE_DONE
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.*
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.sounds.Sound
import ru.hollowhorizon.hollowengine.common.ui.panels.UiPanelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

// Rig values retain their authored names independently of the active serializer registry.
private val rigTypesByValueClass = object : ClassValue<AtomicReference<RigAttachmentType<*>?>>() {
    override fun computeValue(type: Class<*>) = AtomicReference<RigAttachmentType<*>?>()
}

/**
 * One kind of thing that can hang on a bone: how it is stored, and what the editor calls it.
 */
class RigAttachmentType<S : Any>(
    val id: String,
    val specClass: KClass<S>,
    private val rigId: (S) -> String,
    val serializer: KSerializer<S>,
    val titleKey: String,
    val createDefault: ((id: String) -> S)? = null,
    val allowedOnModel: Boolean = false,
    val namedAcrossRig: Boolean = false,
    val editorColor: Int? = null,
    val requiresMesh: Boolean = false,
    val uniqueOnHolder: Boolean = false,
) {
    val key: ResourceLocation = id.rl

    init {
        rigTypesByValueClass.get(specClass.java).compareAndSet(null, this)
    }

    internal fun instanceId(value: Any): String = rigId(specClass.java.cast(value))
}

object RigAttachmentTypes {
    val point = ExtensionPoints.create<RigAttachmentType<*>>("hollowengine:rig/attachment_types".rl)

    @Volatile
    private var typesByClass: Map<KClass<*>, RigAttachmentType<*>> = emptyMap()

    init {
        point.onChange {
            typesByClass = buildMap {
                for (type in point.extensions) putIfAbsent(type.specClass, type)
            }
            TagModuleRevision.invalidate()
        }

        register(
            RigAttachmentType(
                id = "hollowengine:rig/item",
                specClass = ItemSlotAttachmentSpec::class,
                rigId = ItemSlotAttachmentSpec::id,
                serializer = ItemSlotAttachmentSpec.serializer(),
                titleKey = "hollowengine.gui.rig_editor.kind_item",
                createDefault = { id -> ItemSlotAttachmentSpec(id = id) },
                editorColor = 0xE8C547,
            )
        )
        register(
            RigAttachmentType(
                id = "hollowengine:rig/vfx",
                specClass = VfxComponent::class,
                rigId = VfxComponent::id,
                serializer = VfxComponent.serializer(),
                titleKey = "hollowengine.gui.rig_editor.kind_vfx",
                createDefault = { id -> VfxComponent(id = id) },
                editorColor = 0xFF8A3D,
                allowedOnModel = true,
            )
        )
        register(
            RigAttachmentType(
                id = "hollowengine:rig/model",
                specClass = ModelAttachmentSpec::class,
                rigId = ModelAttachmentSpec::id,
                serializer = ModelAttachmentSpec.serializer(),
                titleKey = "hollowengine.gui.rig_editor.kind_model",
                createDefault = { id -> ModelAttachmentSpec(id = id) },
                editorColor = 0x6FBF8A,
                allowedOnModel = true,
            )
        )
        register(ColliderAttachmentSpec.TYPE)
        register(
            RigAttachmentType(
                "hollowengine:rig/sound", Sound::class, Sound::id, Sound.serializer(),
                "hollowengine.gui.rig_editor.kind_sound", { id -> Sound(id = id) },
                allowedOnModel = true, namedAcrossRig = true, editorColor = 0x70BCD8,
            )
        )
        register(RigAttachmentType(
            Decal.TYPE_ID, Decal::class, Decal::id, RigDecalSerializer,
            "hollowengine.gui.rig_editor.kind_decal", { Decal(id = it) },
            allowedOnModel = true, editorColor = 0xC77DDB,
        ))
        register(UiPanelAttachmentSpec.TYPE)
        register(IkTargetSpec.TYPE)
        register(IkChainSpec.TYPE)
        register(MeshExtrusionSpec.TYPE)
    }

    fun register(type: RigAttachmentType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<RigAttachmentType<*>> get() = point.extensions

    fun of(spec: Any): RigAttachmentType<*>? = typesByClass[spec::class]

}

/** Keeps the old rig discriminators local to the rig and montage formats. */
internal object RigComponentSerializer : KSerializer<Any> {
    override val descriptor = PolymorphicSerializer(Any::class).descriptor

    override fun serialize(encoder: Encoder, value: Any) {
        val serializer = (value as? UnknownRigComponent)?.serializer() ?: serializerFor(value)
        val name = serializer.descriptor.serialName
        if (encoder is JsonEncoder) {
            val body = encoder.json.encodeToJsonElement(serializer, value) as? JsonObject
                ?: throw SerializationException("Rig component '$name' must encode an object")
            val discriminator = encoder.json.configuration.classDiscriminator
            encoder.encodeJsonElement(JsonObject(linkedMapOf(discriminator to JsonPrimitive(name)) + body))
        } else {
            encoder.encodeStructure(descriptor) {
                encodeStringElement(descriptor, 0, name)
                encodeSerializableElement(descriptor, 1, serializer, value)
            }
        }
    }

    override fun deserialize(decoder: Decoder): Any {
        if (decoder is JsonDecoder) {
            val body = decoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected a rig component object")
            val discriminator = decoder.json.configuration.classDiscriminator
            val name = (body[discriminator] as? JsonPrimitive)?.contentOrNull
                ?: throw SerializationException("Missing rig component type")
            return decoder.json.decodeFromJsonElement(serializerFor(name), JsonObject(body - discriminator))
        }
        return decoder.decodeStructure(descriptor) {
            val sequential = decodeSequentially()
            if (!sequential && decodeElementIndex(descriptor) != 0) throw SerializationException("Missing rig component type")
            val name = decodeStringElement(descriptor, 0)
            if (!sequential && decodeElementIndex(descriptor) != 1) throw SerializationException("Missing data for '$name'")
            val value = decodeSerializableElement(descriptor, 1, serializerFor(name))
            if (!sequential && decodeElementIndex(descriptor) != DECODE_DONE) throw SerializationException("Invalid data for '$name'")
            value
        }
    }

    private fun serializerFor(name: String) =
        RigAttachmentTypes.all.firstOrNull { it.serializer.descriptor.serialName == name }?.serializer
            ?: UnknownRigComponent.deserializerFor(name)

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(value: Any): KSerializer<Any> =
        RigAttachmentTypes.of(value)?.serializer as? KSerializer<Any>
            ?: throw SerializationException("No legacy rig serializer for ${value::class.simpleName}")
}

/** The name used to address a component in existing rig and montage files. */
val Any.rigId: String
    get() = if (this is UnknownRigComponent) id else checkNotNull(
        RigAttachmentTypes.of(this) ?: rigTypesByValueClass.get(javaClass).get()
    ) {
        "Component ${this::class.simpleName} has no legacy rig name"
    }.instanceId(this)
