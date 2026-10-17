package ru.hollowhorizon.hollowengine.common.utils.json

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import ru.hollowhorizon.hollowengine.common.utils.serialization.Format

@OptIn(ExperimentalSerializationApi::class)
val json = Json {
    isLenient = true
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    useArrayPolymorphism = true
    prettyPrint = true
    prettyPrintIndent = "  "
    allowComments = true
    allowTrailingComma = true
}

object JsonFormat: Format<JsonElement> {
    override val serializersModule = json.serializersModule

    override fun <V> serialize(serializer: SerializationStrategy<V>, value: V): JsonElement {
        return json.encodeToJsonElement(serializer, value)
    }

    override fun <V> deserialize(deserializer: DeserializationStrategy<V>, data: JsonElement): V {
        return json.decodeFromJsonElement(deserializer, data)
    }

    inline fun <reified T> decodeFromString(string: String): T {
        return json.decodeFromString(string)
    }

    fun encodeToString(element: JsonElement): String {
        return json.encodeToString(JsonElement.serializer(), element)
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> decodeFromStream(stream: java.io.InputStream): T {
        return json.decodeFromStream(stream)
    }

}