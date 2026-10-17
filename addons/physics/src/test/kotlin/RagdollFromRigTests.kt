import com.github.stephengold.joltjni.JobSystemThreadPool
import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.TempAllocatorImpl
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.*
import ru.hollowhorizon.hollowengine.addons.physics.rig.*
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorld
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.byIndex
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.utils.math.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rag-doll built from a rig as the generator writes one, on a model shaped like a player.
 */
class RagdollFromRigTests {
    private val spec = RagdollStateSpec()

    private fun offset(x: Float = 0f, y: Float = 0f) = TrsTransformF().apply { translate(Vec3f(x, y, 0f)) }

    /** A box collider of [size] around [center] on a bone, the body made of it, and a joint to [parent]. */
    private fun body(collider: String, size: Vec3f, center: Vec3f = Vec3f.ZERO, parent: String? = null, joint: JointAttachmentSpec? = null) =
        RigBone(
            attachments = listOfNotNull(
                ColliderAttachmentSpec(id = collider, offset = center, size = size),
                RigidBodyAttachmentSpec(),
                joint ?: parent?.let { JointAttachmentSpec(parent = it) },
            ),
        )

    private fun playerLikeModel(): PoseTarget {
        val eyes = (0 until 4).map { index ->
            NodeDefinition(
                index = 10 + index,
                name = "eye$index",
                children = mutableListOf(),
                transform = offset(x = -0.12f + 0.08f * index, y = 0.25f),
            )
        }
        val head =
            NodeDefinition(index = 2, name = "head", children = eyes.toMutableList(), transform = offset(y = 0.75f))
        val body =
            NodeDefinition(index = 1, name = "body", children = mutableListOf(head), transform = offset(y = 0.75f))
        eyes.forEach { it.parent = head }
        head.parent = body

        val rig = ModelRig(
            bones = mapOf(
                "body" to body("body", Vec3f(0.5f, 0.75f, 0.25f)),
                "head" to body("head", Vec3f(0.52f, 0.52f, 0.52f), center = Vec3f(0f, 0.25f, 0f), parent = "body"),
            ) + eyes.associate { eye -> eye.name!! to body(eye.name!!, Vec3f(0.06f, 0.04f, 0.02f), parent = "head") },
        )
        return PoseTarget(listOf(RuntimeNode(body, parent = null)).byIndex(), emptyMap(), rig = rig)
    }

    @Test
    fun `a rigged model is simulated as the rig describes it`() {
        val target = playerLikeModel()
        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))

        assertEquals(6, plan.bones.size, "Every bone the rig put a body on")
        assertEquals("body", plan.bones.first().name, "The body a joint names must come first")
        assertTrue(plan.bones.drop(1).all { it.parent >= 0 }, "Everything else hangs off something")
    }

    @Test
    fun `a rigged model stays in one piece while it falls`() {
        assertTrue(JoltNatives.ensureLoaded().isSuccess, "Jolt did not load")

        val target = playerLikeModel()
        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        val system = PhysicsWorld.createSystem()
        val instance = requireNotNull(RagdollInstance.create(system, RagdollTemplate.build(plan, spec), spec))

        val placement = ModelPlacement(Vec3f.ZERO, QuatF.IDENTITY)
        val animated = RagdollPose.globals(plan.order, AnimationPose(), HashMap())
        instance.start(placement, animated, Vec3f.ZERO)

        val allocator = TempAllocatorImpl(8 * 1024 * 1024)
        val jobs = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1)
        repeat(60) { system.update(1f / 60f, 1, allocator, jobs) }

        val start = HashMap<Int, Vec3f>()
        plan.bones.forEach { bone ->
            val position = MutableVec3f()
            requireNotNull(animated[bone.nodeIndex]).decompose(position, null, null)
            start[bone.nodeIndex] = Vec3f(position)
        }

        val restored = HashMap<Int, MutableMat4f>()
        instance.readInto(placement, restored)
        instance.close()

        plan.bones.forEach { bone ->
            val position = MutableVec3f()
            requireNotNull(restored[bone.nodeIndex]).decompose(position, null, null)
            val moved = MutableVec3f(position).subtract(requireNotNull(start[bone.nodeIndex])).length()

            assertTrue(moved < 8f, "Bone ${bone.name} moved $moved blocks in a second, to $position")
        }
    }

    @Test
    fun `a hinge bends one way and not the other`() {
        assertTrue(JoltNatives.ensureLoaded().isSuccess, "Jolt did not load")

        val shin = NodeDefinition(index = 2, name = "shin", children = mutableListOf(), transform = offset(y = -0.4f))
        val thigh =
            NodeDefinition(index = 1, name = "thigh", children = mutableListOf(shin), transform = offset(y = 1f))
        shin.parent = thigh

        val leg = Vec3f(0.12f, 0.4f, 0.12f)
        val rig = ModelRig(
            bones = mapOf(
                "thigh" to body("thigh", leg),
                "shin" to body("shin", leg, joint = JointAttachmentSpec(parent = "thigh", limits = JointLimits.hinge(min = -120f, max = 0f))),
            ),
        )
        val target = PoseTarget(listOf(RuntimeNode(thigh, parent = null)).byIndex(), emptyMap(), rig = rig)

        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        val system = PhysicsWorld.createSystem()
        val instance = requireNotNull(RagdollInstance.create(system, RagdollTemplate.build(plan, spec), spec))

        val placement = ModelPlacement(Vec3f.ZERO, QuatF.IDENTITY)
        val animated = RagdollPose.globals(plan.order, AnimationPose(), HashMap())
        instance.start(placement, animated, Vec3f.ZERO)

        instance.push(Vec3f(4f, 0f, 0f))
        val allocator = TempAllocatorImpl(8 * 1024 * 1024)
        val jobs = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1)
        repeat(30) { system.update(1f / 60f, 1, allocator, jobs) }

        val restored = HashMap<Int, MutableMat4f>()
        instance.readInto(placement, restored)
        instance.close()

        val knee = MutableVec3f()
        val hip = MutableVec3f()
        requireNotNull(restored[2]).decompose(knee, null, null)
        requireNotNull(restored[1]).decompose(hip, null, null)

        assertTrue(knee.y < hip.y, "The shin is at $knee, above the thigh at $hip")
        assertTrue(
            abs(knee.x - hip.x) < 0.2f,
            "The shin swung out to x = ${knee.x} while the thigh is at ${hip.x}; the hinge did not hold",
        )
    }
}
