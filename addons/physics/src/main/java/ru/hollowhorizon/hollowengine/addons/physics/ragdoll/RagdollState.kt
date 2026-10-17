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
    private var attached: Pair<PhysicsWorld, RagdollKey>? = null
    private var running: RagdollInstance? = null
    private var follows: Int? = null
    private val warnings = HashSet<String>()

    /** Pose, that controller assumed when it started moving towards this spot. */
    private var seed: AnimationPose = AnimationPose()
    private var seeded = false

    private val animated = HashMap<Int, MutableMat4f>()
    private val simulated = HashMap<Int, MutableMat4f>()

    override fun enter(from: AnimationPose?) {
        seed = from ?: AnimationPose()
        seeded = false
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
        val entity = context.entity ?: return null
        val placement = placementOf(context) ?: return null
        val plan = planFor(target, allowed) ?: return null
        if (!seeded) {
            RagdollPose.globals(plan.order, seed, animated)
            seeded = true
        }

        val client = entity.level().isClientSide
        return when (spec.simulation) {
            RagdollSimulation.SERVER if client -> replicated(entity, placement, plan, target)
            RagdollSimulation.CLIENT if !client -> null
            else -> simulated(entity, placement, plan, target, context)
        }
    }

    /** Runs the bodies here: on the server for everyone, or on a client for itself alone. */
    private fun simulated(
        entity: Entity,
        placement: ModelPlacement,
        plan: RagdollPlan,
        target: PoseTarget,
        context: AnimatorEvaluationContext,
    ): AnimationPose? {
        if (!JoltNatives.isAvailable) return null
        val level = entity.level()
        val world = PhysicsWorlds.of(level) ?: return null
        val key = RagdollKey(entity.id, spec.id)

        val instance = world.ragdoll(key) {
            val template = template ?: RagdollTemplate.build(plan, spec).also { template = it }
            RagdollInstance.create(world, template, spec)?.also {
                it.start(placement, animated, velocityOf(entity))
                HollowEngine.LOGGER.debug("Ragdoll '{}' woke up with {} bodies", spec.id, plan.bones.size)
            }
        } ?: return warnOnce("Jolt would not build the ragdoll for state '${spec.id}'")
        attached = world to key
        val fresh = instance !== running
        running = instance

        var placed = placement
        if (level.isClientSide) {
            world.stepOnce(TickHandler.renderFrame, context.deltaTime)
        } else {
            if (!fresh) RagdollReplication.impulseOf(entity)?.let { instance.nudge(it * spec.inheritVelocity) }
            world.stepOnce(level.gameTime, SECONDS_PER_TICK)
            RagdollReplication.publish(entity, instance, fresh)
            val moved = RagdollReplication.anchor(entity, instance)
            placed = placement.moved(moved.x, moved.y, moved.z)
        }

        instance.readInto(placed, simulated)
        return RagdollPose.write(plan, target, simulated, animated)
    }

    /** Follows the bodies the server sends, holding the pose it entered with until the first of them arrives. */
    private fun replicated(
        entity: Entity,
        placement: ModelPlacement,
        plan: RagdollPlan,
        target: PoseTarget,
    ): AnimationPose {
        follows = entity.id
        val bodies = RagdollReplicas.poseAt(entity.id, RagdollReplicas.clientNow())
            ?: return RagdollPose.write(plan, target, animated, animated)
        bodies.readInto(placement, simulated)
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
        seeded = false
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
        attached?.let { (world, key) -> world.forget(key) }
        attached = null
        running = null
        follows?.let(RagdollReplicas::forget)
        follows = null
    }

    /**
     * Which ragdoll of a world this is. An entity's model can be posed by more than one animator on a client, the
     * drawn one and the one its colliders move by, and those share one ragdoll rather than each running its own.
     */
    private data class RagdollKey(val entityId: Int, val stateId: String)

    private companion object {
        const val TICKS_PER_SECOND = 20f
        const val SECONDS_PER_TICK = 1f / TICKS_PER_SECOND
        const val SCALE_TOLERANCE = 0.05f
    }
}
