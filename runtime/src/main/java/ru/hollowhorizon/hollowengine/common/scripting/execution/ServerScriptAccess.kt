package ru.hollowhorizon.hollowengine.common.scripting.execution

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.rcon.RconConsoleSource
import ru.hollowhorizon.hollowengine.common.addons.project.remote.canAccessRemoteProject
import ru.hollowhorizon.hollowengine.common.addons.project.remote.isConnectedPlayer
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions

internal const val ServerScriptAccessDenied = "hollowengine.commands.scripting_access_denied"

internal fun ServerPlayer.canControlServerScripts(): Boolean = if (server.isDedicatedServer) canAccessRemoteProject()
else isConnectedPlayer() && server.isSingleplayerOwner(gameProfile) && hasPermissions(PlayerPermissions.GAMEMASTER)

internal fun CommandSourceStack.canControlServerScripts(): Boolean {
    val actor = source
    if (actor is ServerPlayer) return actor.canControlServerScripts()
    return hasPermission(PlayerPermissions.GAMEMASTER) && (actor === server || actor is RconConsoleSource)
}
