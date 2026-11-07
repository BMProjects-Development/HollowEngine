package ru.hollowhorizon.hollowengine.addons.narrative.items

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.state.BlockState
import ru.hollowhorizon.hollowengine.addons.narrative.nbt.compoundOf
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValue
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRegistry
import ru.hollowhorizon.hollowengine.common.utils.currentServer

private const val ITEMS = "minecraft:item"
private const val COMPONENTS = "minecraft:data_component_type"
private const val ENCHANTMENTS = "minecraft:enchantment"

/** [count] of [item]: the stack commands give, put or compare. */
@EditorCategory(StoryCategory.ITEMS)
@StoryValue("item")
fun itemValue(item: ItemStack, count: Int = 1): ItemStack = item.copyWithCount(count)

/** The item of [block], what the player places it with; empty for a block without one, such as fire. */
@EditorCategory(StoryCategory.ITEMS)
@StoryValue("block-item")
fun blockItemOf(block: BlockState): ItemStack = ItemStack(block.block.asItem())

/** The block [item] places, as it stands by default; null for an item that places none. */
@EditorCategory(StoryCategory.ITEMS)
@StoryValue("item-block")
fun itemBlockOf(item: ItemStack): BlockState? = (item.item as? BlockItem)?.block?.defaultBlockState()

/** The id of the item of [item], such as `minecraft:apple`. */
@EditorCategory(StoryCategory.ITEM_DATA)
@StoryValue("item-id")
fun itemIdOf(item: ItemStack): ResourceLocation = BuiltInRegistries.ITEM.getKey(item.item)

/** What [item] is called, its own name if it was given one. */
@EditorCategory(StoryCategory.ITEM_DATA)
@StoryValue("item-name")
fun itemNameOf(item: ItemStack): String = item.hoverName.string

@EditorCategory(StoryCategory.ITEM_DATA)
@StoryValue("item-count")
fun itemCountOf(item: ItemStack): Int = item.count

@EditorCategory(StoryCategory.ITEM_DATA)
@StoryValue("max-stack")
fun maxStackOf(item: ItemStack): Int = item.maxStackSize

@EditorCategory(StoryCategory.ITEM_DATA)
@StoryValue("is-empty")
fun isEmptyItem(item: ItemStack): Boolean = item.isEmpty

/** Whether [item] belongs to the item tag [tag], such as `minecraft:logs`. */
@EditorCategory(StoryCategory.ITEM_DATA)
@StoryValue("item-in-tag")
fun itemInTag(item: ItemStack, @EditorRegistry(ITEMS, tags = true) tag: ResourceLocation): Boolean =
    item.`is`(TagKey.create(Registries.ITEM, tag))

