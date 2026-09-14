package ru.hollowhorizon.hollowengine.common.events.registry

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

/**
 * Fires on every datapack load, after the server reload scripts have been run, with the command tree the
 * server switches to once the load completes.
 */
class RegisterCommandsEvent(
    val dispatcher: CommandDispatcher<CommandSourceStack>,
    val registryAccess: CommandBuildContext,
    val environment: Commands.CommandSelection,
) : ServerEvent {
    companion object : EventHandler<RegisterCommandsEvent>()
}

/** Fires whenever the client rebuilds its command tree, which happens when it joins a world. */
class RegisterClientCommandsEvent(
    val dispatcher: CommandDispatcher<SharedSuggestionProvider>,
    val registryAccess: CommandBuildContext,
) : ClientEvent {
    companion object : EventHandler<RegisterClientCommandsEvent>()
}
