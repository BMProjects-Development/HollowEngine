package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.client.handlers.TickHandler

/**
 * The ragdolls, that server simulates, as this client hears of them.
 */
internal object RagdollReplicas {
    /** How far behind the newest snapshot a ragdoll is shown, so there is nearly always one past the moment drawn. */
    const val DELAY_TICKS = 2.0

    /** How quickly the estimate of the server's clock follows what the packets say; small, so late packets do not jerk it. */
    private const val CLOCK_SMOOTHING = 0.1

    /** How many snapshots of a ragdoll are kept: enough for the delay and a late packet or two. */
    private const val KEPT = 8

    private class Replica(var offset: Double) {
        val ticks = ArrayList<Long>(KEPT)
        val snapshots = ArrayList<RagdollSnapshot>(KEPT)
    }

    private val replicas = HashMap<Int, Replica>()

    /** This client's clock: ticks it has run, and how far into the next one this frame is. */
    fun clientNow(): Double = TickHandler.clientFrame + TickHandler.partialTick.toDouble()

    /** Takes the snapshot of [entityId]'s ragdoll the server took on [tick], arriving at [now] on the client's clock. */
    fun receive(entityId: Int, tick: Long, snapshot: RagdollSnapshot, now: Double) {
        val replica = replicas.getOrPut(entityId) { Replica(now - tick) }
        replica.offset += (now - tick - replica.offset) * CLOCK_SMOOTHING

        if (replica.ticks.isNotEmpty() && tick <= replica.ticks.last()) return
        replica.ticks += tick
        replica.snapshots += snapshot
        while (replica.ticks.size > KEPT) {
            replica.ticks.removeAt(0)
            replica.snapshots.removeAt(0)
        }
    }

    /**
     * The bodies of [entityId] as they are to be drawn at [now] on the client's clock, or null when the server has
     * sent none yet. Before the first snapshot kept it holds that one, past the last it holds the last.
     */
    fun poseAt(entityId: Int, now: Double): RagdollSnapshot? {
        val replica = replicas[entityId] ?: return null
        val ticks = replica.ticks
        val at = now - replica.offset - DELAY_TICKS
        if (at <= ticks.first()) return replica.snapshots.first()
        if (at >= ticks.last()) return replica.snapshots.last()

        val after = ticks.indexOfFirst { it > at }
        val from = ticks[after - 1]
        val to = ticks[after]
        return replica.snapshots[after - 1].lerp(replica.snapshots[after], ((at - from) / (to - from)).toFloat())
    }

    /** Forgets [entityId]'s ragdoll, which it has left; the next one starts from what the server sends then. */
    fun forget(entityId: Int) {
        replicas.remove(entityId)
    }

    fun clear() = replicas.clear()

    /** Forgets the ragdolls of entities this client no longer has. */
    fun retain(alive: (Int) -> Boolean) {
        replicas.keys.retainAll(alive)
    }
}
