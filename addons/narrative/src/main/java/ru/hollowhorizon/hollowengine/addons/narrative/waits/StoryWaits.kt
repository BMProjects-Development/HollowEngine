package ru.hollowhorizon.hollowengine.addons.narrative.waits

import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.items.StoryItemFilter
import ru.hollowhorizon.hollowengine.addons.narrative.items.StoryItemRequest
import ru.hollowhorizon.hollowengine.addons.narrative.items.hasAllItems
import ru.hollowhorizon.hollowengine.addons.narrative.items.hasItems
import ru.hollowhorizon.hollowengine.addons.narrative.story.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.events.entity.LivingEntityDeathEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerInteractEvent
import ru.hollowhorizon.hollowengine.common.events.factory.await
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.player.Key
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.player.waitKey
import java.lang.reflect.Modifier
import kotlin.time.Duration.Companion.milliseconds

/**
 * Waits until [this] presses [key], such as `e`, `space` or `left-shift`, and returns the key pressed; with no key
 * any key will do. The key still does what it always does.
 */
@EditorCategory(StoryCategory.WAIT)
@SimpleAction("wait-key")
suspend fun ServerPlayer.waitForKey(@Suggestions(KeyNames::class) key: String = ""): String {
    val code = if (key.isBlank()) waitKey() else waitKey(KeyNames.code(key))
    return KeyNames.name(code)
}

/** Waits until [who] comes within [radius] blocks of [pos], or, with [leave], until it is farther than that. */
@EditorCategory(StoryCategory.WAIT)
@SimpleAction("wait-position")
suspend fun waitForPosition(who: Entity, pos: Vec3, radius: Double = 1.0, leave: Boolean = false) {
    while ((who.distanceToSqr(pos) <= radius * radius) == leave) delay(TICK)
}

/**
 * Waits until a player clicks [target] with the right button, [by] when it is given, and returns that player. With
 * [consume] the click does nothing else, so a villager does not open its trades.
 */
@EditorCategory(StoryCategory.WAIT)
@SimpleAction("wait-interact")
suspend fun waitForInteract(target: Entity, by: ServerPlayer? = null, consume: Boolean = true): ServerPlayer {
    val event = PlayerInteractEvent.EntityInteract.await { event ->
        val player = event.player
        val matches =
            player is ServerPlayer && event.hand == InteractionHand.MAIN_HAND && event.target.uuid == target.uuid && (by == null || player.uuid == by.uuid)
        if (matches && consume) event.isCanceled = true
        matches
    }
    return event.player as ServerPlayer
}

/**
 * Waits until a player clicks the block at [pos] with the right button, [by] when it is given, and returns that
 * player. With [consume] the click does nothing else, so a door stays shut.
 */
@EditorCategory(StoryCategory.WAIT)
@SimpleAction("wait-use-block")
suspend fun waitForBlockUse(pos: Vec3, by: ServerPlayer? = null, consume: Boolean = false): ServerPlayer {
    val block = BlockPos.containing(pos)
    val event = PlayerInteractEvent.BlockInteract.await { event ->
        val player = event.player
        val matches =
            player is ServerPlayer && event.hand == InteractionHand.MAIN_HAND && event.state.blockPos == block && (by == null || player.uuid == by.uuid)
        if (matches && consume) event.isCanceled = true
        matches
    }
    return event.player as ServerPlayer
}

/** Waits until [entity] dies; at once when it is dead already. */
@EditorCategory(StoryCategory.WAIT)
@SimpleAction("wait-death")
suspend fun waitForDeath(entity: LivingEntity) {
    if (entity.isDeadOrDying) return
    LivingEntityDeathEvent.await { it.entity.uuid == entity.uuid }
}

/** Waits until [player] carries [count] of the items [filter] lets through. */
@EditorCategory(StoryCategory.WAIT)
@SimpleAction("wait-items")
suspend fun waitForItems(player: ServerPlayer, filter: StoryItemFilter, count: Int = 1) {
    while (!hasItems(player, filter, count)) delay(TICK)
}

/** Waits until [player] carries everything [requests] asks for at once, as a quest that wants several items does. */
@EditorCategory(StoryCategory.WAIT)
@SimpleAction("wait-all-items")
suspend fun waitForRequests(player: ServerPlayer, requests: List<StoryItemRequest>) {
    while (!hasAllItems(player, requests)) delay(TICK)
}

/** The keys [waitForKey] knows, by the names of [Key] in lower case with dashes. */
object KeyNames : SuggestionSource {
    private val codes: Map<String, Int> =
        Key::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name.lowercase().replace('_', '-') to it.getInt(null) }
    private val names = codes.entries.associate { (name, code) -> code to name }

    override val values: List<String> = codes.keys.sorted()

    /** The code of the key [name], or of the character it is when it is one. */
    fun code(name: String): Int {
        val typed = name.trim().lowercase()
        codes[typed]?.let { return it }
        if (typed.length == 1) return Key.code(typed[0])
        throw StoryException("There is no key '$name'")
    }

    /** The name of the key [code], or the code itself for a key without one. */
    fun name(code: Int): String = names[code] ?: code.toString()
}

private val TICK = 50.milliseconds
