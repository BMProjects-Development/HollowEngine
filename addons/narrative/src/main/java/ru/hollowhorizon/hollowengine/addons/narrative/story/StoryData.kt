package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.addons.narrative.speech.audience
import ru.hollowhorizon.hollowengine.common.utils.currentServer

/** The player the story is shown to: the first of its audience, or the first on the server. */
suspend fun storyPlayer(): ServerPlayer = audience()?.firstOrNull() ?: currentServer.playerList.players.firstOrNull()
?: throw StoryException("No player is around to read")

/** The player called [name]. */
fun findStoryPlayer(name: String): ServerPlayer =
    currentServer.playerList.getPlayerByName(name) ?: throw StoryException("There is no player '$name'")

fun entityHealth(entity: Entity): Double = (entity as? LivingEntity)?.health?.toDouble() ?: 0.0

fun entityMaxHealth(entity: Entity): Double = (entity as? LivingEntity)?.maxHealth?.toDouble() ?: 0.0

/** The value of the attribute [attribute], such as `minecraft:generic.movement_speed`, or 0 for an entity without it. */
fun entityAttribute(entity: Entity, attribute: String): Double {
    val living = entity as? LivingEntity ?: return 0.0
    val holder = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.parse(attribute)).orElse(null)
        ?: throw StoryException("There is no attribute '$attribute'")
    return living.getAttribute(holder)?.value ?: 0.0
}

fun playerFood(player: Entity): Double = (player as? ServerPlayer)?.foodData?.foodLevel?.toDouble() ?: 0.0

fun playerLevel(player: Entity): Double = (player as? ServerPlayer)?.experienceLevel?.toDouble() ?: 0.0

fun playerGameMode(player: Entity): String = (player as? ServerPlayer)?.gameMode?.gameModeForPlayer?.name?.lowercase().orEmpty()

/** The id of what [player] holds in the main hand. */
fun playerHeldItem(player: Entity): String =
    (player as? ServerPlayer)?.mainHandItem?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }.orEmpty()

/** How many of the item [item] the inventory of [player] holds. */
fun playerItemCount(player: Entity, item: String): Double {
    val inventory = (player as? ServerPlayer)?.inventory ?: return 0.0
    val wanted = BuiltInRegistries.ITEM.get(ResourceLocation.parse(item))
    return inventory.items.filter { it.item == wanted }.sumOf { it.count }.toDouble()
}
