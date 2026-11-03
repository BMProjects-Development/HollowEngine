package ru.hollowhorizon.hollowengine.client.editor

import androidx.compose.runtime.mutableStateMapOf
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import ru.hollowhorizon.hollowengine.common.npcs.RequestNpcModelPacket

data class WorldNpcModel(val alive: Boolean, val model: String?)

object WorldNpcModels {
    private const val STALE_MILLIS = 10_000L

    private val known = mutableStateMapOf<String, WorldNpcModel>()
    private val asked = HashMap<String, Long>()
    private var level: ClientLevel? = null

    fun of(id: String): WorldNpcModel? {
        val current = Minecraft.getInstance().level ?: return null
        if (current !== level) forget(current)
        val now = System.currentTimeMillis()
        if (now - (asked[id] ?: (Long.MIN_VALUE / 2)) > STALE_MILLIS) {
            asked[id] = now
            RequestNpcModelPacket(id).send()
        }
        return known[id]
    }

    /** What the server last said of [id], without asking again; safe to read from any thread. */
    fun known(id: String): WorldNpcModel? = known[id]

    internal fun answer(id: String, alive: Boolean, model: String?) {
        val answer = WorldNpcModel(alive, model)
        if (known[id] != answer) known[id] = answer
    }

    private fun forget(current: ClientLevel) {
        level = current
        asked.clear()
        known.clear()
    }
}
