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
