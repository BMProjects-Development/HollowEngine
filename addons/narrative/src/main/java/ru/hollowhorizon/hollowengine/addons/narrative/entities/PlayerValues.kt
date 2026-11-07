package ru.hollowhorizon.hollowengine.addons.narrative.entities

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValue
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory

@EditorCategory(StoryCategory.GAME_MODE)
@StoryValue("game-mode")
fun gameModeOf(player: ServerPlayer): GameType = player.gameMode.gameModeForPlayer

/** How full [player] is, from 0 to 20. */
@EditorCategory(StoryCategory.FOOD)
@StoryValue("food")
fun foodOf(player: ServerPlayer): Int = player.foodData.foodLevel

/** What keeps [player] from getting hungry, spent before the food. */
@EditorCategory(StoryCategory.FOOD)
@StoryValue("saturation")
fun saturationOf(player: ServerPlayer): Float = player.foodData.saturationLevel

@EditorCategory(StoryCategory.EXPERIENCE)
@StoryValue("experience-level")
fun experienceLevelOf(player: ServerPlayer): Int = player.experienceLevel

/** How far [player] is to the next level, from 0 to 1. */
@EditorCategory(StoryCategory.EXPERIENCE)
@StoryValue("experience-progress")
fun experienceProgressOf(player: ServerPlayer): Float = player.experienceProgress

@EditorCategory(StoryCategory.PLAYERS)
@StoryValue("flying")
fun isFlying(player: ServerPlayer): Boolean = player.abilities.flying
