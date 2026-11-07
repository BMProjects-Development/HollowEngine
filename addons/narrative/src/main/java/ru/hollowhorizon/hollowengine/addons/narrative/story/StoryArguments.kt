package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueCharacter
import ru.hollowhorizon.hollowengine.addons.narrative.speech.audience
import ru.hollowhorizon.hollowengine.common.utils.currentServer

/** What the fields of a story card compile to: values written the way a writer types them. */

fun vec(x: Number, y: Number, z: Number) = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

fun blockPos(x: Int, y: Int, z: Int) = BlockPos(x, y, z)

fun location(text: String): ResourceLocation = ResourceLocation.tryParse(text)
    ?: throw StoryException("'$text' is not an id like minecraft:stone")

fun dimension(text: String): ServerLevel = currentServer.getLevel(ResourceKey.create(Registries.DIMENSION, location(text)))
    ?: throw StoryException("No dimension is named '$text'")

fun character(name: String): DialogueCharacter = DialogueCharacter.of(name)

/** The player of the scene named [name], or its first one for `player`. */
suspend fun actor(name: String): LivingEntity {
    val players = audience().orEmpty()
    val found = if (name.equals(PLAYER, ignoreCase = true)) players.firstOrNull()
    else players.firstOrNull { it.scoreboardName == name }
    return found ?: throw StoryException("No player of the scene is named '$name'")
}

/** The level of the first player of the scene, or the overworld. */
suspend fun storyLevel(): ServerLevel = audience()?.firstOrNull()?.serverLevel() ?: currentServer.overworld()

private const val PLAYER = "player"
