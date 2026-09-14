package ru.hollowhorizon.hollowengine.common.events.registry

import net.minecraft.core.Registry
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagEntry
import net.minecraft.tags.TagKey
import net.minecraft.tags.TagLoader
import ru.hollowhorizon.hollowengine.bridge.mixins.tags.TagEntryAccessor
import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.utils.JavaHacks

class RegisterTagsEvent(
    val registry: Registry<*>,
    private val tags: MutableMap<ResourceLocation, MutableList<TagLoader.EntryWithSource>>,
) : ServerEvent {
    companion object : EventHandler<RegisterTagsEvent>()

    fun <T : Any> addToTag(key: TagKey<T>, vararg values: T) {
        tags.getOrPut(key.location(), ::ArrayList).apply {
            values.forEach { value ->
                val te = valueToEntry(value) ?: return
                add(te)
            }
        }
    }

    fun <T : Any> removeFromTag(key: TagKey<T>, vararg value: T) {
        val locations = value.mapNotNull { registry.getKey(JavaHacks.forceCast(it)) }.toSet()
        tags.getOrPut(key.location(), ::ArrayList).removeIf { (it.entry as TagEntryAccessor).id() in locations }
    }

    private fun valueToEntry(value: Any): TagLoader.EntryWithSource? {
        val key = registry.getKey(JavaHacks.forceCast(value)) ?: return null
        return TagLoader.EntryWithSource(TagEntry.element(key), "Default")
    }
}
