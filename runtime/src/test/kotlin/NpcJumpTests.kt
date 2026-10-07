import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.npcs.navigation.JumpBody
import ru.hollowhorizon.hollowengine.common.npcs.navigation.JumpDecision
import ru.hollowhorizon.hollowengine.common.npcs.navigation.JumpOutcome
import ru.hollowhorizon.hollowengine.common.npcs.navigation.JumpSimulation
import ru.hollowhorizon.hollowengine.common.npcs.navigation.JumpSpace
import ru.hollowhorizon.hollowengine.common.npcs.navigation.PathStraightening
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpcJumpTests {
    /** Full blocks at the given cells, as a jump sees them. */
    private class Blocks(cells: List<Triple<Int, Int, Int>>) : JumpSpace {
        private val boxes = cells.map { (x, y, z) -> AABB(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0) }
        override fun collides(box: AABB): Boolean = boxes.any { it.intersects(box) }
    }

    /** Ground at y = 63 along X, broken by a gap of [gap] blocks after x = 0; the far side is raised by [rise]. */
    private fun gap(gap: Int, rise: Int = 0, extra: List<Triple<Int, Int, Int>> = emptyList()): Blocks {
        val near = (-6..0).flatMap { x -> (-2..2).map { z -> Triple(x, 63, z) } }
        val far = (gap + 1..gap + 8).flatMap { x -> (-2..2).map { z -> Triple(x, 63 + rise, z) } }
        return Blocks(near + far + extra)
    }

    private fun body(speed: Double) = JumpBody(
        halfWidth = 0.3,
        height = 1.8,
        speed = speed,
        jumpPower = 0.42,
        gravity = 0.08,
        friction = 0.6,
    )

    private val start = Vec3(0.5, 64.0, 0.5)

    @Test
    fun `a run-up clears a gap that walking speed does not`() {
        val landing = Vec3(4.5, 64.0, 0.5)
        assertNull(JumpSimulation.plan(gap(3), body(NPC_SPEED), start, landing))

        val jump = assertNotNull(JumpSimulation.plan(gap(3), body(NPC_SPEED * 2.0), start, landing))
        assertTrue(jump.landing.position.x in 4.0 - 0.3..5.0, "lands on the far side, at ${jump.landing.position}")
        assertEquals(64.0, jump.landing.position.y)
    }

    @Test
    fun `a low ceiling over the gap cuts the jump short`() {
        val landing = Vec3(3.5, 64.0, 0.5)
        assertNotNull(JumpSimulation.plan(gap(2), body(NPC_SPEED * 1.5), start, landing))

        val ceiling = (1..2).flatMap { x -> (-2..2).map { z -> Triple(x, 66, z) } }
        assertNull(JumpSimulation.plan(gap(2, extra = ceiling), body(NPC_SPEED * 1.5), start, landing))
    }

    @Test
    fun `a jump lands on ground a block higher`() {
        val jump = assertNotNull(JumpSimulation.plan(gap(1, rise = 1), body(NPC_SPEED * 1.5), start, Vec3(2.5, 65.0, 0.5)))
        assertEquals(65.0, jump.landing.position.y)
    }

    @Test
    fun `a jump that comes down short of the landing block fails`() {
        assertNull(JumpSimulation.plan(gap(4), body(NPC_SPEED * 1.5), start, Vec3(5.5, 64.0, 0.5)))
    }

    @Test
    fun `the fall of a jump down is counted from its top`() {
        val jump = assertNotNull(JumpSimulation.plan(gap(1, rise = -2), body(NPC_SPEED * 1.5), start, Vec3(2.5, 62.0, 0.5)))
        assertTrue(jump.landing.fall > 2.0, "fell ${jump.landing.fall}, more than the 2 blocks between the grounds")
    }

    /** Ground up to x = 0, then a gap of two blocks, a pillar one block wide at x = 3, and nothing past it. */
    private val pillar = Blocks(
        (-6..0).flatMap { x -> (-2..2).map { z -> Triple(x, 63, z) } } + Triple(3, 63, 0),
    )

    private val onPillar = Vec3(3.5, 64.0, 0.5)

    @Test
    fun `a run-up onto a pillar comes to rest on it`() {
        val jump = assertNotNull(JumpSimulation.plan(pillar, body(NPC_SPEED * 1.5), start, onPillar))
        assertTrue(jump.landing.position.x in 3.0 - 0.3..4.0 + 0.3, "stops on the pillar, at ${jump.landing.position}")
    }

    @Test
    fun `taking off too slow comes down early and too fast slides off the pillar`() {
        val body = body(NPC_SPEED * 1.5)
        val edge = Vec3(1.2, 64.0, 0.5)
        assertEquals(JumpOutcome.Early, JumpSimulation.takeOff(pillar, body, start, 0.0, 0.0, 1.0, 0.0, onPillar))
        assertEquals(JumpOutcome.Late, JumpSimulation.takeOff(pillar, body, edge, 0.5, 0.0, 1.0, 0.0, onPillar))
    }

    /**
     * Runs up tick by tick the way the NPC does, deciding each tick from where it is and how fast it goes.
     * Returns how the jump ended, or null when it braked; then the speed it would stop from must leave it on
     * the ground.
     */
    private fun runUp(space: JumpSpace, body: JumpBody, from: Double, speed: Double, landing: Vec3): JumpOutcome? {
        val acceleration = JumpSimulation.groundAcceleration(body.speed, body.friction)
        val drag = JumpSimulation.groundDrag(body.friction)
        var x = from
        var vx = speed
        repeat(60) {
            when (JumpSimulation.decide(space, body, Vec3(x, 64.0, 0.5), vx, 0.0, 1.0, 0.0, landing)) {
                JumpDecision.TAKE_OFF -> return JumpSimulation.takeOff(space, body, Vec3(x, 64.0, 0.5), vx + acceleration, 0.0, 1.0, 0.0, landing)
                JumpDecision.RUN -> {
                    vx += acceleration
                    x += vx
                    vx *= drag
                }
                JumpDecision.BRAKE -> {
                    val stop = x + JumpSimulation.slideDistance(vx, body.friction)
                    assertTrue(stop < 1.0 + body.halfWidth, "braked at $x moving $vx and slides off the edge to $stop")
                    return null
                }
            }
        }
        error("never decided")
    }

    @Test
    fun `from rest in the middle the run-up lands on the pillar`() {
        val outcome = runUp(pillar, body(NPC_SPEED * 1.5), 0.5, 0.0, onPillar)
        assertTrue(outcome is JumpOutcome.Landed, "jumped from rest and got $outcome")
    }

    @Test
    fun `however it comes at the edge it lands on the pillar or stops in time`() {
        val body = body(NPC_SPEED * 1.5)
        for (from in listOf(-0.5, 0.2, 0.5, 0.8)) for (speed in listOf(0.0, 0.1, 0.2)) {
            val outcome = runUp(pillar, body, from, speed, onPillar) ?: continue
            assertTrue(outcome is JumpOutcome.Landed, "from $from at $speed it jumped and got $outcome")
        }
    }

    @Test
    fun `straightening keeps both ends of a jump`() {
        val nodes = (0..6).toList()
        val jumps = BooleanArray(nodes.size) { it == 4 }
        val kept = PathStraightening.keep(nodes, jumps, canSkip = { true }, canWalk = { _, _ -> true })
        assertEquals(listOf(0, 3, 4, 6), kept)
    }

    @Test
    fun `straightening stops at a node it may not skip`() {
        val nodes = (0..5).toList()
        val kept = PathStraightening.keep(nodes, BooleanArray(nodes.size), canSkip = { it != 2 }, canWalk = { _, _ -> true })
        assertEquals(listOf(0, 2, 5), kept)
    }

    @Test
    fun `straightening keeps every corner it cannot walk past`() {
        val nodes = (0..4).toList()
        val kept = PathStraightening.keep(nodes, BooleanArray(nodes.size), canSkip = { true }, canWalk = { a, b -> b - a <= 1 })
        assertEquals(nodes, kept)
    }

    private companion object {
        /** The movement speed of an engine NPC. */
        const val NPC_SPEED = 0.23
    }
}
