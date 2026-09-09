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
import ru.hollowhorizon.hollowengine.common.models.BoneMask
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

    private fun RuntimeNode.rig(shape: RigidBodyShape, parent: String? = null) {
        attachments += RigidBodyAttachment(RigidBodyAttachmentSpec(shape = shape), this)
        parent?.let { attachments += JointAttachment(JointAttachmentSpec(parent = it), this) }
    }

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

        val target = PoseTarget(listOf(RuntimeNode(body, parent = null)).byIndex(), emptyMap())
        requireNotNull(target.node("body")).rig(RigidBodyShape.Box(RigVector(0.25f, 0.375f, 0.125f)))
        requireNotNull(target.node("head")).rig(
            RigidBodyShape.Box(
                RigVector(0.26f, 0.26f, 0.26f), RigVector(y = 0.25f)
            ), "body"
        )
        eyes.forEach { eye ->
            requireNotNull(target.node(eye.name!!)).rig(RigidBodyShape.Box(RigVector(0.03f, 0.02f, 0.01f)), "head")
        }
        return target
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

        val target = PoseTarget(listOf(RuntimeNode(thigh, parent = null)).byIndex(), emptyMap())
        requireNotNull(target.node("thigh")).rig(RigidBodyShape.Capsule(radius = 0.06f, length = 0.4f))
        requireNotNull(target.node("shin")).let { node ->
            node.attachments += RigidBodyAttachment(
                RigidBodyAttachmentSpec(shape = RigidBodyShape.Capsule(radius = 0.06f, length = 0.4f)),
                node,
            )
            node.attachments += JointAttachment(
                JointAttachmentSpec(
                    parent = "thigh",
                    limits = JointLimits.hinge(min = -120f, max = 0f),
                ),
                node,
            )
        }

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
