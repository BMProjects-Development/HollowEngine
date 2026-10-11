package ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.montages.MontageHandle
import ru.hollowhorizon.hollowengine.common.montages.MontagePlayback

/**
 * Starts [montage] (`<namespace>:montages/<name>`) on this entity over its animator and returns at once.
 * The handle stops it, waits for it and runs code at its events; null when the server cannot read it.
 */
fun Entity.startMontage(
    montage: String,
    fadeIn: Double = 0.2,
    fadeOut: Double = 0.2,
    speed: Double = 1.0,
): MontageHandle? = MontagePlayback.start(this, montage, fadeIn.toFloat(), fadeOut.toFloat(), speed.toFloat())

/**
 * Plays [montage] on this entity and waits until it ends: true if it played through, false if it was
 * stopped, cut short or could not be read. [events] sets up the handle before it starts playing, as
 * `onEvent("fire") { ... }`. Cancelling the wait stops the montage.
 */
suspend fun Entity.playMontage(
    montage: String,
    fadeIn: Double = 0.2,
    fadeOut: Double = 0.2,
    speed: Double = 1.0,
    events: MontageHandle.() -> Unit = {},
): Boolean {
    val handle = startMontage(montage, fadeIn, fadeOut, speed) ?: return false
    handle.events()
    try {
        return handle.await()
    } finally {
        handle.stop()
    }
}

/** Stops [montage] on this entity, however it was started, fading it out over [fadeOut] seconds. */
fun Entity.stopMontage(montage: String, fadeOut: Double = 0.2) {
    MontagePlayback.stop(this, montage, fadeOut.toFloat())
}
