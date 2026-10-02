import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAlignment
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderBox
import ru.hollowhorizon.hollowengine.common.colliders.EntityCollider
import ru.hollowhorizon.hollowengine.common.colliders.fitEntityBox
import ru.hollowhorizon.hollowengine.common.colliders.place
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColliderBoxTest {
    /** A unit cube turned 45 degrees about Y: its corner, not its face, faces the ray. */
    private val diamond = ColliderBox.of(MutableMat4f().rotate(45f.deg, Vec3f.Y_AXIS), Vec3.ZERO)

    @Test
    fun `ray meets a turned box at its corner`() {
        val hit = assertNotNull(diamond.clip(Vec3(-2.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0)))

        assertEquals(-sqrt(0.5), hit.x, 1.0e-5)
    }

    @Test
    fun `ray through the bounds of a turned box but past its edge misses it`() {
        val start = Vec3(0.5, -2.0, 0.5)
        val end = Vec3(0.5, 2.0, 0.5)

        assertTrue(diamond.bounds.clip(start, end).isPresent)
        assertNull(diamond.clip(start, end))
    }

    @Test
    fun `ray that starts inside hits where it starts`() {
        val start = Vec3(0.1, 0.1, 0.1)

        assertEquals(start, diamond.clip(start, Vec3(5.0, 0.0, 0.0)))
    }

    @Test
    fun `segment that stops short does not reach the box`() {
        assertNull(diamond.clip(Vec3(-3.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0)))
    }

    @Test
    fun `distance is measured to the nearest face`() {
        assertEquals(0.0, diamond.distanceTo(Vec3.ZERO), 1.0e-9)
        assertEquals(1.0, diamond.distanceTo(Vec3(0.0, 1.5, 0.0)), 1.0e-5)
    }

    @Test
    fun `overlapping entity is pushed out the shortest way`() {
        val wall = ColliderBox.aligned(Vec3.ZERO, Vec3(0.5, 2.0, 2.0))
        val entity = AABB(0.3, 0.0, -0.3, 0.9, 1.8, 0.3)

        val push = assertNotNull(wall.penetration(entity))

        assertEquals(0.2, push.x, 1.0e-9)
        assertEquals(0.0, push.z, 1.0e-9)
    }

    @Test
    fun `entity beside a turned box is not pushed even when their bounds overlap`() {
        val entity = AABB(0.45, -0.5, 0.45, 0.7, 0.5, 0.7)

        assertTrue(diamond.bounds.intersects(entity))
        assertNull(diamond.penetration(entity))
    }

    @Test
    fun `box flattened to nothing is never hit`() {
        val flat = ColliderBox.of(MutableMat4f().scale(Vec3f(1f, 0f, 1f)), Vec3.ZERO)

        assertNull(flat.clip(Vec3(0.0, -1.0, 0.0), Vec3(0.0, 1.0, 0.0)))
        assertFalse(flat.contains(Vec3.ZERO))
    }

    /** The box stops where its face meets the corner of the turned box, not where their bounds meet. */
    @Test
    fun `moving box stops against the corner of a turned box`() {
        val box = AABB(-2.1, -0.1, -0.1, -1.9, 0.1, 0.1)

        assertEquals(1.9 - sqrt(0.5), diamond.sweep(box, Direction.Axis.X, 3.0), 1.0e-5)
    }

    /** Off to the side the corner is out of reach, and the box meets the slanted face further on. */
    @Test
    fun `moving box stops against the slanted face of a turned box`() {
        val box = AABB(-2.1, -0.1, 0.3, -1.9, 0.1, 0.5)

        // Its corner nearest the diamond is at z = 0.3, where the slanted face is at x = -(√0.5 - 0.3).
        assertEquals(1.9 - (sqrt(0.5) - 0.3), diamond.sweep(box, Direction.Axis.X, 3.0), 1.0e-5)
    }

    @Test
    fun `box that already overlaps a collider is let out`() {
        val inside = AABB(-0.1, -0.1, -0.1, 0.1, 0.1, 0.1)

        assertEquals(-3.0, diamond.sweep(inside, Direction.Axis.X, -3.0))
    }

    @Test
    fun `box moving past or away from a collider keeps its whole move`() {
        val beside = AABB(-2.1, 2.0, -0.1, -1.9, 2.2, 0.1)
        val behind = AABB(-2.1, -0.1, -0.1, -1.9, 0.1, 0.1)

        assertEquals(3.0, diamond.sweep(beside, Direction.Axis.X, 3.0))
        assertEquals(-3.0, diamond.sweep(behind, Direction.Axis.X, -3.0))
    }

    /** What stands on a collider turns with it: a quarter turn takes a point on its X side to its Z side. */
    @Test
    fun `box sunk into a flat collider is lifted to its top`() {
        val floor = ColliderBox.aligned(Vec3.ZERO, Vec3(1.0, 0.5, 1.0))

        assertEquals(0.2, assertNotNull(floor.lift(AABB(-0.3, 0.3, -0.3, 0.3, 2.1, 0.3))), 1.0e-9)
        assertNull(floor.lift(AABB(-0.3, 0.6, -0.3, 0.3, 2.4, 0.3)))
    }

    @Test
    fun `box sunk into a slope is lifted to the slope where its edge is highest`() {
        val ridge = ColliderBox.of(MutableMat4f().rotate(45f.deg, Vec3f.Z_AXIS), Vec3.ZERO)
        val box = AABB(0.2, 0.3, -0.1, 0.4, 2.1, 0.1)

        assertEquals(sqrt(0.5) - 0.2 - 0.3, assertNotNull(ridge.lift(box)), 1.0e-5)
    }

    @Test
    fun `fast collider that crossed a box within the tick touched it`() {
        val before = ColliderBox.aligned(Vec3(-3.0, 0.0, 0.0), Vec3(0.25, 0.25, 0.25))
        val now = ColliderBox.aligned(Vec3(3.0, 0.0, 0.0), Vec3(0.25, 0.25, 0.25))
        val box = AABB(-0.3, -0.9, -0.3, 0.3, 0.9, 0.3)

        assertNull(before.penetration(box))
        assertNull(now.penetration(box))
        val touch = assertNotNull(now.firstTouch(before, box))
        assertTrue(touch.pose.center.x < 0.0, "the first touch is on the side the collider came from")
    }

    @Test
    fun `fast collider that went past a box does not touch it`() {
        val before = ColliderBox.aligned(Vec3(-3.0, 0.0, 2.0), Vec3(0.25, 0.25, 0.25))
        val now = ColliderBox.aligned(Vec3(3.0, 0.0, 2.0), Vec3(0.25, 0.25, 0.25))

        assertNull(now.firstTouch(before, AABB(-0.3, -0.9, -0.3, 0.3, 0.9, 0.3)))
    }

    @Test
    fun `box sunk in shallowly from below still has a way out sideways`() {
        val slab = ColliderBox.aligned(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.5, 1.0))
        val wall = AABB(0.8, 0.0, -0.5, 2.0, 0.6, 0.5)

        assertEquals(0.0, assertNotNull(slab.penetration(wall)).x, 1.0e-9)
        assertEquals(0.2, assertNotNull(slab.escape(wall, Vec3(1.0, 0.0, 0.0))), 1.0e-9)
    }

    @Test
    fun `turning collider carries a point with it`() {
        val before = ColliderBox.aligned(Vec3.ZERO, Vec3(0.5, 0.5, 0.5))
        val after = ColliderBox.of(MutableMat4f().rotate(90f.deg, Vec3f.Y_AXIS), Vec3.ZERO)

        val carried = assertNotNull(before.carry(Vec3(1.0, 0.0, 0.0), after))

        assertEquals(0.0, carried.x, 1.0e-5)
        assertEquals(1.0, abs(carried.z), 1.0e-5)
    }

    /** The width reaches the farthest corner from the vertical axis, so turning the entity never pokes out of it. */
    @Test
    fun `entity box around colliders holds however the entity turns`() {
        val tail = ColliderBox.aligned(Vec3(1.0, 0.5, 0.0), Vec3(0.5, 0.5, 0.5))
        val (width, height) = assertNotNull(fitEntityBox(listOf(collider(tail))))

        assertEquals(2.0 * sqrt(1.5 * 1.5 + 0.5 * 0.5), width.toDouble(), 1.0e-5)
        assertEquals(1.0, height.toDouble(), 1.0e-5)
    }

    @Test
    fun `colliders all below the feet give no entity box`() {
        val pit = ColliderBox.aligned(Vec3(0.0, -1.0, 0.0), Vec3(0.5, 0.5, 0.5))

        assertNull(fitEntityBox(listOf(collider(pit))))
    }

    private fun collider(box: ColliderBox) = EntityCollider("box", null, ColliderAttachmentSpec(), box)

    @Test
    fun `world-aligned collider keeps its size whatever its bone turns to`() {
        val spec = ColliderAttachmentSpec(alignment = ColliderAlignment.WORLD, size = Vec3f(1f, 2f, 1f))
        val bone: Mat4f = MutableMat4f().translate(0f, 1f, 0f).rotate(30f.deg, Vec3f.Z_AXIS)

        val box = spec.place(bone, MutableMat4f(), Vec3(10.0, 0.0, 0.0))

        assertEquals(AABB(9.5, 0.0, -0.5, 10.5, 2.0, 0.5), box.bounds)
    }
}
