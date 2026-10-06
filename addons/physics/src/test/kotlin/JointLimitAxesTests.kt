import com.github.stephengold.joltjni.JobSystemThreadPool
import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.TempAllocatorImpl
import com.github.stephengold.joltjni.Vec3
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EMotionType
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.*
import ru.hollowhorizon.hollowengine.addons.physics.rig.AxisLimit
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointLimits
import ru.hollowhorizon.hollowengine.addons.physics.rig.RigidBodyAttachmentSpec
import ru.hollowhorizon.hollowengine.addons.physics.rotated
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
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A joint's limit on an axis holds the turn around that very axis, whichever of the bone's axes the joint twists
 * around: a shin hung under a thigh, free to swing one way and held the other, is pushed both ways.
 */
class JointLimitAxesTests {
    private val spec = RagdollStateSpec()

    /** How far, in degrees, the shin turns from the thigh around X and around Z after being pushed with [push]. */
    private fun swingAfterPush(limits: JointLimits, push: Vec3f): Pair<Float, Float> {
        assertTrue(JoltNatives.ensureLoaded().isSuccess, "Jolt did not load")
        val shin = NodeDefinition(index = 2, name = "shin", children = mutableListOf(), transform = offset(-0.4f))
        val thigh = NodeDefinition(index = 1, name = "thigh", children = mutableListOf(shin), transform = offset(1f))
        shin.parent = thigh

        fun limb(name: String, joint: JointAttachmentSpec?) = RigBone(
            attachments = listOfNotNull(
                ColliderAttachmentSpec(id = name, offset = Vec3f(0f, -0.2f, 0f), size = Vec3f(0.12f, 0.4f, 0.12f)),
                RigidBodyAttachmentSpec(),
                joint,
            ),
        )

        val rig = ModelRig(
            bones = mapOf(
                "thigh" to limb("thigh", null),
                "shin" to limb("shin", JointAttachmentSpec(parent = "thigh", limits = limits))
            )
        )
        val target = PoseTarget(listOf(RuntimeNode(thigh, parent = null)).byIndex(), emptyMap(), rig = rig)

        val plan = requireNotNull(RagdollPlan.build(target, spec, target.mask(BoneMask.full())))
        val system = PhysicsWorld.createSystem()
        val instance = requireNotNull(RagdollInstance.create(system, RagdollTemplate.build(plan, spec), spec))
        val placement = ModelPlacement(Vec3f.ZERO, QuatF.IDENTITY)
        instance.start(placement, RagdollPose.globals(plan.order, AnimationPose(), HashMap()), Vec3f.ZERO)

        val allocator = TempAllocatorImpl(8 * 1024 * 1024)
        val jobs = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1)
        val bodies = system.bodyInterface
        val shinBody = instance.bodyIds[plan.bones.indexOfFirst { it.name == "shin" }]

        bodies.setMotionType(
            instance.bodyIds[plan.bones.indexOfFirst { it.name == "thigh" }],
            EMotionType.Kinematic,
            EActivation.DontActivate
        )
        repeat(40) {
            bodies.setLinearVelocity(shinBody, Vec3(push.x, push.y, push.z))
            bodies.activateBody(shinBody)
            system.update(1f / 60f, 1, allocator, jobs)
        }

        val pose = HashMap<Int, MutableMat4f>()
        instance.readInto(placement, pose)
        instance.close()

        val thighRotation = MutableQuatF().also { requireNotNull(pose[1]).decompose(null, it, null) }
        val shinRotation = MutableQuatF().also { requireNotNull(pose[2]).decompose(null, it, null) }
        val relative = MutableQuatF(thighRotation).invert().mul(shinRotation).norm()
        val down = Vec3f(0f, -1f, 0f).rotated(relative)
        val aroundX = Math.toDegrees(atan2(-down.z, -down.y).toDouble()).toFloat()
        val aroundZ = Math.toDegrees(atan2(down.x, -down.y).toDouble()).toFloat()
        return aroundX to aroundZ
    }

    private fun offset(y: Float) = TrsTransformF().apply { translate(Vec3f(0f, y, 0f)) }

    /** Held to a few degrees around X, free around Z. */
    private val freeAroundZ = JointLimits(AxisLimit(-5f, 5f), AxisLimit(-5f, 5f), AxisLimit(-80f, 80f))

    @Test
    fun `a push across the free axis swings the shin far`() {
        val (_, aroundZ) = swingAfterPush(freeAroundZ, Vec3f(6f, 0f, 0f))
        assertTrue(abs(aroundZ) > 30f, "The shin turned only $aroundZ degrees around Z, where it may turn 80")
    }

    @Test
    fun `a push across the held axis barely moves the shin`() {
        val (aroundX, _) = swingAfterPush(freeAroundZ, Vec3f(0f, 0f, 6f))
        assertTrue(abs(aroundX) < 15f, "The shin turned $aroundX degrees around X, where it may turn 5")
    }

    @Test
    fun `a hinge from 0 to 130 around X bends a hanging limb forward, toward -Z, and not back`() {
        val elbow = JointLimits.hinge(0f, 130f)
        val (forward, _) = swingAfterPush(elbow, Vec3f(0f, 0f, -6f))
        val (backward, _) = swingAfterPush(elbow, Vec3f(0f, 0f, 6f))
        assertTrue(forward > 45f, "Pushed forward, the limb bent only $forward degrees")
        assertTrue(backward > -10f, "Pushed back, the limb bent $backward degrees the wrong way")
    }
}

