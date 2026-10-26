package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.delay
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.addons.narrative.story.Action
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.addons.narrative.story.action
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.playSound
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.play
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.playAndWait
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.stopAnimation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** `@wait 2s`. A restart in the middle waits only for what was left: the deadline is kept with the call. */
@Action("wait")
suspend fun waitFor(time: Duration) = action(time) {
    val deadline = capture { System.currentTimeMillis() + time.inWholeMilliseconds }
    perform {
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            delay(minOf(remaining, MAX_SLEEP_MILLIS).milliseconds)
        }
    }
}

/** `@play-sound player minecraft:block.note_block.bell 1.0`. */
@SimpleAction("play-sound")
fun ServerPlayer.playSceneSound(name: String, volume: Float = 1f, pitch: Float = 1f) {
    playSound(name, volume, pitch)
}

/** `@play-once Виталик hello`: plays the animation once and waits for it to end. */
@SimpleAction("play-once")
suspend fun Entity.animateOnce(animation: String, fade: Duration = DEFAULT_FADE) {
    playAndWait(animation, fade.seconds, fade.seconds)
}

/** Plays the animation over and over until it is stopped; does not wait. */
@SimpleAction("play-looped")
fun Entity.animateLooped(animation: String, fade: Duration = DEFAULT_FADE) =
    play(animation, AnimationPlayMode.Loop, fade.seconds, fade.seconds)

/** Plays the animation once and holds its last frame; does not wait. */
@SimpleAction("play-clamped")
fun Entity.animateClamped(animation: String, fade: Duration = DEFAULT_FADE) =
    play(animation, AnimationPlayMode.ClampForever, fade.seconds, fade.seconds)

/** Plays the animation forward and back until it is stopped; does not wait. */
@SimpleAction("play-ping-pong")
fun Entity.animatePingPong(animation: String, fade: Duration = DEFAULT_FADE) =
    play(animation, AnimationPlayMode.PingPong, fade.seconds, fade.seconds)

@SimpleAction("stop-animation")
fun Entity.stopAnimating(animation: String, fade: Duration = DEFAULT_FADE) = stopAnimation(animation, fade.seconds)

private val Duration.seconds: Double get() = inWholeMilliseconds / 1000.0

private val DEFAULT_FADE = 330.milliseconds
private const val MAX_SLEEP_MILLIS = 1_000L
