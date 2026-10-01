package ru.hollowhorizon.hollowengine.common.events.entity.player

import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import ru.hollowhorizon.hollowengine.common.colliders.ColliderHit
import ru.hollowhorizon.hollowengine.common.events.Cancellable
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

abstract class PlayerInteractEvent(player: Player) : PlayerEvent(player), Cancellable {
    override var isCanceled: Boolean = false

    /** [collider] is the collider of [target] the player clicked, when it was clicked through one. */
    class EntityInteract(
        player: Player,
        val hand: InteractionHand,
        val target: Entity,
        val collider: ColliderHit? = null,
    ) : PlayerInteractEvent(player) {
        companion object: EventHandler<EntityInteract>()
    }
    class BlockInteract(player: Player, val hand: InteractionHand, val state: BlockHitResult) : PlayerInteractEvent(player) {
        companion object: EventHandler<BlockInteract>()
    }
    class ItemInteract(player: Player, val hand: InteractionHand, val itemStack: ItemStack) : PlayerInteractEvent(player) {
        companion object: EventHandler<ItemInteract>()
    }
}
