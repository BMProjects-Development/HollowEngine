import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.addons.physics.collider.HullColliderShape
import ru.hollowhorizon.hollowengine.addons.physics.collider.HullFitter
import ru.hollowhorizon.hollowengine.client.models.internal.rig.BoneBounds
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A collider fitted to geometry is a hull only where a box would leave much of itself empty. */
class HullFitterTests {
    private fun cube(min: Vec3f, max: Vec3f): List<Vec3f> = (0 until 8).map { index ->
        Vec3f(
            if (index and 1 == 0) min.x else max.x,
            if (index and 2 == 0) min.y else max.y,
            if (index and 4 == 0) min.z else max.z,
        )
    }

    private fun fit(corners: List<Vec3f>) = HullFitter.fit(corners, BoneBounds.around(corners))

    @Test
    fun `a single cube stays a box`() {
        assertTrue(JoltNatives.ensureLoaded().isSuccess, "Jolt did not load")
        assertNull(fit(cube(Vec3f(-0.25f, 0f, -0.125f), Vec3f(0.25f, 0.75f, 0.125f))))
    }

    @Test
    fun `an L of two cubes is a hull`() {
        assertTrue(JoltNatives.ensureLoaded().isSuccess, "Jolt did not load")
        val upright = cube(Vec3f(0f, 0f, 0f), Vec3f(0.1f, 1f, 0.1f))
        val foot = cube(Vec3f(0f, 0f, 0f), Vec3f(1f, 0.1f, 0.1f))
        val shape = assertIs<HullColliderShape>(fit(upright + foot))
        assertTrue(shape.points.all { point -> listOf(point.x, point.y, point.z).all { it in -1.001f..1.001f } }, "Points are in the box's own measure")
    }
}
