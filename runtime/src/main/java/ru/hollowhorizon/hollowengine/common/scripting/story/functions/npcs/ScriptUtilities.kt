package ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs

import com.mojang.brigadier.StringReader
import kotlinx.coroutines.delay
import net.minecraft.ChatFormatting
import net.minecraft.commands.arguments.item.ItemParser
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.utils.colored
import ru.hollowhorizon.hollowengine.common.utils.currentServerOrNull
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.plus
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pauses the coroutine for the specified number of ticks.
 *
 * @param time The number of ticks to delay.
 */
suspend fun wait(time: Int) {
    delay((time * 50L).milliseconds)
}

fun uuid(uuid: String): UUID = UUID.fromString(uuid)

/**
 * Создает ItemStack с указанным предметом, количеством и NBT-тегом.
 *
 * @param item Идентификатор предмета (например, "minecraft:apple").
 * @param count Количество предметов в стаке.
 * @param nbt NBT-тег для предмета.
 * @return ItemStack с заданными параметрами.
 */
fun item(item: String, count: Int = 1): ItemStack = item(item, count, DataComponentPatch.EMPTY)

private val builtInRegistryAccess by lazy {
    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
}

fun item(item: String, count: Int = 1, components: DataComponentPatch): ItemStack {
    require(count > 0) { "Item count must be greater than zero" }
    val reader = StringReader(item)
    val registries = currentServerOrNull()?.registryAccess() ?: builtInRegistryAccess
    val parsed = runCatching { ItemParser(registries).parse(reader) }
        .getOrElse { error -> throw IllegalArgumentException("Invalid item specification '$item'", error) }
    require(!reader.canRead()) { "Unexpected trailing data in item specification '$item'" }

    return ItemStack(parsed.item, count).apply {
        applyComponents(parsed.components)
        applyComponents(components)
    }
}

/**
 * A getter variable that converts seconds to ticks.
 *
 * @param this The number to be converted from ticks to seconds
 * @template
 * ```node.kts
 * 10.sec // Converts 10 seconds to ticks (10 * 20 = 200)
 * ```
 */
val Int.sec get() = this * 20

val Double.sec get() = (this * 20).toInt()

/**
 * Makes an NPC "say" the text by sending a message to all players on the server.
 *
 * @param text The text of the message.
 */
infix fun NpcEntity.say(text: String) {
    server?.playerList?.players?.forEach {
        it.sendSystemMessage(
            "[".literal.colored(ChatFormatting.GRAY) +
            name +
            "]: ".literal.colored(ChatFormatting.GRAY) +
            text.literal
        )
    }
}

