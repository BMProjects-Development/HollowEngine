package ru.hollowhorizon.hollowengine.client.models.fbx

open class Property
class TypedProperty<T>(val value: T) : Property()

class PropertyTable(val element: Element? = null, val templateProps: PropertyTable? = null) {
    val lazyProps = linkedMapOf<String, Element>()
    val props = mutableMapOf<String, Property>()

    init {
        element?.scope?.getCollection("P")?.forEach { entry ->
            val name = entry.peekPropertyName
            if (name.isNotEmpty()) lazyProps[name] = entry
        }
    }

    inline operator fun <reified T> invoke(name: String, defaultValue: T): T = value<T>(name) ?: defaultValue
    inline operator fun <reified T> invoke(name: String): T? = value(name)

    inline fun <reified T> value(name: String): T? {
        val property = get(name) as? TypedProperty<*> ?: return null
        val value = property.value
        if (value is T) return value
        val converted = when {
            value is Number -> when (T::class) {
                Float::class -> value.toFloat()
                Double::class -> value.toDouble()
                Long::class -> value.toLong()
                Int::class -> value.toInt()
                Boolean::class -> value.toInt() != 0
                else -> null
            }
            value is Boolean && T::class == Int::class -> if (value) 1 else 0
            else -> null
        }
        return converted as? T ?: domError("property $name has incompatible type", element)
    }

    operator fun get(name: String): Property? {
        props[name]?.let { return it }
        val parsed = lazyProps[name]?.readTypedProperty()
        if (parsed != null) {
            props[name] = parsed
            return parsed
        }
        return templateProps?.get(name)
    }
}
