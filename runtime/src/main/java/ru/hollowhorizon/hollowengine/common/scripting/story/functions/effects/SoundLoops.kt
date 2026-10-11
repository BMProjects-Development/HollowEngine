package ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.common.sounds.SoundRepeat

/** Compatibility DSL backed by the same playback owner as every other sound. */
@SoundDsl
class SoundLoopBuilder : SoundBuilder() {
    init { repeat = SoundRepeat.Forever; fadeIn = 1f; fadeOut = 1f }
}

fun ServerPlayer.playLoop(id: String, location: String, block: SoundLoopBuilder.() -> Unit = {}) {
    SoundLoopBuilder().also { it.id = id; it.location = location }.apply(block).packet().send(this)
}

fun ServerPlayer.stopLoop(id: String) = stopSound(id)
fun Level.playLoop(id: String, location: String, block: SoundLoopBuilder.() -> Unit = {}) =
    players().filterIsInstance<ServerPlayer>().forEach { it.playLoop(id, location, block) }
fun Level.stopLoop(id: String) = stopSound(id)
