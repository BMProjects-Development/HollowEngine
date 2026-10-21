package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes a player's clicks to the dialogue they are in.
 */
object DialogueInput {
    private val running = CopyOnWriteArrayList<DialogueController>()

    internal fun register(controller: DialogueController) {
        running.remove(controller)
        running += controller
    }

    internal fun unregister(controller: DialogueController) {
        running.remove(controller)
    }

    /** The dialogue [player] is taking part in, most recently started first. */
    fun sessionOf(player: ServerPlayer): DialogueController? =
        running.lastOrNull { it.isRunning && player in it.participants }

    /** Advances the line [player] is reading. Returns false when they are not in a dialogue. */
    fun advance(player: ServerPlayer): Boolean {
        val controller = sessionOf(player) ?: return false
        controller.advance(player)
        return true
    }

    /** Votes for the menu option at [index]. Returns false when no menu is open for [player]. */
    fun choose(player: ServerPlayer, index: Int): Boolean {
        val controller = sessionOf(player) ?: return false
        if (controller.openMenuSize <= index || index < 0) return false
        controller.choose(player, index)
        return true
    }
}
