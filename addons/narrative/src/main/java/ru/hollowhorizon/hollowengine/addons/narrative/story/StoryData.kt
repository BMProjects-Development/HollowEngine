package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.speech.audience
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.utils.currentServer

/** The player the story is shown to: the first of its audience, or the first on the server. */
suspend fun storyPlayer(): ServerPlayer = audience()?.firstOrNull() ?: currentServer.playerList.players.firstOrNull()
?: throw StoryException("No player is around to read")

/** The players the story is shown to: its audience, or everyone on the server when it has none. */
suspend fun storyPlayers(): List<ServerPlayer> = audience() ?: currentServer.playerList.players.toList()

/** The player called [name]. */
@EditorCategory(StoryCategory.PLAYERS)
@StoryValue("find-player")
fun findStoryPlayer(name: String): ServerPlayer =
    currentServer.playerList.getPlayerByName(name) ?: throw StoryException("There is no player '$name'")
