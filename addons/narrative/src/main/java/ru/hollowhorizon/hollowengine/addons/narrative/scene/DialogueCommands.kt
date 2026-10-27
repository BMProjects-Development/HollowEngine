package ru.hollowhorizon.hollowengine.addons.narrative.scene

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import ru.hollowhorizon.hollowengine.addons.narrative.poll.Polls
import ru.hollowhorizon.hollowengine.addons.narrative.speech.Reading
import ru.hollowhorizon.hollowengine.common.utils.mcTranslate

/**
 * `/hollowengine dialogue advance` and `/hollowengine dialogue choose <n>`: how a player moves past a line and votes
 * from chat, and what the buttons of a line or a poll can run.
 */
internal object DialogueCommands {
    fun register(root: CommandNode<CommandSourceStack>) {
        val command = Commands.literal("dialogue")
            .then(Commands.literal("advance").executes(::advance))
            .then(
                Commands.literal("choose").then(
                    Commands.argument("option", IntegerArgumentType.integer(0)).executes(::choose),
                ),
            )
        root.addChild(command.build())
    }

    private fun advance(context: CommandContext<CommandSourceStack>): Int {
        if (Reading.advance(context.source.playerOrException)) return 1
        context.source.sendFailure("hollowengine.commands.dialogue_not_in_dialogue".mcTranslate)
        return 0
    }

    private fun choose(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val option = IntegerArgumentType.getInteger(context, "option")
        if (Polls.of(player)?.vote(player, option) == true) return 1
        context.source.sendFailure("hollowengine.commands.dialogue_no_such_option".mcTranslate(option))
        return 0
    }
}
