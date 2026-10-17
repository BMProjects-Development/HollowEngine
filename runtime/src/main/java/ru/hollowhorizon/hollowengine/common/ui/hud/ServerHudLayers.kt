package ru.hollowhorizon.hollowengine.common.ui.hud

import net.minecraft.resources.ResourceLocation
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which HUD layers the server has hidden per player, so the set survives a re-send and can
 * be restored after a respawn or dimension change without the caller tracking it.
 */
object ServerHudLayers {
    private val hidden = ConcurrentHashMap<UUID, Set<ResourceLocation>>()

    operator fun get(player: UUID): Set<ResourceLocation> = hidden[player].orEmpty()

    operator fun set(player: UUID, layers: Set<ResourceLocation>) {
        if (layers.isEmpty()) hidden.remove(player) else hidden[player] = layers
    }

    fun clear(player: UUID) {
        hidden.remove(player)
    }
}
