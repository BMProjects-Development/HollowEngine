package ru.hollowhorizon.hollowengine.common.scripting.story.functions.player

import kotlinx.coroutines.delay
import net.minecraft.ChatFormatting
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.utils.colored
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.plus
import ru.hollowhorizon.hollowengine.common.utils.sendToast

suspend fun Player.waitPos(pos: Vec3, radius: Float = 1f, inverse: Boolean = false) {
    if (inverse) {
        while (distanceToSqr(pos) <= radius * radius) delay(50)
    } else {
        while (distanceToSqr(pos) >= radius * radius) delay(50)
    }
}

fun Player.say(text: String) =
    sendSystemMessage("[".literal.colored(ChatFormatting.GRAY) + name + "] ".literal.colored(ChatFormatting.GRAY) + text.literal)

infix fun Player.send(text: String) = sendSystemMessage(text.literal)
fun Player.notify(text: String) = sendToast(text.literal)