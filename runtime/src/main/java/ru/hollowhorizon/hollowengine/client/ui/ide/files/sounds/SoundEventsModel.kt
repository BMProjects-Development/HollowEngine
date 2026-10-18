package ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.gson.*

/** `sounds.json` as the editor works on it; [serialize] writes it back in vanilla's shape. */
internal class SoundEventsModel(val events: SnapshotStateList<SoundEvent>) {
    fun addEvent(name: String = "new_event"): SoundEvent {
        val event = SoundEvent(name = uniqueEventName(name))
        events += event
        return event
    }

    private fun uniqueEventName(base: String): String {
        if (events.none { it.name == base }) return base
        var index = 1
        while (events.any { it.name == "$base.$index" }) index++
        return "$base.$index"
    }

    fun serialize(): String {
        val root = JsonObject()
        for (event in events) {
            val name = event.name.trim()
            if (name.isEmpty() || root.has(name)) continue
            val obj = JsonObject()
            if (event.replace) obj.addProperty("replace", true)
            if (event.subtitle.isNotBlank()) obj.addProperty("subtitle", event.subtitle.trim())
            val sounds = JsonArray()
            for (sound in event.sounds) {
                serializeSound(sound)?.let(sounds::add)
            }
            obj.add("sounds", sounds)
            root.add(name, obj)
        }
        return GSON.toJson(root) + "\n"
    }

    private fun serializeSound(sound: SoundEntry): JsonElement? {
        val name = sound.name.trim()
        if (name.isEmpty()) return null
        if (sound.isDefaultExceptName) return JsonPrimitive(name)
        val obj = JsonObject()
        obj.addProperty("name", name)
        if (sound.volume != 1f) obj.addProperty("volume", sound.volume)
        if (sound.pitch != 1f) obj.addProperty("pitch", sound.pitch)
        if (sound.weight != 1) obj.addProperty("weight", sound.weight)
        if (sound.stream) obj.addProperty("stream", true)
        if (sound.attenuationDistance != 16) obj.addProperty("attenuation_distance", sound.attenuationDistance)
        if (sound.preload) obj.addProperty("preload", true)
        if (sound.type != SoundEntryType.SOUND) obj.addProperty("type", sound.type.jsonName)
        return obj
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        /** Throws on text that is not a JSON object, so the editor never writes over a half-typed file. */
        fun parse(text: String): SoundEventsModel {
            val events = mutableStateListOf<SoundEvent>()
            if (text.isBlank()) return SoundEventsModel(events)
            val root = JsonParser.parseString(text)
            require(root.isJsonObject) { "sounds.json must be a JSON object" }

            for ((key, value) in root.asJsonObject.entrySet()) {
                if (!value.isJsonObject) continue
                val obj = value.asJsonObject
                val event = SoundEvent(
                    name = key,
                    replace = obj.boolean("replace") ?: false,
                    subtitle = obj.string("subtitle") ?: "",
                )
                obj.get("sounds")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
                    parseSound(element)?.let { event.sounds += it }
                }
                events += event
            }
            return SoundEventsModel(events)
        }

        private fun parseSound(element: JsonElement): SoundEntry? = when {
            element.isJsonPrimitive -> SoundEntry(name = element.asString)
            element.isJsonObject -> element.asJsonObject.let { obj ->
                SoundEntry(
                    name = obj.string("name") ?: "",
                    volume = obj.float("volume") ?: 1f,
                    pitch = obj.float("pitch") ?: 1f,
                    weight = obj.int("weight") ?: 1,
                    stream = obj.boolean("stream") ?: false,
                    attenuationDistance = obj.int("attenuation_distance") ?: 16,
                    preload = obj.boolean("preload") ?: false,
                    type = if (obj.string("type") == "event") SoundEntryType.EVENT else SoundEntryType.SOUND,
                )
            }

            else -> null
        }

        private fun JsonObject.member(key: String): JsonElement? =
            get(key)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }

        private fun JsonObject.string(key: String): String? = member(key)?.asString

        private fun JsonObject.boolean(key: String): Boolean? =
            member(key)?.let { runCatching { it.asBoolean }.getOrNull() }

        private fun JsonObject.int(key: String): Int? =
            member(key)?.let { runCatching { it.asInt }.getOrNull() }

        private fun JsonObject.float(key: String): Float? =
            member(key)?.let { runCatching { it.asFloat }.getOrNull() }
    }
}

internal enum class SoundEntryType(val jsonName: String) {
    SOUND("sound"),
    EVENT("event"),
}

/** A single sound event (top-level key in `sounds.json`). */
internal class SoundEvent(
    name: String = "",
    replace: Boolean = false,
    subtitle: String = "",
) {
    var name by mutableStateOf(name)
    var replace by mutableStateOf(replace)
    var subtitle by mutableStateOf(subtitle)
    val sounds: SnapshotStateList<SoundEntry> = mutableStateListOf()
}

/** A single entry inside an event's `sounds` array. */
internal class SoundEntry(
    name: String = "",
    volume: Float = 1f,
    pitch: Float = 1f,
    weight: Int = 1,
    stream: Boolean = false,
    attenuationDistance: Int = 16,
    preload: Boolean = false,
    type: SoundEntryType = SoundEntryType.SOUND,
) {
    var name by mutableStateOf(name)
    var volume by mutableStateOf(volume)
    var pitch by mutableStateOf(pitch)
    var weight by mutableStateOf(weight)
    var stream by mutableStateOf(stream)
    var attenuationDistance by mutableStateOf(attenuationDistance)
    var preload by mutableStateOf(preload)
    var type by mutableStateOf(type)

    /** True when only [name] carries information, so the entry can be written as a bare string. */
    val isDefaultExceptName: Boolean
        get() = volume == 1f && pitch == 1f && weight == 1 && !stream &&
            attenuationDistance == 16 && !preload && type == SoundEntryType.SOUND
}
