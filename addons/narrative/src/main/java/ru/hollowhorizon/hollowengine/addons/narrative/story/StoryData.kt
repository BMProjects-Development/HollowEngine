package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.speech.audience
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.utils.currentServer

/** The player the story is shown to: the first of its audience, or the first on the server. */
@EditorCategory(StoryCategory.STORY)
@StoryValue("story-player")
suspend fun storyPlayer(): ServerPlayer = audience()?.firstOrNull() ?: currentServer.playerList.players.firstOrNull()
?: throw StoryException("No player is around to read")

/** The level the story happens in: that of its first player. */
@EditorCategory(StoryCategory.STORY)
@StoryValue("story-level")
suspend fun storyLevelValue(): ServerLevel = storyLevel()

/** The player called [name]. */
@EditorCategory(StoryCategory.PLAYERS)
@StoryValue("find-player")
fun findStoryPlayer(name: String): ServerPlayer =
    currentServer.playerList.getPlayerByName(name) ?: throw StoryException("There is no player '$name'")
