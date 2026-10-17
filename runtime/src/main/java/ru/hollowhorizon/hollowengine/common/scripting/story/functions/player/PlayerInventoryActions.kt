package ru.hollowhorizon.hollowengine.common.scripting.story.functions.player

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemFilter
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemRequest
import ru.hollowhorizon.hollowengine.common.slots.*

fun Player.giveItem(stack: ItemStack): ItemStack = storageSlots().insert(stack)

fun Player.giveItems(stacks: Iterable<ItemStack>): List<ItemStack> = storageSlots().insertAll(stacks)

fun Player.takeItems(filter: ItemFilter, count: Int): List<ItemStack> = storageSlots().extract(filter, count)

fun Player.countItems(filter: ItemFilter): Int = storageSlots().count(filter)

fun Player.hasItems(request: ItemRequest): Boolean = storageSlots().contains(request)

fun Player.hasItems(requests: Iterable<ItemRequest>): Boolean = storageSlots().containsAll(requests)

/**
 * Equips a matching item from storage, returning `false` when the player has nothing that matches.
 *
 * Whatever was worn there goes back into storage, or is dropped at the player's feet when storage is full:
 * the new item takes the slot either way, and a full inventory is not a reason to delete the old one.
 */
fun Player.equip(slot: EquipmentSlot, filter: ItemFilter): Boolean {
    val storage = storageSlots()
    val stack = storage.extract(filter, 1).firstOrNull() ?: return false
    val equipped = getItemBySlot(slot).copy()
    setItemSlot(slot, stack)
    if (!equipped.isEmpty) {
        val remainder = storage.insert(equipped)
        if (!remainder.isEmpty) dropItem(remainder)
    }
    return true
}

/** Puts an equipped item back into storage. Returns `false` when the slot is empty or nothing fit. */
fun Player.unequip(slot: EquipmentSlot): Boolean {
    val equipped = getItemBySlot(slot).copy()
    if (equipped.isEmpty) return false

    // Cleared before inserting: a player's main hand *is* one of the storage slots, so inserting first would
    // top the stack up in place and the clear would then wipe both halves of it.
    setItemSlot(slot, ItemStack.EMPTY)
    val remainder = storageSlots().insert(equipped)
    if (!remainder.isEmpty) setItemSlot(slot, remainder)
    return remainder.isEmpty
}

/** Throws a stack in front of the player, the same way pressing the drop key does. */
fun Player.dropItem(item: ItemStack) {
    drop(item, false)
}

fun Player.dropItems(filter: ItemFilter, count: Int): List<ItemStack> =
    storageSlots().extract(filter, count).onEach(::dropItem)

fun Player.transferItems(target: Player, filter: ItemFilter, count: Int): Int =
    storageSlots().transferTo(target.storageSlots(), filter, count)

fun Player.transferItems(target: NpcEntity, filter: ItemFilter, count: Int): Int =
    storageSlots().transferTo(target.inventory.slots, filter, count)
