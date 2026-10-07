import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.npcs.navigation.PathAhead
import ru.hollowhorizon.hollowengine.common.npcs.navigation.SpeedPlan
import ru.hollowhorizon.hollowengine.common.npcs.navigation.SpeedRamp
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NpcPaceTests {
    @Test
    fun `inverse smoothstep undoes smoothstep`() {
        for (step in 0..20) {
            val t = step / 20.0
            assertEquals(t, SpeedPlan.inverseSmoothstep(SpeedPlan.smoothstep(t)), 1.0e-9)
        }
    }

    @Test
    fun `the ramp reaches full speed in the acceleration time and not before`() {
        val ramp = SpeedRamp()
        val ticks = 6.0
        val shares = (1..6).map { ramp.next(1.0, 1.0, ticks) }
        assertTrue(shares.zipWithNext().all { (a, b) -> b > a }, "rises every tick: $shares")
        assertTrue(shares[4] < 1.0, "not there a tick early: $shares")
        assertEquals(1.0, shares[5], 1.0e-9)
        // Starts gently and ends gently, as smoothstep does.
        assertTrue(shares[0] < shares[2] - shares[1] && shares[5] - shares[4] < shares[3] - shares[2])
    }

    @Test
    fun `the ramp drops at once and picks up again from where it is`() {
        val ramp = SpeedRamp()
        repeat(10) { ramp.next(1.0, 1.0, 6.0) }
        assertEquals(0.4, ramp.next(0.4, 1.0, 6.0))
        val next = ramp.next(1.0, 1.0, 6.0)
        assertTrue(next > 0.4 && next < 1.0, "climbs back from 0.4, got $next")
    }

    @Test
    fun `braking at the computed share stops on the mark`() {
        val full = 0.11
        val brakeTicks = 10.0
        var distance = 3.0
        var speed = full
        var ticks = 0
        while (distance > 0.0 && ticks < 200) {
            speed = minOf(full, SpeedPlan.brakingShare(distance, 0.0, full, brakeTicks) * full)
            distance -= speed
            ticks++
            if (speed < 1.0e-4) break
        }
        assertTrue(abs(distance) < full, "stops within a tick of the mark, $distance left")
        assertTrue(SpeedPlan.brakingShare(full * brakeTicks / 2.0, 0.0, full, brakeTicks) in 0.99..1.01)
    }

    @Test
    fun `sharper turns are taken slower and gentle ones at full speed`() {
        assertEquals(1.0, SpeedPlan.cornerShare(10.0))
        assertTrue(SpeedPlan.cornerShare(45.0) > SpeedPlan.cornerShare(90.0))
        assertTrue(SpeedPlan.cornerShare(90.0) > SpeedPlan.cornerShare(170.0))
    }

    @Test
    fun `the straight boost fades out over the second half of the stretch`() {
        assertEquals(1.1, SpeedPlan.straightShare(20.0, 8.0, 1.1), 1.0e-9)
        assertEquals(1.0, SpeedPlan.straightShare(3.0, 8.0, 1.1), 1.0e-9)
        val middle = SpeedPlan.straightShare(6.0, 8.0, 1.1)
        assertTrue(middle > 1.0 && middle < 1.1)
    }

    @Test
    fun `walking sideways and backward is slower in proportion`() {
        assertEquals(1.0, SpeedPlan.directionShare(0.0, 0.8, 0.6))
        assertEquals(0.8, SpeedPlan.directionShare(90.0, 0.8, 0.6), 1.0e-9)
        assertEquals(0.6, SpeedPlan.directionShare(180.0, 0.8, 0.6), 1.0e-9)
        assertEquals(0.7, SpeedPlan.directionShare(135.0, 0.8, 0.6), 1.0e-9)
    }

    @Test
    fun `the line ahead finds its turns and the straight before them`() {
        val ahead = PathAhead(
            listOf(Vec3(0.0, 64.0, 0.0), Vec3(4.0, 64.0, 0.0), Vec3(8.0, 64.0, 0.0), Vec3(8.0, 64.0, 5.0)),
            stopShort = 0.0,
        )
        assertEquals(13.0, ahead.length, 1.0e-9)
        assertEquals(1, ahead.turns.size, "a straight node is no turn")
        assertEquals(8.0, ahead.turns.single().distance, 1.0e-9)
        assertEquals(90.0, ahead.turns.single().degrees, 1.0e-6)
        assertEquals(8.0, ahead.straightAhead(15.0), 1.0e-9)
        assertEquals(Vec3(8.0, 64.0, 2.0), ahead.pointAt(10.0))
        assertEquals(1, ahead.nodeBefore(6.0))
    }
}
