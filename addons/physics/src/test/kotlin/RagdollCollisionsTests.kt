import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollBone
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollCollisions
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollShape
import ru.hollowhorizon.hollowengine.addons.physics.rig.BodyCollision
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointLimits
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which bodies of ragdoll are left to collide with each other.
 */
class RagdollCollisionsTests {
    private fun bone(
        name: String,
        at: Vec3f,
        half: Vec3f,
        parent: Int = -1,
        collision: BodyCollision = BodyCollision(),
        rotation: QuatF = QuatF.IDENTITY,
    ) = RagdollBone(
        nodeIndex = name.hashCode(),
        name = name,
        parent = parent,
        modelParent = null,
        bindPosition = at,
        bindRotation = rotation,
        shape = RagdollShape.Box(half, Vec3f.ZERO, QuatF.IDENTITY),
        pivot = Vec3f.ZERO,
        density = 1050f,
        collision = collision,
        limits = JointLimits(),
    )

    @Test
    fun `bodies that share space in the bind pose are left to their joints`() {
        val bones = listOf(
            bone("head", Vec3f(0f, 1.5f, 0f), Vec3f(0.25f, 0.25f, 0.25f)),
            bone("lid", Vec3f(0f, 1.55f, 0.2f), Vec3f(0.06f, 0.02f, 0.06f), parent = 0),
            bone("brow", Vec3f(0f, 1.56f, 0.2f), Vec3f(0.06f, 0.02f, 0.06f), parent = 0),
        )

        val pairs = RagdollCollisions.excludedPairs(bones)

        assertTrue(1 to 2 in pairs, "Two face bones sharing space are left to their joints")
        assertTrue(0 to 1 in pairs, "A body never collides with the one it hangs from")
    }

    @Test
    fun `bodies that only stand next to each other keep their collisions`() {
        val bones = listOf(
            bone("chest", Vec3f(0f, 1f, 0f), Vec3f(0.25f, 0.375f, 0.125f)),
            bone("arm", Vec3f(0.375f, 1f, 0f), Vec3f(0.125f, 0.375f, 0.125f), parent = 0), // A shin well clear of both.
            bone("shin", Vec3f(0.1f, 0.2f, 0f), Vec3f(0.1f, 0.2f, 0.1f)),
        )

        assertEquals(listOf(0 to 1), RagdollCollisions.excludedPairs(bones))
    }

    @Test
    fun `a body says for itself who it collides with`() {
        fun bones(collision: BodyCollision) = listOf(
            bone("chest", Vec3f(0f, 1f, 0f), Vec3f(0.25f, 0.375f, 0.125f)),
            bone("shin", Vec3f(0.1f, 0.2f, 0f), Vec3f(0.1f, 0.2f, 0.1f), collision = collision),
        )

        assertEquals(emptyList(), RagdollCollisions.excludedPairs(bones(BodyCollision())))
        assertEquals(
            listOf(0 to 1),
            RagdollCollisions.excludedPairs(bones(BodyCollision(ignores = setOf("chest")))),
            "A bone named in ignores is never hit",
        )
        assertEquals(
            listOf(0 to 1),
            RagdollCollisions.excludedPairs(bones(BodyCollision(withRig = false))),
            "A body that is out of the rig hits none of it",
        )
    }

    @Test
    fun `a body can ask to be pushed out of what it overlaps`() {
        fun bones(collision: BodyCollision) = listOf(
            bone("head", Vec3f(0f, 1.5f, 0f), Vec3f(0.25f, 0.25f, 0.25f)),
            bone("lid", Vec3f(0f, 1.55f, 0.2f), Vec3f(0.06f, 0.02f, 0.06f), parent = 0),
            bone("brow", Vec3f(0f, 1.56f, 0.2f), Vec3f(0.06f, 0.02f, 0.06f), parent = 0, collision = collision),
        )

        assertTrue(1 to 2 in RagdollCollisions.excludedPairs(bones(BodyCollision())))
        assertFalse(1 to 2 in RagdollCollisions.excludedPairs(bones(BodyCollision(pushesOut = true))))
    }

    @Test
    fun `only the bodies that give way are remembered`() {
        val bones = listOf(
            bone("chest", Vec3f(0f, 1f, 0f), Vec3f(0.25f, 0.375f, 0.125f)),
            bone("arm", Vec3f(1f, 1f, 0f), Vec3f(0.125f, 0.375f, 0.125f), collision = BodyCollision(push = 0.2f)),
        )

        assertEquals(mapOf(1 to 0.2f), RagdollCollisions.softBodies(bones))
    }

    @Test
    fun `boxes are apart until they really share space`() {
        val chest = RagdollCollisions.boxOf(bone("chest", Vec3f(0f, 1f, 0f), Vec3f(0.25f, 0.375f, 0.125f)))
        val near = RagdollCollisions.boxOf(bone("shin", Vec3f(0f, 0.6f, 0f), Vec3f(0.1f, 0.02f, 0.1f)))
        val inside = RagdollCollisions.boxOf(bone("shin", Vec3f(0f, 0.7f, 0f), Vec3f(0.1f, 0.02f, 0.1f)))

        assertFalse(RagdollCollisions.overlap(chest, near), "A body just below the chest is not inside it")
        assertTrue(RagdollCollisions.overlap(chest, inside))
    }

    @Test
    fun `a turned box is measured as it is turned`() {
        val post = RagdollCollisions.boxOf(bone("post", Vec3f.ZERO, Vec3f(0.1f, 0.5f, 0.1f)))
        val above = Vec3f(0f, 0.9f, 0f)
        val half = Vec3f(0.1f, 0.5f, 0.1f)

        assertTrue(RagdollCollisions.overlap(post, RagdollCollisions.boxOf(bone("beam", above, half))))
        assertFalse(
            RagdollCollisions.overlap(
                post,
                RagdollCollisions.boxOf(bone("beam", above, half, rotation = QuatF(90f.deg, Vec3f.Z_AXIS))),
            )
        )
    }
}
