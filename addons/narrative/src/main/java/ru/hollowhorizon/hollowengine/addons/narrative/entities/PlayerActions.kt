package ru.hollowhorizon.hollowengine.addons.narrative.entities

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import ru.hollowhorizon.hollowengine.addons.narrative.story.Action
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.action
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory

/** Switches [this] to [mode]. Undone, they play in the mode they played in. */
@EditorCategory(StoryCategory.GAME_MODE)
@Action("set-game-mode")
suspend fun ServerPlayer.changeGameMode(mode: GameType) = action(this, mode) {
    val before = capture { gameMode.gameModeForPlayer }
    undo { setGameMode(before) }
    perform { setGameMode(mode) }
}

/** Adds [levels] of experience to [this]; fewer than none takes them. Undone, they have the experience they had. */
@EditorCategory(StoryCategory.EXPERIENCE)
@Action("give-levels")
suspend fun ServerPlayer.giveLevels(levels: Int) = action(this, levels) {
    val level = capture { experienceLevel }
    val progress = capture { experienceProgress }
    val total = capture { totalExperience }
    undo {
        experienceLevel = level
        experienceProgress = progress
        totalExperience = total
        setExperienceLevels(level)
    }
    perform { giveExperienceLevels(levels) }
}

/** Sets how full [this] is, from 0 to 20, and their [saturation]. Undone, they are as full as they were. */
@EditorCategory(StoryCategory.FOOD)
@Action("set-food")
suspend fun ServerPlayer.changeFood(food: Int, saturation: Float = 5f) = action(this, food, saturation) {
    val level = capture { foodData.foodLevel }
    val saturated = capture { foodData.saturationLevel }
    undo {
        foodData.foodLevel = level
        foodData.setSaturation(saturated)
    }
    perform {
        foodData.foodLevel = food.coerceIn(0, MAX_FOOD)
        foodData.setSaturation(saturation)
    }
}

/** Shows [text] to [this] in the chat, or above the hotbar with [actionBar]. */
@EditorCategory(StoryCategory.PLAYERS)
@SimpleAction("message")
fun ServerPlayer.message(text: String, actionBar: Boolean = false) {
    displayClientMessage(Component.literal(text), actionBar)
}

private const val MAX_FOOD = 20
