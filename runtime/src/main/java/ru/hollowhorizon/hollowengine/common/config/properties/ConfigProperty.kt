package ru.hollowhorizon.hollowengine.common.config.properties

import com.google.common.reflect.TypeToken
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import net.peanuuutz.tomlkt.TomlElement
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.config.PropertyComment
import ru.hollowhorizon.hollowengine.common.config.PropertyName
import ru.hollowhorizon.hollowengine.common.config.PropertyRange
import ru.hollowhorizon.hollowengine.common.config.PropertyValidValues
import ru.hollowhorizon.hollowengine.common.utils.ReloadableConfigValue
import ru.hollowhorizon.hollowengine.common.utils.SerializerProvider
import ru.hollowhorizon.hollowengine.common.utils.toml.toml
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.findAnnotation

@Suppress("UNCHECKED_CAST")
class ConfigProperty<T>(
    initialValue: T,
    private val onChange: () -> Unit,
    private val validator: ((T) -> Boolean)? = null,
    private val valueSerializer: KSerializer<T>? = null,
) : ReadWriteProperty<Any?, T> {
    private var _value: T = initialValue
    private var cachedElement: TomlElement? = null
    private var isCacheValid = false

    var name: String? = null
    var comment: String? = null
    var range: ClosedFloatingPointRange<Float>? = null
    var validValues: Set<String>? = null

    private val serializer: KSerializer<T> by lazy {
        valueSerializer ?: when (_value) {
            is SerializerProvider -> (_value as SerializerProvider).serializer() as KSerializer<T>
            else -> toml.serializersModule.serializer(TypeToken.of(_value!!::class.java).type) as KSerializer<T>
        }
    }

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, T> {
        name = property.findAnnotation<PropertyName>()?.name ?: property.name
        comment = property.findAnnotation<PropertyComment>()?.value
        property.findAnnotation<PropertyRange>()?.let {
            range = it.min..it.max
        }
        property.findAnnotation<PropertyValidValues>()?.let {
            validValues = it.values.toSet()
        }

        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return _value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (!isValid(value)) {
            throw IllegalArgumentException("Invalid value for property ${property.name}: $value")
        }

        _value = value
        isCacheValid = false
        onChange()
    }

    private fun isValid(value: T): Boolean {
        if (value is Number && range != null) {
            if (value.toFloat() !in range!!) return false
        }

        if (validValues != null) {
            if (value !is String) return false
            if (value !in validValues!!) return false
        }

        if (validator != null && !validator(value)) return false

        return true
    }

    fun serialize(): TomlElement? {
        if (!isCacheValid) {
            try {
                cachedElement = toml.encodeToTomlElement(serializer, _value)
                isCacheValid = true
            } catch (e: Exception) {
                HollowEngine.LOGGER.error("Serialization failed for property: $name", e)
                return null
            }
        }
        return cachedElement
    }

    fun deserialize(element: TomlElement): Boolean {
        try {
            val decoded = toml.decodeFromTomlElement(serializer, element)
            if (!isValid(decoded)) {
                HollowEngine.LOGGER.warn(
                    "Invalid value loaded for property '{}': {}. Keeping default: {}",
                    name, decoded, _value
                )
                return false
            }
            val reloadableValue = _value as? ReloadableConfigValue
            if (reloadableValue != null && !reloadableValue.replaceFrom(decoded)) {
                HollowEngine.LOGGER.warn(
                    "Deserialized value for property '{}' cannot replace its observable container. Value unchanged.",
                    name
                )
                return false
            }

            if (reloadableValue == null) {
                _value = decoded
            }
            isCacheValid = false
            return true
        } catch (e: Exception) {
            HollowEngine.LOGGER.warn("Deserialization failed for property '{}': {}. Value unchanged.", name, e.message)
            return false
        }
    }
}
