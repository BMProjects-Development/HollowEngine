import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollReplicas
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollSnapshot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A ragdoll the server moves one block a tick is drawn moving smoothly on the client, however unevenly its
 * snapshots arrive.
 */
class RagdollReplicasTests {
    private val entity = 4242

    @AfterTest
    fun forget() = RagdollReplicas.forget(entity)

    /** One body, [x] blocks along X, as the server took it on [tick]. */
    private fun snapshot(x: Float) = RagdollSnapshot(0.0, 0.0, 0.0, intArrayOf(1), floatArrayOf(x, 0f, 0f, 0f, 0f, 0f, 1f))

    @Test
    fun `snapshots arriving in pairs and gaps are drawn as steady motion`() {
        val arrivals = (0L..40L).map { tick -> tick to tick + 0.3 + if (tick % 3 == 1L) 0.6 else 0.0 }
        val frames = generateSequence(0.0) { it + 1.0 / 3.0 }.takeWhile { it < 41.0 }.toList()

        var next = 0
        val drawn = frames.mapNotNull { now ->
            while (next < arrivals.size && arrivals[next].second <= now) {
                val (tick, at) = arrivals[next++]
                RagdollReplicas.receive(entity, tick, snapshot(tick.toFloat()), at)
            }
            RagdollReplicas.poseAt(entity, now)?.values?.get(0)
        }

        val steps = drawn.zipWithNext { a, b -> b - a }.drop(12)
        assertTrue(steps.all { it >= -1e-4f }, "The body went back: $steps")
        assertTrue(steps.all { it <= 0.75f }, "The body jumped more than two frames' worth in one: $steps")
        assertTrue(steps.count { it < 1e-4f } <= 2, "The body stood still between frames, as if drawn at the tick rate: $steps")
    }
}
