package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Minecraft language files contain a flat map of translation keys to strings. */
internal class LanguageCatalog private constructor(val entries: Map<String, String>, private val newline: String) {
    fun withTranslation(key: String, value: String): String {
        val translations = LinkedHashMap(entries)
        translations[key] = value
        return (format.toJson(translations) + "\n").replace("\n", newline)
    }

    companion object {
        private val format = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        fun parse(text: String): LanguageCatalog {
            val entries = linkedMapOf<String, String>()
            if (text.isNotBlank()) {
                JsonReader(StringReader(text)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key !in entries && reader.peek() == JsonToken.STRING)
                        entries[key] = reader.nextString()
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
            }
            return LanguageCatalog(entries, if ("\r\n" in text) "\r\n" else "\n")
        }
    }
}
