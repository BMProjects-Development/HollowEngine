package ru.hollowhorizon.hollowengine.client.ui.widgets

import net.minecraft.client.Minecraft
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.locale.Language
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.item.Item
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.level.block.Block

/**
 * Completes the ids of a registry as they are typed, with what each is called beside it.
 * Typing `#` completes the tags if [tags] allows it; with [onlyTags] field holds a tag id without the `#`.
 */
class RegistryCompletions private constructor(
    private val registry: ResourceKey<out Registry<*>>,
    private val tags: Boolean,
    private val onlyTags: Boolean = false,
) : UiCompletionContributor {
    override val triggerCharacters: String get() = ":#"

    override fun complete(context: UiCompletionContext): List<UiTextCompletion> {
        val typed = context.text.take(context.caret.coerceIn(0, context.text.length)).trim().lowercase()
        val tagged = onlyTags || tags && typed.startsWith("#")
        val prefix = typed.removePrefix("#")
        val entries = if (tagged) tagEntries() else entries()
        return entries.asSequence()
            .filter { prefix.isEmpty() || it.id.startsWith(prefix) || it.id.substringAfter(':').startsWith(prefix) }
            .take(MAX_COMPLETIONS).map { entry ->
                val insert = if (tagged && !onlyTags) "#${entry.id}" else entry.id
                UiTextCompletion(
                    label = insert,
                    tail = entry.title,
                    itemIcon = entry.icon,
                    filterText = "$insert ${entry.title}",
                    wordChars = ID_CHARS,
                )
            }.toList()
    }

    private class Entry(val id: String, val title: String, val icon: String?)

    private fun entries(): List<Entry> {
        val language = Language.getInstance()
        cache[registry]?.takeIf { it.first === language }?.let { return it.second }
        val found = registryOf(registry) ?: return emptyList()
        val made = found.entrySet().map { (key, value) ->
            val id = key.location().toString()
            Entry(id, titleOf(value, language), iconOf(id, value))
        }.sortedBy { it.id }
        cache[registry] = language to made
        return made
    }

    private fun tagEntries(): List<Entry> =
        registryOf(registry)?.getTagNames()?.map { Entry(it.location().toString(), "", null) }?.toList()
            ?.sortedBy { it.id }.orEmpty()

    companion object {
        private const val MAX_COMPLETIONS = 60
        private const val ID_CHARS = ":-/._#"
        private val cache = HashMap<ResourceKey<out Registry<*>>, Pair<Language, List<Entry>>>()

        val items = RegistryCompletions(Registries.ITEM, tags = true)

        val plainItems = RegistryCompletions(Registries.ITEM, tags = false)

        val plainBlocks = RegistryCompletions(Registries.BLOCK, tags = false)
        val itemTags = RegistryCompletions(Registries.ITEM, tags = true, onlyTags = true)
        val blocks = RegistryCompletions(Registries.BLOCK, tags = true)
        val effects = RegistryCompletions(Registries.MOB_EFFECT, tags = false)
        val attributes = RegistryCompletions(Registries.ATTRIBUTE, tags = false)

        fun of(registry: ResourceLocation, tags: Boolean = true): RegistryCompletions =
            RegistryCompletions(ResourceKey.createRegistryKey<Any>(registry), tags)

        private fun registryOf(key: ResourceKey<out Registry<*>>): Registry<*>? =
            BuiltInRegistries.REGISTRY.get(key.location()) ?: Minecraft.getInstance().level?.registryAccess()
                ?.registry(key)?.orElse(null)

        private fun titleOf(value: Any?, language: Language): String = when (value) {
            is Item -> language.getOrDefault(value.descriptionId)
            is Block -> language.getOrDefault(value.descriptionId)
            is MobEffect -> language.getOrDefault(value.descriptionId)
            is Attribute -> language.getOrDefault(value.descriptionId)
            is EntityType<*> -> language.getOrDefault(value.descriptionId)
            is Enchantment -> value.description().string
            else -> ""
        }

        private fun iconOf(id: String, value: Any?): String? = when (value) {
            is Item -> id
            is Block -> BuiltInRegistries.ITEM.getKey(value.asItem()).toString()
            else -> null
        }
    }
}
