@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.client.ui.inspector

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.VirtualComponentRegistry
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeTypes
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.utils.math.VectorDescriptors
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import ru.hollowhorizon.hollowengine.common.vfx.VfxModuleTypes
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeTypes
import kotlin.reflect.KClass

/**
 * Editor edits JSON, not objects.
 *
 * A component is encoded once with its own serializer, every field the UI shows is a path into that
 * tree, and a changed tree is decoded back into a component.
 */
internal object ComponentJson {
    @Volatile
    private var cached: Json? = null

    @Volatile
    private var cachedRevision = -1

    val format: Json
        get() {
            val revision = TagModuleRevision.current
            cached?.takeIf { cachedRevision == revision }?.let { return it }
            return Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                explicitNulls = true
                allowSpecialFloatingPointValues = true
                serializersModule = SerializersModule {
                    RigAttachmentTypes.registerInto(this)
                    ColliderShapeTypes.registerInto(this)
                    VfxNodeTypes.registerInto(this)
                    VfxModuleTypes.registerInto(this)
                }
            }.also {
                cached = it
                cachedRevision = revision
            }
        }

    @Suppress("UNCHECKED_CAST")
    fun serializerOf(type: KClass<*>): KSerializer<Component>? =
        (ComponentDescriptorRegistry.descriptorOrNull(type)?.serializer
            ?: VirtualComponentRegistry.descriptor(type)?.serializer) as KSerializer<Component>?

    fun serializerOf(component: Component): KSerializer<Component>? = serializerOf(component::class)

    fun idOf(component: Component): ResourceLocation? =
        ComponentDescriptorRegistry.idFor(component::class)
            ?: VirtualComponentRegistry.descriptor(component::class)?.id

    fun encode(component: Component): JsonObject? {
        val serializer = serializerOf(component) ?: return null
        return runCatching { format.encodeToJsonElement(serializer, component) as? JsonObject }.getOrNull()
    }

    fun decode(serializer: KSerializer<Component>, json: JsonElement): Component? =
        runCatching { format.decodeFromJsonElement(serializer, json) }.getOrNull()

    /**
     * A component with everything at its declared default. Decoding an empty object is what picks up
     * the constructor defaults; the descriptor walk is the fallback for components that declare fields
     * without one.
     */
    fun defaultOf(serializer: KSerializer<Component>): Component? =
        decode(serializer, JsonObject(emptyMap()))
            ?: decode(serializer, defaultJson(serializer.descriptor))

    /** A value of the shape [descriptor] describes, with every leaf at its zero. */
    fun defaultJson(descriptor: SerialDescriptor): JsonElement {
        if (descriptor.isNullable) return JsonNull
        return when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> JsonPrimitive("")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> JsonPrimitive(0)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(0.0)
            SerialKind.ENUM -> JsonPrimitive(descriptor.elementNames.firstOrNull().orEmpty())
            StructureKind.LIST -> JsonArray(List(VectorDescriptors.components(descriptor) ?: 0) { defaultJson(descriptor.getElementDescriptor(0)) })
            StructureKind.MAP -> JsonObject(emptyMap())
            StructureKind.CLASS, StructureKind.OBJECT -> JsonObject(
                (0 until descriptor.elementsCount).associate { index ->
                    descriptor.getElementName(index) to defaultJson(descriptor.getElementDescriptor(index))
                },
            )

            PolymorphicKind.SEALED, PolymorphicKind.OPEN -> subclassDescriptors(descriptor).firstOrNull()
                ?.let { defaultJsonOfSubclass(it) } ?: JsonObject(emptyMap())

            else -> JsonNull
        }
    }

    private fun defaultJsonOfSubclass(descriptor: SerialDescriptor): JsonObject {
        val body = defaultJson(descriptor) as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(body + (format.configuration.classDiscriminator to JsonPrimitive(descriptor.serialName)))
    }

    /** The kinds a polymorphic field can hold: those of a sealed class, or those registered for an open one. */
    fun subclassDescriptors(descriptor: SerialDescriptor): List<SerialDescriptor> {
        if (descriptor.kind == PolymorphicKind.OPEN) return format.serializersModule.getPolymorphicDescriptors(descriptor)
        if (descriptor.elementsCount < 2) return emptyList()
        return descriptor.getElementDescriptor(1).elementDescriptors.toList()
    }

    fun discriminatorOf(value: JsonElement): String? =
        (value as? JsonObject)?.get(format.configuration.classDiscriminator)?.jsonPrimitive?.contentOrNull

    fun withDiscriminator(body: JsonObject, serialName: String): JsonObject =
        JsonObject(body + (format.configuration.classDiscriminator to JsonPrimitive(serialName)))

    fun defaultOfSubclass(descriptor: SerialDescriptor): JsonObject = defaultJsonOfSubclass(descriptor)
}

/** Replaces one entry of a JSON object, keeping the order of the other. */
internal fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
    JsonObject(LinkedHashMap(this).apply { put(name, value) })

/** Replaces one entry of a JSON array. */
internal fun JsonArray.withItem(index: Int, value: JsonElement): JsonArray =
    JsonArray(toMutableList().apply { if (index in indices) set(index, value) })

internal fun JsonArray.withoutItem(index: Int): JsonArray =
    JsonArray(toMutableList().apply { if (index in indices) removeAt(index) })

internal fun JsonArray.withItemAdded(value: JsonElement): JsonArray = JsonArray(this + value)

/** Renames a map key in place, so editing a key does not send it to the end of the map. */
internal fun JsonObject.withKeyRenamed(from: String, to: String): JsonObject {
    if (from == to || to in this) return this
    return JsonObject(LinkedHashMap<String, JsonElement>().also { result ->
        forEach { (key, value) -> result[if (key == from) to else key] = value }
    })
}

internal fun JsonObject.withoutField(name: String): JsonObject =
    JsonObject(LinkedHashMap(this).apply { remove(name) })
