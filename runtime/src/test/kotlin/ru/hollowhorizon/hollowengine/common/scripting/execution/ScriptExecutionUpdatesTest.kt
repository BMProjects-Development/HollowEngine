package ru.hollowhorizon.hollowengine.common.scripting.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptExecutionUpdatesTest {
    private data class Delivery(val player: String, val allowed: Boolean, val reset: Boolean,
                                val states: Map<String, ScriptExecutionStatus>)

    @Test
    fun `admission changes stopping and privilege revocation are pushed without polling`() {
        val updates = ScriptExecutionUpdates<String, ScriptExecutionStatus>(ScriptExecutionStatus.IDLE)
        val deliveries = mutableListOf<Delivery>()
        fun sync(states: Map<String, ScriptExecutionStatus>, players: Set<String>) =
            updates.synchronize(states, players) { player, allowed, reset, changes ->
                deliveries += Delivery(player, allowed, reset, changes)
            }
        val running = mapOf("node.kts" to ScriptExecutionStatus.RUNNING)
        sync(running, setOf("admin"))
        assertEquals(listOf(Delivery("admin", true, true, running)), deliveries)
        deliveries.clear()
        repeat(200) { sync(running, setOf("admin")) }
        assertTrue(deliveries.isEmpty())
        sync(running, setOf("admin", "new-admin"))
        assertEquals(listOf(Delivery("new-admin", true, true, running)), deliveries)
        deliveries.clear()
        sync(mapOf("node.kts" to ScriptExecutionStatus.STOPPING), setOf("admin", "new-admin"))
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { !it.reset && it.states["node.kts"] == ScriptExecutionStatus.STOPPING })
        deliveries.clear()
        sync(emptyMap(), setOf("admin"))
        assertEquals(setOf(Delivery("new-admin", false, true, emptyMap()),
            Delivery("admin", true, false, mapOf("node.kts" to ScriptExecutionStatus.IDLE))), deliveries.toSet())
        deliveries.clear()
        sync(emptyMap(), setOf("admin", "new-admin"))
        assertEquals(listOf(Delivery("new-admin", true, true, emptyMap())), deliveries)
    }
}
