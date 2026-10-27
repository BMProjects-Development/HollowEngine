package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes a player's clicks to the scene they are in.
 */
object DialogueInput {
    private val running = CopyOnWriteArrayList<ScenePlayer>()

    internal fun register(scene: ScenePlayer) {
        running.remove(scene)
        running += scene
    }

    internal fun unregister(scene: ScenePlayer) {
        running.remove(scene)
    }

    /** The scene [player] is taking part in, most recently started first. */
    fun sessionOf(player: ServerPlayer): SceneSession? =
        running.lastOrNull { it.isRunning && player in it.participants }

    /** Advances the line [player] is reading. Returns false when they are not in a scene. */
    fun advance(player: ServerPlayer): Boolean {
        val session = sessionOf(player) ?: return false
        session.advance(player)
        return true
    }

    /** Votes for the menu option at [index]. Returns false when no menu is open for [player]. */
    fun choose(player: ServerPlayer, index: Int): Boolean {
        val session = sessionOf(player) ?: return false
        if (session.openMenuSize <= index || index < 0) return false
        session.choose(player, index)
        return true
    }
}
