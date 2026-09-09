import com.github.stephengold.joltjni.JobSystemThreadPool
import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.Quat
import com.github.stephengold.joltjni.RVec3
import com.github.stephengold.joltjni.SkeletonPose
import com.github.stephengold.joltjni.TempAllocatorImpl
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.addons.physics.matrixOf
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.ModelPlacement
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollBone
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollInstance
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPlan
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollShape
import ru.hollowhorizon.hollowengine.addons.physics.rig.AxisLimit
import ru.hollowhorizon.hollowengine.addons.physics.rig.BodyCollision
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointLimits
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollTemplate
import ru.hollowhorizon.hollowengine.addons.physics.toJolt
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorld
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RagdollSimulationTests {
    private val spec = RagdollStateSpec()

    private fun spinePlan(): RagdollPlan {
        val bones = (0 until 3).map { index ->
            RagdollBone(
                nodeIndex = index,
                name = "bone$index",
                parent = index - 1,
                modelParent = if (index == 0) null else index - 1,
                bindPosition = Vec3f(0f, index * 0.5f, 0f),
                bindRotation = QuatF.IDENTITY,
                shape = RagdollShape.alongBone(Vec3f.Y_AXIS, length = 0.5f, radius = 0.1f),
                pivot = Vec3f.ZERO,
                density = spec.density,
                collision = BodyCollision(),
                limits = JointLimits(x = AxisLimit.of(spec.twistAngle), y = AxisLimit.of(spec.swingAngle), z = AxisLimit.of(spec.swingAngle)),
            )
        }
        return RagdollPlan(bones, emptyList())
    }

    @Test
    fun `a ragdoll set to a pose falls from it`() {
        val loaded = JoltNatives.ensureLoaded()
        assertTrue(loaded.isSuccess, "Jolt did not load: ${loaded.exceptionOrNull()}")

        val plan = spinePlan()
        val template = RagdollTemplate.build(plan, spec)
        val system = PhysicsWorld.createSystem()
        val ragdoll = requireNotNull(template.instantiate(system)) { "Jolt refused to create the ragdoll" }

        val origin = Vec3f(8f, 70f, 8f)
        val pose = SkeletonPose().apply { setSkeleton(template.skeleton) }
        pose.setRootOffset(RVec3(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()))
        plan.bones.forEachIndexed { index, bone ->
            pose.jointMatrices.set(index, matrixOf(QuatF.IDENTITY, bone.bindPosition).toJolt())
        }
        ragdoll.setPose(pose)

        val start = RVec3()
        ragdoll.getRootTransform(start, Quat())

        val allocator = TempAllocatorImpl(8 * 1024 * 1024)
        val jobs = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1)
        repeat(30) { system.update(1f / 60f, 1, allocator, jobs) }

        val end = RVec3()
        ragdoll.getRootTransform(end, Quat())
        val fell = start.yy() - end.yy()

        assertTrue(fell > 0.1, "The ragdoll did not fall: it moved ${"%.4f".format(fell)} blocks in half a second")

        ragdoll.getPose(pose)
        val readBack = pose.rootOffset.yy() + pose.getJointMatrix(0).translation.y
        assertTrue(
            readBack < origin.y - 0.1f,
            "The pose read back says y = $readBack, but the bodies are at ${end.yy()}",
        )

        ragdoll.removeFromPhysicsSystem()
    }

    @Test
    fun `a pose written in model space comes back in model space`() {
        val loaded = JoltNatives.ensureLoaded()
        assertTrue(loaded.isSuccess, "Jolt did not load: ${loaded.exceptionOrNull()}")

        val plan = spinePlan()
        val template = RagdollTemplate.build(plan, spec)
        val system = PhysicsWorld.createSystem()
        val instance = requireNotNull(RagdollInstance.create(system, template, spec))

        val placement = ModelPlacement(
            origin = Vec3f(1000.5f, 70f, -400.5f),
            rotation = QuatF(90f.deg, Vec3f.Y_AXIS),
        )
        val globals = plan.bones.associate { bone ->
            bone.nodeIndex to matrixOf(QuatF.IDENTITY, bone.bindPosition) as Mat4f
        }
        instance.start(placement, globals, Vec3f.ZERO)

        val restored = HashMap<Int, MutableMat4f>()
        instance.readInto(placement, restored)
        plan.bones.forEach { bone ->
            val position = MutableVec3f()
            requireNotNull(restored[bone.nodeIndex]).decompose(position, null, null)
            assertTrue(
                distanceBetween(position, bone.bindPosition) < 0.01f,
                "Bone ${bone.name} came back at $position instead of ${bone.bindPosition}",
            )
        }

        val allocator = TempAllocatorImpl(8 * 1024 * 1024)
        val jobs = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1)
        repeat(30) { system.update(1f / 60f, 1, allocator, jobs) }

        instance.readInto(placement, restored)
        val root = MutableVec3f()
        requireNotNull(restored[0]).decompose(root, null, null)

        assertTrue(root.y < -0.1f, "After half a second of falling the root is at $root")
        assertTrue(
            abs(root.x) < 0.5f && abs(root.z) < 0.5f,
            "The ragdoll drifted sideways in model space to $root, which means a rotation is inverted",
        )

        instance.close()
    }

    @Test
    fun `a ragdoll that has been closed is inert rather than fatal`() {
        val loaded = JoltNatives.ensureLoaded()
        assertTrue(loaded.isSuccess, "Jolt did not load: ${loaded.exceptionOrNull()}")

        val plan = spinePlan()
        val template = RagdollTemplate.build(plan, spec)
        val system = PhysicsWorld.createSystem()
        val instance = requireNotNull(RagdollInstance.create(system, template, spec))
        val placement = ModelPlacement(Vec3f(0f, 70f, 0f), QuatF.IDENTITY)

        instance.close()
        assertFalse(instance.isAlive)

        val store = HashMap<Int, MutableMat4f>()
        instance.readInto(placement, store)
        instance.beforeStep()
        instance.start(placement, emptyMap(), Vec3f.ZERO)
        instance.forEachBody { _, _, _ -> error("A closed ragdoll reported a body") }
        instance.close()

        assertTrue(store.isEmpty(), "A closed ragdoll wrote a pose")
    }

    private fun distanceBetween(first: Vec3f, second: Vec3f): Float =
        MutableVec3f(first).subtract(second).length()
}
