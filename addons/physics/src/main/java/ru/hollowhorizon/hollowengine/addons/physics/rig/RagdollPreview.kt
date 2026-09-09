package ru.hollowhorizon.hollowengine.addons.physics.rig

import com.github.stephengold.joltjni.BodyCreationSettings
import com.github.stephengold.joltjni.BoxShapeSettings
import com.github.stephengold.joltjni.JobSystemThreadPool
import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.PhysicsSystem
import com.github.stephengold.joltjni.Quat
import com.github.stephengold.joltjni.RVec3
import com.github.stephengold.joltjni.TempAllocatorImpl
import com.github.stephengold.joltjni.Vec3
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EMotionType
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.ModelPlacement
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollInstance
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPlan
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollPose
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollStateSpec
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollTemplate
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.shape
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorld
import ru.hollowhorizon.hollowengine.addons.physics.world.SoftContacts
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigPreview
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.ceil

/**
 * Rig editor's own ragdoll. Physics & Rag-doll preview.
 */
class RagdollPreview : RigPreview {
    private val spec = RagdollStateSpec()
    private var system: PhysicsSystem? = null
    private var allocator: TempAllocatorImpl? = null
    private var jobs: JobSystemThreadPool? = null
    private var contacts: SoftContacts? = null
    private var instance: RagdollInstance? = null
    private var plan: RagdollPlan? = null
    private var builtFor: PoseTarget? = null

    private val animated = HashMap<Int, MutableMat4f>()
    private val simulated = HashMap<Int, MutableMat4f>()

    override fun update(target: PoseTarget, deltaTime: Float): AnimationPose? {
        val plan = ensureBuilt(target) ?: return null
        val instance = instance ?: return null

        step(deltaTime)
        instance.readInto(PLACEMENT, simulated)
        return RagdollPose.write(plan, target, simulated, animated)
    }

    override fun push(direction: Vec3f) {
        instance?.push(direction)
    }

    override fun draw(lines: DebugLines.Batch) {
        instance?.forEachBody { bone, position, rotation ->
            lines.shape(bone.shape, position, rotation, BODY_COLOR)
        }
    }

    override fun close() {
        instance?.close()
        instance = null
        system?.let {
            it.removeAllBodies()
            it.destroyAllBodies()
        }
        system = null
        allocator = null
        contacts = null
        jobs = null
        plan = null
        builtFor = null
    }

    private fun ensureBuilt(target: PoseTarget): RagdollPlan? {
        if (builtFor === target) return plan
        if (!JoltNatives.isAvailable) return null

        close()
        builtFor = target

        val plan = RagdollPlan.build(target, spec, target.mask(BoneMask.full())) ?: return null
        val system = PhysicsWorld.createSystem().also { system = it }
        allocator = TempAllocatorImpl(TEMP_ALLOCATOR_BYTES)
        jobs = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1)
        contacts = SoftContacts().also { it.listenTo(system) }
        addFloor(system)

        val created = RagdollInstance.create(system, plan.let { RagdollTemplate.build(it, spec) }, spec)
        if (created == null) {
            HollowEngine.LOGGER.warn("Jolt would not build the rig preview")
            close()
            return null
        }

        RagdollPose.globals(plan.order, AnimationPose(), animated)
        created.start(PLACEMENT, animated, Vec3f.ZERO)
        contacts?.remember(created.softBodies())
        instance = created
        this.plan = plan
        return plan
    }

    private fun step(deltaTime: Float) {
        val system = system ?: return
        val allocator = allocator ?: return
        val jobs = jobs ?: return

        val step = deltaTime.coerceIn(0f, MAX_STEP * MAX_COLLISION_STEPS)
        if (step <= 0f) return

        val collisionSteps = ceil(step / MAX_STEP).toInt().coerceIn(1, MAX_COLLISION_STEPS)
        system.update(step, collisionSteps, allocator, jobs)
    }

    private fun addFloor(system: PhysicsSystem) {
        val settings = BodyCreationSettings(
            BoxShapeSettings(Vec3(FLOOR_EXTENT, FLOOR_THICKNESS, FLOOR_EXTENT)),
            RVec3(0.0, -FLOOR_THICKNESS.toDouble(), 0.0),
            Quat.sIdentity(),
            EMotionType.Static,
            PhysicsWorld.LAYER_STATIC,
        )
        val bodies = system.bodyInterface
        bodies.addBody(bodies.createBody(settings).id, EActivation.DontActivate)
    }

    private companion object {
        val PLACEMENT = ModelPlacement(Vec3f.ZERO, QuatF.IDENTITY)
        const val BODY_COLOR = 0xFF4DFF99.toInt()
        const val FLOOR_EXTENT = 80f
        const val FLOOR_THICKNESS = 0.5f
        const val MAX_STEP = 1f / 60f
        const val MAX_COLLISION_STEPS = 4
        const val TEMP_ALLOCATOR_BYTES = 8 * 1024 * 1024
    }
}
