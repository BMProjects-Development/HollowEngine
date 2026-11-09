package ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities

import kotlinx.coroutines.delay
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAnimation
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.npcs.NpcAnimationRuntime
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcAction
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcActionKeys
import kotlin.time.Duration.Companion.milliseconds

fun Entity.play(
    @EditorAnimation animation: String,
    playMode: AnimationPlayMode = AnimationPlayMode.Once,
    fadeIn: Double = 0.33,
    fadeOut: Double = 0.33,
) {
    NpcAnimationRuntime.apply(
        entity = this,
        from = null,
        to = animation,
        playMode = playMode,
        duration = 0f,
        fadeIn = fadeIn.toFloat(),
        fadeOut = fadeOut.toFloat(),
    )
}

infix fun Entity.playOnce(@EditorAnimation animation: String) = play(animation)
infix fun Entity.playLooped(@EditorAnimation animation: String) = play(animation, playMode = AnimationPlayMode.Loop)
infix fun Entity.playClamped(@EditorAnimation animation: String) = play(animation, playMode = AnimationPlayMode.ClampForever)
infix fun Entity.playPingPong(@EditorAnimation animation: String) = play(animation, playMode = AnimationPlayMode.PingPong)

fun Entity.stopAnimation(@EditorAnimation animation: String, fadeOut: Double = 0.33) {
    NpcAnimationRuntime.apply(
        entity = this,
        from = animation,
        to = null,
        playMode = AnimationPlayMode.Once,
        duration = fadeOut.toFloat(),
    )
}

suspend fun Entity.playAndWait(
    @EditorAnimation animation: String,
    fadeIn: Double = 0.33,
    fadeOut: Double = 0.33,
): Boolean {
    play(animation, AnimationPlayMode.Once, fadeIn, fadeOut)
    val duration = NpcAnimationRuntime.animationDuration(this, animation) ?: return false
    var completed = false
    try {
        delay(((duration + fadeOut.coerceAtLeast(0.0)) * 1000.0).toLong().milliseconds)
        completed = true
        return true
    } finally {
        if (!completed) stopAnimation(animation, fadeOut = 0.0)
    }
}

fun NpcEntity.startAnimation(
    @EditorAnimation animation: String,
    fadeIn: Double = 0.33,
    fadeOut: Double = 0.33,
): NpcAction<Boolean> = actions.startConcurrent(NpcActionKeys.ANIMATION) {
    playAndWait(animation, fadeIn, fadeOut)
}

fun Entity.stopAllAnimations() {
    NpcAnimationRuntime.clear(this)
}
