import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.minecraft.util.Mth
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.npcs.navigation.PassingSteer
import kotlin.math.min
import kotlin.test.assertTrue

class NpcPassingTests {
    /** A walker heading for [goalX] along X, at most [speed] blocks a tick, as the path steering moves it. */
    private class Walker(val id: Int, var x: Double, var z: Double, val goalX: Double, val speed: Double, val makesWay: Boolean) {
        var motionX = 0.0
        var motionZ = 0.0
        var sides: Int2IntMap = Int2IntOpenHashMap()
        val taken = mutableSetOf<Int>()

        fun step(others: List<Walker>) {
            val dirX = Math.signum(goalX - x)
            if (dirX == 0.0 || speed == 0.0) return
            val passers = others.map {
                PassingSteer.Passer(it.id, it.x - x, it.z - z, it.motionX, it.motionZ, WIDTH, if (it.makesWay) 0.5 else 1.0)
            }
            val met = Int2IntOpenHashMap()
            val shift = PassingSteer.meet(dirX, 0.0, speed, WIDTH, passers, sides, met).shift
            sides = met
            taken += met.values
            // It heads for a point a block ahead, moved aside, as the steering does.
            val sideways = (shift * PassingSteer.SHIFT_GAIN).coerceIn(-PassingSteer.MAX_SHIFT, PassingSteer.MAX_SHIFT)
            val towardX = dirX * HEADING
            val towardZ = dirX * sideways
            val length = Mth.length(towardX, towardZ)
            val move = min(speed, Mth.length(goalX - x, 0.0))
            motionX = towardX / length * move
            motionZ = towardZ / length * move
        }

        fun apply() {
            x += motionX
            z += motionZ
        }
    }

    private fun closestPass(a: Walker, b: Walker): Double {
        var closest = Double.MAX_VALUE
        repeat(200) {
            a.step(listOf(b))
            b.step(listOf(a))
            a.apply()
            b.apply()
            closest = minOf(closest, Mth.length(a.x - b.x, a.z - b.z))
        }
        return closest
    }

    @Test
    fun `two npcs meeting head on both keep right and pass each other with a gap between them`() {
        val a = Walker(1, -6.0, 0.0, 6.0, SPEED, makesWay = true)
        val b = Walker(2, 6.0, 0.0, -6.0, SPEED, makesWay = true)
        val closest = closestPass(a, b)
        assertTrue(closest >= WIDTH + GAP, "they came within $closest of each other")
        assertTrue(a.x > 5.0 && b.x < -5.0, "they did not get past each other: ${a.x}, ${b.x}")
        // Keeping right, the one walking east steps aside to the south, and the other to the north.
        assertTrue(a.taken == setOf(1) && b.taken == setOf(1), "they took sides ${a.taken} and ${b.taken}")
    }

    @Test
    fun `an npc walks around one standing in its way, nearly on its line`() {
        val walker = Walker(1, -6.0, 0.0, 6.0, SPEED, makesWay = true)
        val standing = Walker(2, 0.0, 0.2, 0.0, 0.0, makesWay = false)
        val closest = closestPass(walker, standing)
        assertTrue(closest >= WIDTH + GAP, "it came within $closest of the one standing")
        assertTrue(walker.x > 5.0, "it did not get past: ${walker.x}")
    }

    private companion object {
        const val WIDTH = 0.6

        /** The least room left between their sides as they pass. */
        const val GAP = 0.25
        const val SPEED = 0.11
        const val HEADING = 1.0
    }
}
