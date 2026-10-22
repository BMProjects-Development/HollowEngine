package ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs

import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemFilter
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemRequest
import ru.hollowhorizon.hollowengine.common.slots.storageSlots
import ru.hollowhorizon.hollowengine.common.slots.transferTo

fun NpcEntity.giveItem(stack: ItemStack): ItemStack = inventory.insert(stack)

fun NpcEntity.giveItems(stacks: Iterable<ItemStack>): List<ItemStack> = inventory.insertAll(stacks)

fun NpcEntity.takeItems(filter: ItemFilter, count: Int): List<ItemStack> = inventory.extract(filter, count)

fun NpcEntity.countItems(filter: ItemFilter): Int = inventory.count(filter)

fun NpcEntity.hasItems(request: ItemRequest): Boolean = inventory.contains(request)

fun NpcEntity.hasItems(requests: Iterable<ItemRequest>): Boolean = inventory.containsAll(requests)

fun NpcEntity.equip(slot: EquipmentSlot, filter: ItemFilter): Boolean {
    val stack = inventory.extract(filter, 1).firstOrNull() ?: return false
    val equipped = getItemBySlot(slot).copy()
    setItemSlot(slot, stack)
    if (!equipped.isEmpty) {
        val remainder = inventory.insert(equipped)
        check(remainder.isEmpty) { "Equipped item could not be returned to the NPC inventory" }
    }
    return true
}

fun NpcEntity.unequip(slot: EquipmentSlot): Boolean {
    val equipped = getItemBySlot(slot).copy()
    if (equipped.isEmpty) return false

    setItemSlot(slot, ItemStack.EMPTY)
    val remainder = inventory.insert(equipped)
    if (!remainder.isEmpty) setItemSlot(slot, remainder)
    return remainder.isEmpty
}

fun NpcEntity.dropItems(filter: ItemFilter, count: Int): List<ItemStack> =
    inventory.extract(filter, count).onEach(::dropItem)

fun NpcEntity.transferItems(target: NpcEntity, filter: ItemFilter, count: Int): Int =
    inventory.slots.transferTo(target.inventory.slots, filter, count)

fun NpcEntity.transferItems(target: Player, filter: ItemFilter, count: Int): Int =
    inventory.slots.transferTo(target.storageSlots(), filter, count)

/**
 * Forces an NPC to drop an item from its inventory.
 *
 * @param item The item to be dropped.
 */
fun NpcEntity.dropItem(item: ItemStack) {
    val p = position()
    val entityStack = ItemEntity(level(), p.x, p.y + eyeHeight, p.z, item)
    entityStack.setDefaultPickUpDelay()
    val f8 = Mth.sin(xRot * Mth.PI / 180f)
    val f3 = Mth.sin(yHeadRot * Mth.PI / 180f)
    val f4 = Mth.cos(yHeadRot * Mth.PI / 180f)
    entityStack.setDeltaMovement(-f3 * 0.3, -f8 * 0.3 + 0.1, f4 * 0.3)
    level().addFreshEntity(entityStack)
}
