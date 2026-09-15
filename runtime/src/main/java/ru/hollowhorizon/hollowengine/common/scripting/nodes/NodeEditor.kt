package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptEditorInfo
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptEditorJson
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptField
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptSchema
import ru.hollowhorizon.hollowengine.common.data.DataKey
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class NodeEditor internal constructor(private val script: NodeScript) {
    internal var title: String? = null
        private set
    internal var summary: String? = null
        private set
    internal var iconPath: String? = null
        private set

    internal val properties = LinkedHashMap<String, EditorProperty<*>>()
    private var bound = false

    fun name(value: String) {
        title = value
    }

    fun description(value: String) {
        summary = value
    }

    fun icon(value: String) {
        iconPath = value
    }

    fun <T : Any> property(
        serializer: KSerializer<T>,
        name: String? = null,
        description: String? = null,
        icon: String? = null,
        min: Double? = null,
        max: Double? = null,
        slider: Boolean = false,
        multiline: Boolean = false,
        assets: List<String> = emptyList(),
        restart: Boolean = false,
        onChange: ((T) -> Unit)? = null,
        default: () -> T,
    ): PropertyDelegateProvider<Any?, ReadWriteProperty<Any?, T>> = PropertyDelegateProvider { _, property ->
        require(property.name !in properties) {
            "Node ${script.path} declares two editor properties named '${property.name}'"
        }

        val key = DataKey(property.name, serializer, null)
        val field = ScriptField(
            name = property.name,
            type = ScriptSchema.typeOf(serializer.descriptor),
            label = name.orEmpty(),
            description = description.orEmpty(),
            icon = icon.orEmpty(),
            min = min,
            max = max,
            slider = slider,
            multiline = multiline,
            assets = assets,
        )

        EditorProperty(field, key, PersistedValue(key, default), restart, onChange).also {
            properties[property.name] = it
            bind()
        }
    }

    inline fun <reified T : Any> property(
        name: String? = null,
        description: String? = null,
        icon: String? = null,
        min: Double? = null,
        max: Double? = null,
        slider: Boolean = false,
        multiline: Boolean = false,
        assets: List<String> = emptyList(),
        restart: Boolean = false,
        noinline onChange: ((T) -> Unit)? = null,
        noinline default: () -> T,
    ): PropertyDelegateProvider<Any?, ReadWriteProperty<Any?, T>> = property(
        serializer(), name, description, icon, min, max, slider, multiline, assets, restart, onChange, default,
    )

    internal fun describe(path: String): ScriptEditorInfo = ScriptEditorInfo(
        path = path,
        name = title.orEmpty(),
        description = summary.orEmpty(),
        icon = iconPath.orEmpty(),
        fields = properties.values.map { it.field },
        values = JsonObject(properties.mapValues { (_, property) -> property.encode() }).toString(),
    )

    internal fun apply(values: JsonObject): Boolean {
        var restart = false
        values.forEach { (name, element) ->
            val property = properties[name] ?: return@forEach
            if (property.apply(element)) restart = true
        }
        return restart
    }

    private fun bind() {
        if (bound) return
        bound = true

        script.onLoadHandlers += { context ->
            val tag = context.tag.getCompound(PropertiesTag)
            properties.values.forEach { it.load(tag) }
        }
        script.onSaveHandlers += { context ->
            val tag = CompoundTag()
            properties.values.forEach { it.save(tag) }
            if (!tag.isEmpty) context.tag.put(PropertiesTag, tag)
        }
    }

    private companion object {
        const val PropertiesTag = "editor"
    }
}

internal class EditorProperty<T : Any>(
    val field: ScriptField,
    private val key: DataKey<T>,
    private val storage: PersistedValue<T>,
    private val restart: Boolean,
    private val onChange: ((T) -> Unit)?,
) : ReadWriteProperty<Any?, T> by storage {
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        storage.setValue(thisRef, property, value)
        onChange?.invoke(value)
    }

    fun encode(): JsonElement = runCatching {
        ScriptEditorJson.encodeToJsonElement(
            key.serializer,
            storage.current()
        )
    }.onFailure { HollowEngine.LOGGER.error("Cannot show editor property '${key.name}'", it) }.getOrDefault(JsonNull)

    fun apply(element: JsonElement): Boolean {
        val decoded =
            runCatching { ScriptEditorJson.decodeFromJsonElement(key.serializer, element) }.getOrElse { return false }
        if (decoded == storage.current()) return false

        storage.assign(decoded)
        onChange?.invoke(decoded)
        return restart
    }

    fun load(tag: CompoundTag) = storage.load(tag)

    fun save(tag: CompoundTag) = storage.save(tag)
}
