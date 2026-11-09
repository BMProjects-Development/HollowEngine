package ru.hollowhorizon.hollowengine.addons.narrative.items

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParamTrait
import ru.hollowhorizon.hollowengine.addons.narrative.story.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorWidget
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.player.countItems
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.player.dropItem
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.player.giveItem
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.player.takeItems

/**
 * Gives [this] the stack [item], as many as it holds; what does not fit falls at their feet. Undone, the inventory is
 * as it was before, and what fell stays where it is.
 */
@EditorCategory(StoryCategory.INVENTORY)
@Action("give-item")
suspend fun ServerPlayer.giveStack(item: ItemStack) = action(this, item) {
    keepInventory(this@giveStack)
    perform {
        var left = item.count
        while (left > 0 && !item.isEmpty) {
            val stack = item.copyWithCount(minOf(left, item.maxStackSize))
            left -= stack.count
            val remainder = giveItem(stack)
            if (!remainder.isEmpty) dropItem(remainder)
        }
        containerMenu.broadcastChanges()
    }
}

/**
 * Takes [count] of the items [filter] lets through from [this], and only when they carry that many: otherwise takes
 * nothing and returns false. Undone, the inventory is as it was before.
 */
@EditorCategory(StoryCategory.INVENTORY)
@Action("take-items")
suspend fun ServerPlayer.takeMatching(filter: StoryItemFilter, count: Int = 1): Boolean = action(this, filter, count) {
    keepInventory(this@takeMatching)
    perform {
        if (countItems(filter) < count) return@perform false
        takeItems(filter, count)
        containerMenu.broadcastChanges()
        true
    }
}

/**
 * Takes every request of [requests] from [this], and only when they carry all of it: a quest that wants five logs
 * and three apples takes either both or nothing, and returns whether it took. Undone, the inventory is as it was.
 */
@EditorCategory(StoryCategory.INVENTORY)
@Action("take-all-items")
suspend fun ServerPlayer.takeRequests(requests: List<StoryItemRequest>): Boolean = action(this, requests) {
    keepInventory(this@takeRequests)
    perform {
        if (!hasAllItems(this@takeRequests, requests)) return@perform false
        requests.forEach { takeItems(it.filter, it.count) }
        containerMenu.broadcastChanges()
        true
    }
}

/**
 * Puts [item] into the slot [slot] of the inventory of [this], in place of what was there. Undone, the slot holds
 * what it held before.
 */
@EditorCategory(StoryCategory.INVENTORY)
@Action("set-inventory-slot")
suspend fun ServerPlayer.putInSlot(@EditorWidget(StoryParamTrait.Widget.INVENTORY_SLOT) slot: Int, item: ItemStack) =
    action(this, slot, item) {
        checkSlot(this@putInSlot, slot)
        val before = capture { inventory.getItem(slot).copy() }
        undo {
            inventory.setItem(slot, before.copy())
            containerMenu.broadcastChanges()
        }
        perform {
            inventory.setItem(slot, item.copy())
            containerMenu.broadcastChanges()
        }
    }

/** Puts [item] into [slot] of [this], in place of what was there. Undone, the slot holds what it held before. */
@EditorCategory(StoryCategory.EQUIPMENT)
@Action("equip")
suspend fun LivingEntity.equip(slot: EquipmentSlot = EquipmentSlot.MAINHAND, item: ItemStack) =
    action(this, slot, item) {
        val before = capture { getItemBySlot(slot).copy() }
        undo { setItemSlot(slot, before.copy()) }
        perform { setItemSlot(slot, item.copy()) }
    }

/** What [who] holds in [hand]. */
@EditorCategory(StoryCategory.EQUIPMENT)
@StoryValue("held-item")
fun heldItemOf(who: LivingEntity, hand: InteractionHand = InteractionHand.MAIN_HAND): ItemStack =
    who.getItemInHand(hand)

/** What [who] wears or holds in [slot]. */
@EditorCategory(StoryCategory.EQUIPMENT)
@StoryValue("equipment")
fun equipmentOf(who: LivingEntity, slot: EquipmentSlot): ItemStack = who.getItemBySlot(slot)

/** The stack in the slot [slot] of the inventory of [player]. */
@EditorCategory(StoryCategory.INVENTORY)
@StoryValue("inventory-slot")
fun inventorySlotOf(player: ServerPlayer, @EditorWidget(StoryParamTrait.Widget.INVENTORY_SLOT) slot: Int): ItemStack {
    checkSlot(player, slot)
    return player.inventory.getItem(slot)
}

/** The stacks [player] carries, without empty slots. */
@EditorCategory(StoryCategory.INVENTORY)
@StoryValue("inventory-items")
fun inventoryItemsOf(player: ServerPlayer): List<ItemStack> =
    (player.inventory.items + player.inventory.offhand + player.inventory.armor).filterNot { it.isEmpty }

/** How many of the items [filter] lets through [player] carries. */
@EditorCategory(StoryCategory.INVENTORY)
@StoryValue("count-items")
fun countMatching(player: ServerPlayer, filter: StoryItemFilter): Int = player.countItems(filter)

/** Whether [player] carries [count] of the items [filter] lets through. */
@EditorCategory(StoryCategory.INVENTORY)
@StoryValue("has-items")
fun hasItems(player: ServerPlayer, filter: StoryItemFilter, count: Int = 1): Boolean =
    player.countItems(filter) >= count

/** Whether [player] carries everything [requests] asks for at once, an item counting for one request only. */
@EditorCategory(StoryCategory.INVENTORY)
@StoryValue("has-all-items")
fun hasAllItems(player: ServerPlayer, requests: List<StoryItemRequest>): Boolean =
    missingItems(player, requests).isEmpty()

/** What of [requests] [player] lacks, each request with the count still missing; nothing when they have it all. */
@EditorCategory(StoryCategory.INVENTORY)
@StoryValue("missing-items")
fun missingItems(player: ServerPlayer, requests: List<StoryItemRequest>): List<StoryItemRequest> {
    val left = inventoryItemsOf(player).map { it.copy() }
    return requests.mapNotNull { request ->
        var needed = request.count
        left.filter { !it.isEmpty && request.filter.matches(it) }.forEach { stack ->
            val taken = minOf(needed, stack.count)
            stack.shrink(taken)
            needed -= taken
        }
        if (needed > 0) StoryItemRequest(request.filter, needed) else null
    }
}

/** Keeps every slot of the inventory of [player] and puts each back on undo. */
internal fun CaptureScope.keepInventory(player: ServerPlayer) {
    val inventory = player.inventory
    val items = capture { (0 until inventory.containerSize).map { inventory.getItem(it).copy() } }
    undo {
        items.forEachIndexed { slot, stack -> inventory.setItem(slot, stack.copy()) }
        player.containerMenu.broadcastChanges()
    }
}

private fun checkSlot(player: ServerPlayer, slot: Int) {
    if (slot !in 0 until player.inventory.containerSize) {
        throw StoryException("The inventory has slots 0 to ${player.inventory.containerSize - 1}, not $slot")
    }
}
