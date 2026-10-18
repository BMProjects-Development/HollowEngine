import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.npcs.navigation.FlightLimits
import ru.hollowhorizon.hollowengine.common.npcs.navigation.FlightSteering
import ru.hollowhorizon.hollowengine.common.npcs.navigation.FlightSteps
import ru.hollowhorizon.hollowengine.common.npcs.navigation.FlightWobble
import ru.hollowhorizon.hollowengine.common.npcs.navigation.PathAhead
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NpcFlightTests {
    private fun glider(climb: Double = 35.0) = FlightLimits(
        fullSpeed = 0.5,
        acceleration = 0.5 / 20.0,
        turn = Math.toRadians(90.0) / 20.0,
        climbSine = sin(Math.toRadians(climb)),
        minSpeed = 0.5 * 0.45,
    )

    private fun hoverer(acceleration: Double) = FlightLimits(
        fullSpeed = 0.4,
        acceleration = acceleration,
        turn = Math.toRadians(720.0) / 20.0,
        climbSine = 1.0,
        minSpeed = 0.0,
    )

    @Test
    fun `a glider turns no faster than its turn rate and climbs no steeper than its angle`() {
        val limits = glider()
        var position = Vec3.ZERO
        var velocity = Vec3(0.0, 0.0, 0.4)
        val target = Vec3(0.0, 30.0, -20.0)
        repeat(200) {
            val next = FlightSteering.glide(velocity, target.subtract(position), limits.fullSpeed, Vec3(0.0, 0.0, 1.0), limits)
            val turned = acos((velocity.normalize().dot(next.normalize())).coerceIn(-1.0, 1.0))
            assertTrue(turned <= limits.turn * 1.01, "turned ${Math.toDegrees(turned)} degrees in a tick")
            val pitch = asin(next.normalize().y)
            assertTrue(abs(pitch) <= asin(limits.climbSine) + 1.0e-9, "climbs at ${Math.toDegrees(pitch)} degrees")
            assertTrue(next.length() >= min(velocity.length(), limits.minSpeed) - 1.0e-9, "slows below its least speed")
            velocity = next
            position = position.add(velocity)
        }
    }

    @Test
    fun `a glider climbs to a point straight over it in circles around it`() {
        val limits = glider(climb = 30.0)
        var position = Vec3.ZERO
        var velocity = Vec3(0.0, 0.0, limits.minSpeed)
        val target = Vec3(0.0, 20.0, 0.0)
        var widest = 0.0
        repeat(400) {
            velocity = FlightSteering.glide(velocity, target.subtract(position), limits.minSpeed, Vec3(0.0, 0.0, 1.0), limits)
            position = position.add(velocity)
            widest = max(widest, Mth.length(position.x, position.z))
        }
        assertTrue(position.y > 15.0, "climbed only to ${position.y}")
        val turnRadius = limits.minSpeed / limits.turn
        assertTrue(widest < turnRadius * 3.0, "strayed $widest blocks from the column, turning radius $turnRadius")
    }

    @Test
    fun `a hovering flier braking by plan stops at its point without overshooting`() {
        val limits = hoverer(0.04)
        var position = Vec3.ZERO
        var velocity = Vec3.ZERO
        val target = Vec3(12.0, 3.0, -5.0)
        val axis = target.normalize()
        var farthest = 0.0
        repeat(200) {
            val toward = target.subtract(position)
            val speed = min(limits.fullSpeed, FlightSteering.brakingSpeed(toward.length(), 0.0, limits.acceleration))
            velocity = FlightSteering.hover(velocity, toward, speed, limits)
            position = position.add(velocity)
            farthest = max(farthest, position.dot(axis))
        }
        assertTrue(position.distanceTo(target) < 1.0e-3, "stopped ${position.distanceTo(target)} blocks away")
        assertTrue(farthest - target.length() < 0.02, "overshot by ${farthest - target.length()}")
    }

    @Test
    fun `a hovering flier climbs no faster than its climb angle lets`() {
        val limits = FlightLimits(0.4, 1.0, 1.0, sin(Math.toRadians(30.0)), 0.0)
        val velocity = FlightSteering.hover(Vec3.ZERO, Vec3(0.0, 10.0, 0.0), 0.4, limits)
        assertEquals(0.2, velocity.y, 1.0e-9)
    }

    @Test
    fun `taking off and landing in one cell keep the ground node`() {
        data class Step(val cell: Int, val air: Boolean)

        val path = listOf(Step(1, false), Step(1, true), Step(2, true), Step(3, true), Step(3, false), Step(4, false))
        val collapsed = FlightSteps.collapse(path, isAir = Step::air, sameCell = { a, b -> a.cell == b.cell })
        assertEquals(listOf(Step(1, false), Step(2, true), Step(3, false), Step(4, false)), collapsed)
    }

    @Test
    fun `wobble is bounded and moves smoothly`() {
        var previous = FlightWobble.offset(7, 0.0)
        var time = 0.0
        while (time < 20.0) {
            time += 0.01
            val offset = FlightWobble.offset(7, time)
            assertTrue(abs(offset.x) <= 1.0 && abs(offset.y) <= 1.0 && abs(offset.z) <= 1.0, "strays to $offset")
            assertTrue(offset.distanceTo(previous) < 0.05, "jumps from $previous to $offset at $time")
            previous = offset
        }
        assertEquals(FlightWobble.offset(7, 3.3), FlightWobble.offset(7, 3.3))
    }

    @Test
    fun `a way through the air counts its climbs in length and turns`() {
        val ahead = PathAhead(
            listOf(Vec3(0.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0), Vec3(4.0, 3.0, 0.0)),
            stopShort = null,
            inSpace = true,
        )
        assertEquals(7.0, ahead.length, 1.0e-9)
        assertEquals(90.0, ahead.turns.single().degrees, 1.0e-6)
    }
}
