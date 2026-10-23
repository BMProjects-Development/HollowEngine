package ru.hollowhorizon.hollowengine.common.utils.nbt

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.SerializersModule
import net.minecraft.nbt.*

internal fun <T> NBTFormat.readNbt(element: Tag, deserializer: DeserializationStrategy<T>): T {
    val input = when (element) {
        is CompoundTag -> NBTReader(this, element)
        is CollectionTag<*> -> TagListDecoder(this, element)
        else -> TagPrimitiveReader(this, element)
    }
    return input.decodeSerializableValue(deserializer)
}

internal inline fun <reified T : Tag> cast(obj: Tag): T {
    check(obj is T) { "Expected ${T::class} but found ${obj::class}" }
    return obj
}

private inline fun <reified T> Any.cast() = this as T

@OptIn(ExperimentalSerializationApi::class)
private sealed class AbstractNBTReader(val format: NBTFormat, open val map: Tag) : NamedValueNbtDecoder() {

    override val serializersModule: SerializersModule
        get() = format.serializersModule


    private fun currentObject() = currentTagOrNull?.let { currentElement(it) } ?: map


    override fun composeName(parentName: String, childName: String): String = childName

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val currentObject = currentObject()
        return when (descriptor.kind) {
            StructureKind.LIST -> {
                if (descriptor.kind == StructureKind.LIST && descriptor.getElementDescriptor(0).isNullable) NullableListDecoder(
                    format,
                    cast(currentObject)
                )
                else TagListDecoder(format, cast(currentObject))
            }

            is PolymorphicKind -> NbtMapDecoder(format, cast(currentObject))
            StructureKind.MAP -> selectMapMode(
                descriptor,
                { NbtMapDecoder(format, cast(currentObject)) }
            ) { TagListDecoder(format, cast(currentObject)) }

            else -> NBTReader(format, cast(currentObject))
        }
    }

    protected open fun getValue(tag: String): Tag {
        return currentElement(tag)
    }

    protected abstract fun currentElement(tag: String): Tag

    override fun decodeTaggedChar(tag: String): Char {
        val o = getValue(tag)
        val str = o.asString
        return if (str.length == 1) str[0] else throw IllegalStateException("$o can't be represented as Char")
    }

    override fun decodeTaggedEnum(tag: String, enumDescriptor: SerialDescriptor): Int =
        enumDescriptor.getElementIndex(getValue(tag).asString)

    override fun decodeTaggedNull(tag: String): Nothing? {
        return null
    }

    override fun decodeTaggedNotNullMark(tag: String): Boolean {
        // If we don't do this assigment it fails. I have no clue why. This is a quantum bug, it cannot be debugged.
        val byteValue = (currentElement(tag) as? ByteTag)?.asByte
        return byteValue != NbtFormatNull
    }

    override fun decodeTaggedBoolean(tag: String): Boolean = decodeTaggedByte(tag) == 1.toByte()
    override fun decodeTaggedByte(tag: String): Byte = getNumberValue(tag, { asByte }, { toByte() })
    override fun decodeTaggedShort(tag: String) = getNumberValue(tag, { asShort }, { toShort() })
    override fun decodeTaggedInt(tag: String): Int = getNumberValue(tag, { asInt }, { toInt() })

    override fun decodeTaggedLong(tag: String) = getNumberValue(tag, { asLong }, { toLong() })
    override fun decodeTaggedFloat(tag: String) = getNumberValue(tag, { asFloat }, { toFloat() })
    override fun decodeTaggedDouble(tag: String) = getNumberValue(tag, { asDouble }, { toDouble() })
    override fun decodeTaggedString(tag: String): String = getValue(tag).cast<StringTag>().asString

    override fun decodeTaggedTag(key: String): Tag = getValue(key)

    override fun rootTag(): Tag = map

    private inline fun <T> getNumberValue(
        tag: String,
        getter: NumericTag.() -> T,
        stringGetter: String.() -> T,
    ): T {
        val value = getValue(tag)
        return if (value is NumericTag) value.getter()
        else value.asString.stringGetter()
    }
}

private open class NBTReader(json: NBTFormat, override val map: CompoundTag) : AbstractNBTReader(json, map) {
    private var position = 0

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (position < descriptor.elementsCount) {
            val name = descriptor.getTag(position++)
            if (map.contains(name)) {
                return position - 1
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun currentElement(tag: String): Tag = map.get(tag)!!

}

private class NbtMapDecoder(json: NBTFormat, override val map: CompoundTag) : NBTReader(json, map) {
    private val keys = map.allKeys.toList()
    private val size: Int = keys.size * 2
    private var position = -1

    override fun elementName(descriptor: SerialDescriptor, index: Int): String {
        val i = index / 2
        return keys[i]
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (position < size - 1) ++position
        else CompositeDecoder.DECODE_DONE
    }

    override fun currentElement(tag: String): Tag {
        return if (position % 2 == 0) StringTag.valueOf(tag) else map.get(tag)!!
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // do nothing, maps do not have strict keys, so strict mode check is omitted
    }
}

private class NullableListDecoder(json: NBTFormat, override val map: CompoundTag) : NBTReader(json, map) {
    private val size: Int = map.size()
    private var position = -1

    override fun elementName(descriptor: SerialDescriptor, index: Int): String = index.toString()


    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (position < size - 1) ++position
        else CompositeDecoder.DECODE_DONE
    }

    override fun currentElement(tag: String): Tag {
        return map[tag]!!
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // do nothing, maps do not have strict keys, so strict mode check is omitted
    }
}

private class TagListDecoder(json: NBTFormat, override val map: CollectionTag<*>) : AbstractNBTReader(json, map) {
    private val size = map.size
    private var currentIndex = -1

    override fun elementName(descriptor: SerialDescriptor, index: Int): String = index.toString()

    override fun currentElement(tag: String): Tag {
        return map[tag.toInt()]
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (currentIndex < size - 1) ++currentIndex
        else CompositeDecoder.DECODE_DONE
    }
}

internal const val PRIMITIVE_TAG = "primitive"

private class TagPrimitiveReader(json: NBTFormat, override val map: Tag) : AbstractNBTReader(json, map) {

    init {
        pushTag(PRIMITIVE_TAG)
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = 0

    override fun currentElement(tag: String): Tag {
        require(tag == PRIMITIVE_TAG) { "This input can only handle primitives with '$PRIMITIVE_TAG' tag" }
        return map
    }
}