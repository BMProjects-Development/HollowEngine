import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.addons.physics.collider.CapsuleColliderShape
import ru.hollowhorizon.hollowengine.addons.physics.collider.HullColliderShape
import ru.hollowhorizon.hollowengine.addons.physics.collider.JoltColliderFactory
import ru.hollowhorizon.hollowengine.common.colliders.ColliderBox
import ru.hollowhorizon.hollowengine.common.colliders.ColliderVolume
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A hull through the corners of a box is that box, so a Jolt-backed collider shaped like it has to answer every
 * question the way the exact box does: which way it pushes, how far a box gets, where a ray lands.
 */
class JoltVolumeTests {
    private val frame = ColliderBox(
        Vec3(1000.5, 64.0, -2000.25),
        Vec3(0.5, 0.0, 0.0),
        Vec3(0.0, 0.25, 0.0),
        Vec3(0.0, 0.0, 0.75),
    )

    private fun volume(shape: HullColliderShape = CUBE): ColliderVolume {
        assertTrue(JoltNatives.ensureLoaded().isSuccess, "Jolt did not load")
        return assertNotNull(JoltColliderFactory.place(shape, frame))
    }

    @Test
    fun `a box overlapping from the side is pushed out the way the exact box pushes it`() {
        val box = AABB(1000.9, 63.9, -2000.4, 1001.4, 64.1, -2000.1)
        val expected = assertNotNull(frame.penetration(box))
        val actual = assertNotNull(volume().penetration(box))
        assertClose(expected, actual)
        assertTrue(actual.x > 0.0, "Pushed away from the collider, toward +X")
    }

    @Test
    fun `a box beside it is not inside it`() {
        assertNull(volume().penetration(AABB(1001.1, 63.9, -2000.4, 1001.6, 64.1, -2000.1)))
    }

    @Test
    fun `a box walking into it stops where the exact box stops it`() {
        val box = AABB(1001.5, 63.9, -2000.4, 1002.0, 64.1, -2000.1)
        val expected = frame.sweep(box, Direction.Axis.X, -1.0)
        val actual = volume().sweep(box, Direction.Axis.X, -1.0)
        assertEquals(expected, actual, 1.0e-3)
        assertEquals(-0.5, actual, 1.0e-3)
    }

    @Test
    fun `a box walking past it is not stopped`() {
        val box = AABB(1001.5, 65.0, -2000.4, 1002.0, 65.5, -2000.1)
        assertEquals(-1.0, volume().sweep(box, Direction.Axis.X, -1.0), 1.0e-9)
    }

    @Test
    fun `a ray lands on the face it hits`() {
        val start = Vec3(1003.0, 64.1, -2000.0)
        val end = Vec3(998.0, 64.1, -2000.0)
        val expected = assertNotNull(frame.clip(start, end))
        val actual = assertNotNull(volume().clip(start, end))
        assertClose(expected, actual, 2.0e-3)
    }

    @Test
    fun `a ray going by misses`() {
        assertNull(volume().clip(Vec3(1003.0, 66.0, -2000.0), Vec3(998.0, 66.0, -2000.0)))
    }

    @Test
    fun `a point outside is as far from it as from the exact box`() {
        val point = Vec3(1001.3, 64.0, -2000.25)
        assertEquals(frame.distanceTo(point), volume().distanceTo(point), 2.0e-3)
        assertEquals(0.0, volume().distanceTo(frame.center), 1.0e-9)
    }

    @Test
    fun `a box sunk into it from above comes out on top`() {
        val box = AABB(1000.3, 64.1, -2000.4, 1000.7, 65.0, -2000.1)
        val expected = assertNotNull(frame.lift(box))
        val actual = assertNotNull(volume().lift(box))
        assertEquals(expected, actual, 2.0e-3)
    }

    @Test
    fun `a capsule standing in a box is round at its ends`() {
        assertTrue(JoltNatives.ensureLoaded().isSuccess, "Jolt did not load")
        val tall = ColliderBox(Vec3(0.0, 0.0, 0.0), Vec3(0.25, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 0.25))
        val capsule = assertNotNull(JoltColliderFactory.place(CapsuleColliderShape(), tall))
        // A corner of the box lies outside the capsule, the top of the box does not.
        assertTrue(capsule.distanceTo(Vec3(0.24, 0.99, 0.24)) > 0.05)
        assertEquals(0.0, capsule.distanceTo(Vec3(0.0, 0.99, 0.0)), 1.0e-3)
    }

    private fun assertClose(expected: Vec3, actual: Vec3, tolerance: Double = 1.0e-3) {
        assertTrue(expected.distanceTo(actual) < tolerance, "Expected $expected, got $actual")
    }

    private companion object {
        val CUBE = HullColliderShape(
            (0 until 8).map { index ->
                Vec3f(
                    if (index and 1 == 0) -1f else 1f,
                    if (index and 2 == 0) -1f else 1f,
                    if (index and 4 == 0) -1f else 1f,
                )
            }
        )
    }
}
