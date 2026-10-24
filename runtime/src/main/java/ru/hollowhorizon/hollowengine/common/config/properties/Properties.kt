package ru.hollowhorizon.hollowengine.common.config.properties

import kotlinx.serialization.KSerializer
import net.peanuuutz.tomlkt.TomlComment
import net.peanuuutz.tomlkt.TomlElement
import net.peanuuutz.tomlkt.TomlTable
import net.peanuuutz.tomlkt.annotated
import ru.hollowhorizon.hollowengine.HollowEngine

class Properties(val onChange: () -> Unit) {
    private val properties = mutableListOf<ConfigProperty<*>>()
    private val orphanedKeys = mutableMapOf<String, TomlElement>()

    fun <T> add(value: T, validator: ((T) -> Boolean)? = null): ConfigProperty<T> {
        return ConfigProperty(value, onChange, validator).also { properties.add(it) }
    }

    fun <T> add(value: T, serializer: KSerializer<T>, validator: ((T) -> Boolean)? = null): ConfigProperty<T> {
        return ConfigProperty(value, onChange, validator, serializer).also { properties.add(it) }
    }

    fun serialize(): TomlTable {
        val propertyNames = properties.mapNotNull { it.name }.toSet()

        val entries = mutableListOf<Pair<String, TomlElement>>()

        for (property in properties) {
            val name = property.name ?: continue
            try {
                val element = property.serialize() ?: continue
                entries.add(name to element)
            } catch (e: Exception) {
                HollowEngine.LOGGER.warn("Failed to serialize property: $name", e)
            }
        }

        for ((orphanedName, orphanedElement) in orphanedKeys) {
            if (orphanedName !in propertyNames) {
                entries.add(orphanedName to orphanedElement)
            }
        }

        val table = TomlTable(*entries.toTypedArray())

        val comments = mutableMapOf<String, List<TomlComment>>()

        for (property in properties) {
            val name = property.name ?: continue
            val propertyComments = mutableListOf<TomlComment>()
            property.comment?.let { propertyComments.add(TomlComment(it)) }
            comments[name] = propertyComments
        }

        for (orphanedName in orphanedKeys.keys) {
            if (orphanedName !in propertyNames) {
                comments[orphanedName] = listOf(
                    TomlComment(" [ORPHANED] This setting is no longer used by the mod and will be removed on next save.")
                )
            }
        }

        return table.annotated(comments)
    }

    fun deserialize(table: TomlTable) {
        val matchedNames = mutableSetOf<String>()
        val propertyNames = properties.mapNotNull { it.name }.toSet()

        for (property in properties) {
            val name = property.name ?: continue
            try {
                val element = table[name]
                if (element != null) {
                    property.deserialize(element)
                    matchedNames.add(name)
                }
            } catch (e: Exception) {
                HollowEngine.LOGGER.warn("Failed to deserialize property: $name", e)
            }
        }

        orphanedKeys.clear()
        for ((key, value) in table) {
            if (key !in propertyNames) {
                orphanedKeys[key] = value
                HollowEngine.LOGGER.info(
                    "Orphaned config key '{}' preserved (not recognized by current mod version)",
                    key
                )
            }
        }
    }

    fun hasOrphanedKeys(): Boolean = orphanedKeys.isNotEmpty()

    fun clearOrphanedKeys() {
        orphanedKeys.clear()
    }
}
