package ru.hollowhorizon.hollowengine.common.npcs.inventory

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemFilter
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemRequest
import ru.hollowhorizon.hollowengine.common.slots.*

/**
 * An NPC's carried items.
 *
 * Storage and stacking both live in [ru.hollowhorizon.hollowengine.common.slots]: this class is the
 * NPC-facing name for them plus persistence, so a script's `giveItem` and a player's shift-click into
 * the same inventory cannot disagree about what fits where. [slots] is what a slot UI binds to.
 */
class NpcInventory(size: Int = DEFAULT_SIZE) {
    val slots = SimpleSlotSource(requireValidSize(size))

    val size: Int
        get() = slots.size

    fun contents(): List<ItemStack> = slots.contents()

    fun get(slot: Int): ItemStack = slots[slot]

    fun set(slot: Int, stack: ItemStack): ItemStack {
        val previous = slots[slot]
        slots[slot] = stack
        return previous
    }

    fun resize(size: Int): List<ItemStack> = slots.resize(requireValidSize(size))

    fun insert(stack: ItemStack): ItemStack = slots.insert(stack)

    fun insertAll(stacks: Iterable<ItemStack>): List<ItemStack> = slots.insertAll(stacks)

    fun extract(filter: ItemFilter, count: Int): List<ItemStack> = slots.extract(filter, count)

    fun count(filter: ItemFilter): Int = slots.count(filter)

    fun contains(request: ItemRequest): Boolean = slots.contains(request)

    fun containsAll(requests: Iterable<ItemRequest>): Boolean = slots.containsAll(requests)

    fun clear(): List<ItemStack> = slots.clearAll()

    fun save(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putInt(SIZE_KEY, size)
        slots.saveItems(tag, registries)
    }

    fun load(tag: CompoundTag, registries: HolderLookup.Provider) {
        val savedSize = tag.getInt(SIZE_KEY).takeIf { it > 0 } ?: DEFAULT_SIZE
        slots.loadItems(tag, registries, requireValidSize(savedSize))
    }

    companion object {
        const val DEFAULT_SIZE = 36
        private const val MAX_SIZE = 256
        private const val SIZE_KEY = "Size"

        private fun requireValidSize(size: Int): Int {
            require(size in 1..MAX_SIZE) { "NPC inventory size must be between 1 and $MAX_SIZE" }
            return size
        }
    }
}
