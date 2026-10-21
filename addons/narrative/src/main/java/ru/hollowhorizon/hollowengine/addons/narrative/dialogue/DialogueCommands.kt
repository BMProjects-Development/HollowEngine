package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import ru.hollowhorizon.hollowengine.common.utils.mcTranslate

/**
 * `/he dialogue advance` and `/he dialogue choose <n>`: how a player plays a dialogue from chat, and
 * what a presenter's own buttons can run.
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
        if (DialogueInput.advance(context.source.playerOrException)) return 1
        context.source.sendFailure("hollowengine.commands.dialogue_not_in_dialogue".mcTranslate)
        return 0
    }

    private fun choose(context: CommandContext<CommandSourceStack>): Int {
        val option = IntegerArgumentType.getInteger(context, "option")
        if (DialogueInput.choose(context.source.playerOrException, option)) return 1
        context.source.sendFailure("hollowengine.commands.dialogue_no_such_option".mcTranslate(option))
        return 0
    }
}
