@file:UseSerializers(ForItemStack::class)

package ru.hollowhorizon.hollowengine.common.slots

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Equipable
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemFilter
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemMatchMode
import ru.hollowhorizon.hollowengine.common.slots.SlotFilter.Companion.custom
import ru.hollowhorizon.hollowengine.common.utils.areItemsEqual
import ru.hollowhorizon.hollowengine.common.utils.areStacksEqual
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForItemStack
import ru.hollowhorizon.hollowengine.common.utils.rl

enum class SlotFilterKind { ANY, NONE, ITEMS, TAG, EQUIPMENT, ANY_OF, ALL_OF, NOT, CUSTOM }

/**
 * A describable item test, mirroring the combinators of
 * [ru.hollowhorizon.hollowengine.common.npcs.items.ItemFilter] in a form that survives a trip over
 * the network.
 *
 * The reason for a second filter type is client prediction. A slot's rules decide the outcome of a
 * click, so the client has to evaluate the same rules the server will, or its optimistic result would
 * be wrong on every filtered slot. Sending the *description* and running one shared [matches] on both
 * sides keeps a single implementation of the logic, which a lambda could never do.
 *
 * [custom] is the escape hatch for a predicate that cannot be described. It stays on the server, and
 * its presence marks the layout unpredictable so the client waits for the authoritative answer
 * instead of guessing.
 */
@Serializable
class SlotFilter internal constructor(
    val kind: SlotFilterKind = SlotFilterKind.ANY,
    val items: List<ItemStack> = emptyList(),
    val match: ItemMatchMode = ItemMatchMode.ITEM_AND_COMPONENTS,
    val tag: String = "",
    /** Equipment slot name, as [EquipmentSlot.getName]. A plain string like [tag], so the wire format
     * stays to types this project's NBT serializer is already exercised on. */
    val equipment: String = "",
    val children: List<SlotFilter> = emptyList(),
) {
    @Transient
    private var customFilter: ItemFilter? = null

    @Transient
    private var tagKey: TagKey<Item>? = null

    /** Whether this test can be evaluated client-side, and so whether a click through it can be predicted. */
    val isPredictable: Boolean
        get() = kind != SlotFilterKind.CUSTOM && children.all(SlotFilter::isPredictable)

    fun matches(stack: ItemStack): Boolean = when (kind) {
        SlotFilterKind.ANY -> true
        SlotFilterKind.NONE -> false
        SlotFilterKind.ITEMS -> items.any { template ->
            when (match) {
                ItemMatchMode.ITEM_ONLY -> template.areItemsEqual(stack)
                ItemMatchMode.ITEM_AND_COMPONENTS -> template.areStacksEqual(stack)
            }
        }

        SlotFilterKind.TAG -> stack.`is`(resolvedTag())
        // Reads only item data, so the client evaluates it exactly as the server does and an armor slot
        // never accepts something optimistically that the server is about to refuse.
        SlotFilterKind.EQUIPMENT -> Equipable.get(stack)?.equipmentSlot?.getName() == equipment
        SlotFilterKind.ANY_OF -> children.any { it.matches(stack) }
        SlotFilterKind.ALL_OF -> children.all { it.matches(stack) }
        SlotFilterKind.NOT -> children.none { it.matches(stack) }
        // Only ever true on the client, which has no lambda to run. The layout is unpredictable in
        // that case, so nothing acts on this answer.
        SlotFilterKind.CUSTOM -> customFilter?.matches(stack) ?: true
    }

    private fun resolvedTag(): TagKey<Item> =
        tagKey ?: TagKey.create(Registries.ITEM, tag.rl).also { tagKey = it }

    companion object {
        val Any = SlotFilter(SlotFilterKind.ANY)
        val None = SlotFilter(SlotFilterKind.NONE)

        /** Matches any of [templates]; template counts are ignored, only item and components matter. */
        fun items(
            vararg templates: ItemStack,
            match: ItemMatchMode = ItemMatchMode.ITEM_AND_COMPONENTS,
        ): SlotFilter {
            val copies = templates.filterNot(ItemStack::isEmpty).map { it.copyWithCount(1) }
            require(copies.isNotEmpty()) { "At least one non-empty item template is required" }
            return SlotFilter(SlotFilterKind.ITEMS, items = copies, match = match)
        }

        fun tag(tag: String): SlotFilter = SlotFilter(SlotFilterKind.TAG, tag = tag)

        fun tag(tag: TagKey<Item>): SlotFilter = tag(tag.location().toString())

        /** Matches what can be worn in [slot], which is how armor slots stay picky without a lambda. */
        fun equipment(slot: EquipmentSlot): SlotFilter =
            SlotFilter(SlotFilterKind.EQUIPMENT, equipment = slot.getName())

        fun anyOf(vararg filters: SlotFilter): SlotFilter {
            require(filters.isNotEmpty()) { "At least one filter is required" }
            return SlotFilter(SlotFilterKind.ANY_OF, children = filters.toList())
        }

        fun allOf(vararg filters: SlotFilter): SlotFilter {
            require(filters.isNotEmpty()) { "At least one filter is required" }
            return SlotFilter(SlotFilterKind.ALL_OF, children = filters.toList())
        }

        fun not(filter: SlotFilter): SlotFilter = SlotFilter(SlotFilterKind.NOT, children = listOf(filter))

        /**
         * Wraps an arbitrary predicate. It never leaves the server, and it costs the whole screen its
         * client-side prediction, so prefer a describable filter when one can express the same rule.
         */
        fun custom(filter: ItemFilter): SlotFilter =
            SlotFilter(SlotFilterKind.CUSTOM).apply { customFilter = filter }
    }
}
