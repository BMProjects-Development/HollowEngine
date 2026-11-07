package ru.hollowhorizon.hollowengine.addons.narrative.items

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemFilter
import ru.hollowhorizon.hollowengine.common.utils.currentServer

/**
 * What items count for a quest or a check: an item, a tag, a component, or several of them put together. A filter is
 * a value a story keeps, so the journal can repeat a call that takes one; it says nothing of how many.
 */
@EditorHidden
@Serializable
sealed interface StoryItemFilter : ItemFilter {
    /** Items like [item]; with [exact] only those with all of its components, such as a name or enchantments. */
    @Serializable
    @SerialName("item")
    data class OfItem(val item: @Contextual ItemStack, val exact: Boolean = false) : StoryItemFilter {
        override fun matches(stack: ItemStack): Boolean =
            if (exact) ItemStack.isSameItemSameComponents(item, stack) else ItemStack.isSameItem(item, stack)
    }

    /** Items of the item tag [tag], such as `minecraft:logs`. */
    @Serializable
    @SerialName("tag")
    data class OfTag(val tag: @Contextual ResourceLocation) : StoryItemFilter {
        override fun matches(stack: ItemStack): Boolean = stack.`is`(TagKey.create(Registries.ITEM, tag))
    }

    /** Items that have the component [component]; with [value], only those where it is written as that. */
    @Serializable
    @SerialName("component")
    data class WithComponent(val component: @Contextual ResourceLocation, val value: @Contextual Tag? = null) :
        StoryItemFilter {
        override fun matches(stack: ItemStack): Boolean {
            val type = ItemComponents.typeOf(component)
            if (!stack.has(type)) return false
            return value == null || ItemComponents.encode(stack, type) == value
        }
    }

    /** Items any of [filters] lets through. */
    @Serializable
    @SerialName("any")
    data class AnyOf(val filters: List<StoryItemFilter>) : StoryItemFilter {
        override fun matches(stack: ItemStack): Boolean = filters.any { it.matches(stack) }
    }

    /** Items every one of [filters] lets through. */
    @Serializable
    @SerialName("all")
    data class AllOf(val filters: List<StoryItemFilter>) : StoryItemFilter {
        override fun matches(stack: ItemStack): Boolean = filters.all { it.matches(stack) }
    }

    /** Items [filter] does not let through. */
    @Serializable
    @SerialName("not")
    data class Not(val filter: StoryItemFilter) : StoryItemFilter {
        override fun matches(stack: ItemStack): Boolean = !filter.matches(stack)
    }
}

/** [count] of the items [filter] lets through, as a quest asks for them. */
@Serializable
data class StoryItemRequest(val filter: StoryItemFilter, val count: Int = 1) {
    init {
        if (count < 1) throw StoryException("A request asks for one item at least, not $count")
    }
}

/**
 * The filter [syntax] names, as an item filter field holds it: `#minecraft:logs` for a tag, anything else the item
 * as [itemOf] reads it, which then has to match the components it names too.
 */
fun itemsMatching(syntax: String): StoryItemFilter {
    val typed = syntax.trim()
    if (typed.startsWith("#")) {
        return StoryItemFilter.OfTag(
            ResourceLocation.tryParse(typed.substring(1)) ?: throw StoryException("'$typed' is not a tag")
        )
    }
    val item = itemOf(typed)
    if (item.isEmpty) throw StoryException("'$typed' is no item to look for")
    return StoryItemFilter.OfItem(item, exact = '[' in typed)
}

/** How the components of items are found by id and written as NBT. */
internal object ItemComponents {
    fun typeOf(id: ResourceLocation): DataComponentType<*> =
        BuiltInRegistries.DATA_COMPONENT_TYPE.get(id) ?: throw StoryException("There is no item component '$id'")

    /** The component [type] of [stack] as NBT, or null when the stack has none. */
    @Suppress("UNCHECKED_CAST")
    fun encode(stack: ItemStack, type: DataComponentType<*>): Tag? {
        val value = stack.get(type) ?: return null
        val codec = (type as DataComponentType<Any>).codec() ?: return null
        return codec.encodeStart(ops(), value).result().orElse(null)
    }

    /** A copy of [stack] with the component [type] read from [nbt]. */
    @Suppress("UNCHECKED_CAST")
    fun with(stack: ItemStack, type: DataComponentType<*>, nbt: Tag): ItemStack {
        val codec = (type as DataComponentType<Any>).codec()
            ?: throw StoryException("Item component '${BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)}' is not saved")
        val value = codec.parse(ops(), nbt).getOrThrow { StoryException("$nbt is not a value of that component: $it") }
        return stack.copy().apply { set(type, value) }
    }

    private fun ops() = currentServer.registryAccess().createSerializationContext(NbtOps.INSTANCE)
}
