package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** A class of values a story keeps field by field: the several results of a story function come back in one. */
interface StoryRecord

/**
 * Writes a [StoryRecord] by the parameters of its constructor, each the way [StoryValues] keeps its type: entities and
 * the rest of what a story keeps can be its fields.
 */
internal class StoryRecordSerializer<T : Any>(type: KClass<T>) : KSerializer<T> {
    private val constructor = type.primaryConstructor ?: throw StoryException("${type.simpleName} has no constructor")
    private val properties = type.memberProperties.associateBy { it.name }
    private val fields = constructor.parameters.map { it.name!! }
    private val serializers by lazy { constructor.parameters.map { StoryValues.serializer(it.type) } }

    override val descriptor: SerialDescriptor by lazy {
        buildClassSerialDescriptor(type.qualifiedName ?: type.java.name) {
            fields.forEachIndexed { index, name -> element(name, serializers[index].descriptor) }
        }
    }

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeStructure(descriptor) {
        fields.forEachIndexed { index, name ->
            encodeSerializableElement(descriptor, index, serializers[index], properties.getValue(name).get(value))
        }
    }

    override fun deserialize(decoder: Decoder): T = decoder.decodeStructure(descriptor) {
        val read = arrayOfNulls<Any?>(fields.size)
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            read[index] = decodeSerializableElement(descriptor, index, serializers[index])
        }
        constructor.call(*read)
    }
}
