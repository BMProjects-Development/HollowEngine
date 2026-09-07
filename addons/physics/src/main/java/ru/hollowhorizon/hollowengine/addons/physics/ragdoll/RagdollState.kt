package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorld
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorlds
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationState
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerStateSpec
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

/**
 * State of the model is in while the physics engine controls its bones.
 *
 * Simulation does not start until the controller initiates movement: rag-doll is generated based on the pose from which
 * it has given and duration of the transition itself ensures a smooth transition between these two states.
 */
class RagdollState(private var spec: RagdollStateSpec) : AnimationState {
    private var plan: RagdollPlan? = null
    private var planTarget: PoseTarget? = null
    private var planAllowed: Set<Int>? = null
    private var template: RagdollTemplate? = null
    private var attached: PhysicsWorld? = null
    private val warnings = HashSet<String>()

    /** Pose, that controller assumed when it started moving towards this spot. */
    private var seed: AnimationPose = AnimationPose()

    private val animated = HashMap<Int, MutableMat4f>()
    private val simulated = HashMap<Int, MutableMat4f>()

    override fun enter(from: AnimationPose?) {
        seed = from ?: AnimationPose()
        release()
    }

    override fun exit() = release()

    override fun reconfigure(spec: AnimationControllerStateSpec): Boolean {
        val ragdoll = spec as? RagdollStateSpec ?: return false
        if (ragdoll == this.spec) return true

        this.spec = ragdoll
        template = null
        plan = null
        planTarget = null
        release()
        return true
    }

    override fun sample(
        target: PoseTarget,
        allowed: Set<Int>,
        context: AnimatorEvaluationContext,
    ): AnimationPose? {
        if (!JoltNatives.isAvailable) return null

        val entity = context.entity ?: return null
        val placement = placementOf(context) ?: return null
        val world = PhysicsWorlds.of(entity.level()) ?: return null
        val plan = planFor(target, allowed) ?: return null

        val instance = world.ragdoll(this) {
            RagdollPose.globals(plan.order, seed, animated)
            val template = template ?: RagdollTemplate.build(plan, spec).also { template = it }
            RagdollInstance.create(world, template, spec)?.also {
                it.start(placement, animated, velocityOf(entity))
                attached = world
                HollowEngine.LOGGER.debug("Ragdoll '{}' woke up with {} bodies", spec.id, plan.bones.size)
            }
        } ?: return warnOnce("Jolt would not build the ragdoll for state '${spec.id}'")

        world.stepOnce(TickHandler.renderFrame, context.deltaTime)
        instance.readInto(placement, simulated)
        return RagdollPose.write(plan, target, simulated, animated)
    }

    private fun planFor(target: PoseTarget, allowed: Set<Int>): RagdollPlan? {
        if (planTarget === target && planAllowed == allowed) return plan

        planTarget = target
        planAllowed = allowed
        template = null
        release()
        animated.clear()
        simulated.clear()
        plan = RagdollPlan.build(target, spec, allowed)
        plan?.let { HollowEngine.LOGGER.debug("Ragdoll state '{}' simulates {} bones", spec.id, it.bones.size) }
            ?: warnOnce("Ragdoll state '${spec.id}' has no bones to simulate")
        return plan
    }

    private fun warnOnce(message: String): Nothing? {
        if (warnings.add(message)) HollowEngine.LOGGER.warn(message)
        return null
    }

    private fun placementOf(context: AnimatorEvaluationContext): ModelPlacement? {
        val transform = context.modelToWorld ?: return null
        val scale = transform.scale
        if (abs(scale.x - 1f) > SCALE_TOLERANCE || abs(scale.y - 1f) > SCALE_TOLERANCE || abs(scale.z - 1f) > SCALE_TOLERANCE) {
            warnOnce("Ragdoll state '${spec.id}' is on a model scaled to $scale; bodies are built at the model's own size")
        }
        return ModelPlacement(Vec3f(transform.translation), MutableQuatF(transform.rotation).norm())
    }

    private fun velocityOf(entity: Entity): Vec3f {
        val movement = entity.deltaMovement
        return Vec3f(
            movement.x.toFloat() * TICKS_PER_SECOND,
            movement.y.toFloat() * TICKS_PER_SECOND,
            movement.z.toFloat() * TICKS_PER_SECOND,
        ) * spec.inheritVelocity
    }

    private fun release() {
        attached?.forget(this)
        attached = null
    }

    private companion object {
        const val TICKS_PER_SECOND = 20f
        const val SCALE_TOLERANCE = 0.05f
    }
}