/** A copy of [item] with [count] of it. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("with-count")
fun withCount(item: ItemStack, count: Int): ItemStack = item.copyWithCount(count)

/** The name [item] was given, or null for its own. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("custom-name")
fun customNameOf(item: ItemStack): String? = item.get(DataComponents.CUSTOM_NAME)?.string

/** A copy of [item] called [name]. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("with-name")
fun withName(item: ItemStack, name: String): ItemStack =
    item.copy().apply { set(DataComponents.CUSTOM_NAME, Component.literal(name)) }

/** The lines written under the name of [item]. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("lore")
fun loreOf(item: ItemStack): List<String> = item.get(DataComponents.LORE)?.lines?.map { it.string }.orEmpty()

/** A copy of [item] with [lines] under its name. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("with-lore")
fun withLore(item: ItemStack, lines: List<String>): ItemStack =
    item.copy().apply { set(DataComponents.LORE, ItemLore(lines.map(Component::literal))) }

/** How worn [item] is: 0 for a new one. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("item-damage")
fun damageOf(item: ItemStack): Int = item.damageValue

/** A copy of [item] worn by [damage]. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("with-damage")
fun withDamage(item: ItemStack, damage: Int): ItemStack = item.copy().apply { damageValue = damage }

/** The level of the enchantment [enchantment] on [item], 0 without it. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("enchantment-level")
fun enchantmentLevelOf(item: ItemStack, @EditorRegistry(ENCHANTMENTS) enchantment: ResourceLocation): Int =
    EnchantmentHelper.getItemEnchantmentLevel(enchantmentHolder(enchantment), item)

/** A copy of [item] with the enchantment [enchantment] at [level]; level 0 takes it off. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("with-enchantment")
fun withEnchantment(
    item: ItemStack,
    @EditorRegistry(ENCHANTMENTS) enchantment: ResourceLocation,
    level: Int = 1,
): ItemStack = item.copy().also { copy ->
    EnchantmentHelper.updateEnchantments(copy) { it.set(enchantmentHolder(enchantment), level) }
}

/** What scripts keep in [item], its `minecraft:custom_data`. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("custom-data")
fun customDataOf(item: ItemStack): CompoundTag = item.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()

/** A copy of [item] keeping the compound [nbt] as its `minecraft:custom_data`. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("with-custom-data")
fun withCustomData(item: ItemStack, nbt: Tag): ItemStack =
    item.copy().apply { set(DataComponents.CUSTOM_DATA, CustomData.of(compoundOf(nbt))) }

/** The component [component] of [item] as NBT, such as `minecraft:dyed_color`; null when it has none. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("component")
fun componentOf(item: ItemStack, @EditorRegistry(COMPONENTS) component: ResourceLocation): Tag? =
    ItemComponents.encode(item, ItemComponents.typeOf(component))

/** A copy of [item] with the component [component] written as [value]. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("with-component")
fun withComponent(item: ItemStack, @EditorRegistry(COMPONENTS) component: ResourceLocation, value: Tag): ItemStack =
    ItemComponents.with(item, ItemComponents.typeOf(component), value)

/** A copy of [item] without the component [component]. */
@EditorCategory(StoryCategory.ITEM_COMPONENTS)
@StoryValue("without-component")
fun withoutComponent(item: ItemStack, @EditorRegistry(COMPONENTS) component: ResourceLocation): ItemStack =
    item.copy().apply { remove(ItemComponents.typeOf(component)) }

/** Items like [item]; with [exact] only those with all of its components too, such as a name or enchantments. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("filter-item")
fun filterItem(item: ItemStack, exact: Boolean = false): StoryItemFilter {
    if (item.isEmpty) throw StoryException("No item is given to look for")
    return StoryItemFilter.OfItem(item.copyWithCount(1), exact)
}

/** Items of the item tag [tag], such as `minecraft:logs`. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("filter-tag")
fun filterTag(@EditorRegistry(ITEMS, tags = true) tag: ResourceLocation): StoryItemFilter = StoryItemFilter.OfTag(tag)

/** Items with the component [component]; with [value], only those where it is written as that. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("filter-component")
fun filterComponent(@EditorRegistry(COMPONENTS) component: ResourceLocation, value: Tag? = null): StoryItemFilter =
    StoryItemFilter.WithComponent(component, value)

/** Items either filter lets through. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("filter-or")
fun filterOr(a: StoryItemFilter, b: StoryItemFilter): StoryItemFilter = StoryItemFilter.AnyOf(listOf(a, b))

/** Items both filters let through. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("filter-and")
fun filterAnd(a: StoryItemFilter, b: StoryItemFilter): StoryItemFilter = StoryItemFilter.AllOf(listOf(a, b))

/** Items [filter] does not let through. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("filter-not")
fun filterNot(filter: StoryItemFilter): StoryItemFilter = StoryItemFilter.Not(filter)

/** Whether [filter] lets [item] through. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("filter-matches")
fun filterMatches(filter: StoryItemFilter, item: ItemStack): Boolean = !item.isEmpty && filter.matches(item)

/** [count] of the items [filter] lets through, as a quest asks for them; a list of requests is a whole order. */
@EditorCategory(StoryCategory.ITEM_FILTERS)
@StoryValue("item-request")
fun itemRequestOf(filter: StoryItemFilter, count: Int = 1): StoryItemRequest = StoryItemRequest(filter, count)

private fun enchantmentHolder(id: ResourceLocation) =
    currentServer.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
        .getHolder(ResourceKey.create(Registries.ENCHANTMENT, id)).orElse(null)
        ?: throw StoryException("There is no enchantment '$id'")
