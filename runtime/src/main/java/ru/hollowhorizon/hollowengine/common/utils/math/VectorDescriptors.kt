package ru.hollowhorizon.hollowengine.common.utils.math

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder

/**
 * Vectors are written as plain lists of numbers, under a name of their own, so an editor can tell a
 * vector from any other list and lay its components out in one row.
 */
@OptIn(ExperimentalSerializationApi::class)
object VectorDescriptors {
    private val components = mapOf(
        "hollowengine.Vec2f" to 2, "hollowengine.Vec2d" to 2, "hollowengine.Vec2i" to 2,
        "hollowengine.Vec3f" to 3, "hollowengine.Vec3d" to 3, "hollowengine.Vec3i" to 3,
        "hollowengine.Vec4f" to 4, "hollowengine.Vec4d" to 4, "hollowengine.Vec4i" to 4,
    )

    internal inline fun <reified T> of(serialName: String): SerialDescriptor =
        SerialDescriptor(serialName, listSerialDescriptor<T>())

    /** How many components the vector [descriptor] describes has, or null when it is no vector. */
    fun components(descriptor: SerialDescriptor): Int? = components[descriptor.serialName]
}

/**
 * Reads the components of a vector written as a collection. A sequential format, like the packet buffer,
 * writes the size first and gives no element indices; the others name each element as it comes.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class VectorReader(private val decoder: CompositeDecoder, private val descriptor: SerialDescriptor) {
    private val sequential = decoder.decodeSequentially()

    init {
        if (sequential) decoder.decodeCollectionSize(descriptor)
    }

    /** Moves to the component at [index], which must be the next one. */
    fun expect(index: Int) {
        if (!sequential) require(decoder.decodeElementIndex(descriptor) == index)
    }
}
