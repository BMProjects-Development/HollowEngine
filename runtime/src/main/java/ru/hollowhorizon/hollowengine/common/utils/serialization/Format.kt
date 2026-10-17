package ru.hollowhorizon.hollowengine.common.utils.serialization

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

interface Format<T> {
    val serializersModule: SerializersModule

    fun <V> serialize(serializer: SerializationStrategy<V>, value: V): T
    fun <V> deserialize(deserializer: DeserializationStrategy<V>, data: T): V
}

inline fun <reified T, V> Format<V>.serialize(value: T): V {
    return this.serialize(serializersModule.serializer(), value)
}

inline fun <reified T, V> Format<V>.deserialize(tag: V): T {
    return deserialize(serializersModule.serializer(), tag)
}