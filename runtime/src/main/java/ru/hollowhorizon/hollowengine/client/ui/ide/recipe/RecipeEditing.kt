package ru.hollowhorizon.hollowengine.client.ui.ide.recipe

import com.google.gson.*

private val Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

internal fun parseRecipeJson(text: String): JsonObject {
    val root = JsonParser.parseString(text)
    require(root.isJsonObject) { "A recipe must be a JSON object" }
    return root.asJsonObject
}

internal fun JsonObject.toRecipeText(): String = Gson.toJson(this) + "\n"

internal fun JsonElement.toPrettyJson(): String = Gson.toJson(this)

/**
 * One recipe file as an editor sees it. [json] is the file as it was read and must not be changed in
 * place; every change goes through [edit], which applies to the file as it is now, so a callback kept
 * from an older composition never writes stale values back.
 */
class RecipeEditing internal constructor(
    val json: JsonObject,
    val readOnly: Boolean,
    internal val session: RecipeEditorSession,
    private val latest: () -> JsonObject?,
    private val write: (JsonObject) -> Unit,
) {
    /** The recipe's `type`, as written. */
    val type: String = json.primitive("type")?.asString.orEmpty()

    fun edit(change: JsonObject.() -> Unit) {
        if (readOnly) return
        val base = latest() ?: return
        val next = base.deepCopy().apply(change)
        if (next != base) write(next)
    }

    /** Rewrites the recipe as one of [type]; see [RecipeEditors.convert] for what carries over. */
    fun convertTo(type: RecipeEditorType) {
        if (readOnly) return
        val base = latest() ?: return
        write(RecipeEditors.convert(base, type))
    }

    fun ingredient(key: String): RecipeIngredient? = RecipeIngredient.of(json.get(key))

    fun setIngredient(key: String, value: RecipeIngredient?) = edit { put(key, value?.json) }

    fun result(key: String = "result"): RecipeResult? = RecipeResult.of(json.get(key))

    fun setResult(key: String, value: RecipeResult?) = edit { put(key, value?.json) }

    fun string(key: String, default: String = ""): String =
        json.primitive(key)?.takeIf { it.isString }?.asString ?: default

    fun int(key: String, default: Int = 0): Int =
        json.primitive(key)?.let { runCatching { it.asInt }.getOrNull() } ?: default

    fun float(key: String, default: Float = 0f): Float =
        json.primitive(key)?.let { runCatching { it.asFloat }.getOrNull() } ?: default

    fun boolean(key: String, default: Boolean = false): Boolean =
        json.primitive(key)?.let { runCatching { it.asBoolean }.getOrNull() } ?: default

    /** Writes [value], or drops the key when it equals [default], the way vanilla's own files do. */
    fun set(key: String, value: String, default: String? = null) =
        edit { put(key, JsonPrimitive(value).takeUnless { value == default }) }

    fun set(key: String, value: Number, default: Number? = null) =
        edit { put(key, JsonPrimitive(value).takeUnless { default != null && value.toDouble() == default.toDouble() }) }

    fun set(key: String, value: Boolean, default: Boolean? = null) =
        edit { put(key, JsonPrimitive(value).takeUnless { value == default }) }
}

internal fun JsonObject.put(key: String, value: JsonElement?) {
    if (value == null) remove(key) else add(key, value)
}

internal fun JsonObject.primitive(key: String): JsonPrimitive? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

/** A JSON array of strings, as recipes write patterns. */
internal fun JsonArray.strings(): List<String> = mapNotNull { element ->
    element.takeIf { it.isJsonPrimitive }?.asString
}
