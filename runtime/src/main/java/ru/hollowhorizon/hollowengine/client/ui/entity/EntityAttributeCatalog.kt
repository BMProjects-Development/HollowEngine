package ru.hollowhorizon.hollowengine.client.ui.entity

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import ru.hollowhorizon.hollowengine.client.utils.lang

internal data class EntityAttributeOption(val id: String, val translationKey: String, val defaultValue: Double) {
    val translatedName: String? get() = translationKey.takeIf { Language.getInstance().has(it) }?.lang
        ?.takeIf { it.isNotBlank() && it != translationKey }
    val displayName: String get() = translatedName ?: id

    fun matches(query: String): Boolean = query.trim().let { text ->
        id.contains(text, ignoreCase = true) || displayName.contains(text, ignoreCase = true)
    }
}

internal fun entityAttributeCatalog(): Map<String, EntityAttributeOption> = BuiltInRegistries.ATTRIBUTE.entrySet()
    .associate { (key, attribute) ->
        val id = key.location().toString()
        id to EntityAttributeOption(id, attribute.descriptionId, attribute.defaultValue)
    }
