package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator
import ru.hollowhorizon.hollowengine.common.models.IkChainSpec
import ru.hollowhorizon.hollowengine.common.models.IkSolver
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

/**
 * What one drawn model keeps between frames for the chains that stand on the ground: the ground each foot is
 * on, eased toward where it now is, so a foot does not jump at the edge of a block.
 */
class RigGroundingState {
    private class Eased(var value: Float, var velocity: Float = 0f, var feet: Float = 0f, var floating: Boolean = false)

    private val values = HashMap<String, Eased>()

    internal fun easeGround(key: String, ground: Float?, feet: Float, seconds: Float, duration: Float): Float {
        val previous = values[key]
        if (previous != null && previous.floating && ground != null) values.remove(key)
        values[key]?.let { eased ->
            if (ground == null) {
                val fall = feet - eased.feet
                if (fall <= 0f && eased.value < eased.feet - LOWER && eased.value < feet) {
                    eased.feet = feet
                    eased.velocity = 0f
                    eased.floating = true
                    return eased.value
                }
                eased.value += fall
            }
            eased.feet = feet
        }
        return ease(key, ground ?: feet, seconds, duration).also {
            values[key]?.let { eased ->
                eased.feet = feet
                eased.floating = ground == null
            }
        }
    }

    internal fun ease(key: String, wanted: Float, seconds: Float, duration: Float): Float {
        val eased = values[key]
        if (eased == null || duration <= 0f || abs(eased.value - wanted) > JUMP) return wanted.also { values[key] = Eased(it) }

        val omega = 2f / duration
        val x = omega * seconds
        val decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        val change = eased.value - wanted
        val pull = (eased.velocity + omega * change) * seconds
        eased.velocity = (eased.velocity - omega * pull) * decay
        eased.value = wanted + (change + pull) * decay
        return eased.value
    }

    private companion object {
        /** Further than this the value is not eased but moved at once. */
        const val JUMP = 3f

        /** How far below the body a foot's ground has to be for the foot to be held on it in the air. */
        const val LOWER = 0.01f
    }
}

/** Where a grounded chain reaches: [goal], and whether the ground [raised] it, as a step does. */
internal class PlantedGoal(val goal: Vec3f, val raised: Boolean)

/** How far the ground has to lift a foot for it to step up rather than just stand. */
private const val RAISED_BY_GROUND = 0.01f

/** The key a chain's eased ground is kept under. */
internal fun groundKey(bone: String, chain: IkChainSpec): String = "$bone/${chain.id}"

/**
 * Where the chains of [grounded] put their goal on the ground under it, by [groundKey], and lowers the pelvis
 * they share toward the lower ground under its legs. The goals are taken before the pelvis goes down, so
 * the feet stay where they stand and the legs bend.
 */
internal fun plantFeet(
    grounded: List<Pair<String, IkChainSpec>>,
    nodes: Map<String, RuntimeNode>,
    rig: ModelRig,
    context: AnimatorEvaluationContext,
    state: RigGroundingState,
): Map<String, PlantedGoal> {
    val ground = IkGround.of(context)
    val goals = HashMap<String, PlantedGoal>()
    val legs = HashMap<String, MutableList<Leg>>()

    grounded.forEach { (bone, chain) ->
        val end = nodes[bone] ?: return@forEach
        val links = chain.links(end)
        if (links.size < 2) return@forEach

        val goal = rig.ikTargetMatrix(chain.target, nodes::get)?.getTranslation() ?: end.modelMatrix().getTranslation()
        val reach = links.map { it.modelMatrix().getTranslation() }.zipWithNext { a, b -> a.distance(b) }.sum()
        val rest = end.restModelMatrix().getTranslation().y
        val footing = ground.footing(groundKey(bone, chain), goal, rest, reach, state, context.deltaTime, chain.blend)
        goals[groundKey(bone, chain)] = PlantedGoal(Vec3f(goal.x, goal.y + footing.shift, goal.z), raised = footing.shift > RAISED_BY_GROUND)

        if (chain.pelvis.isNotBlank()) {
            val weight = AnimatorExpressionEvaluator.float(chain.weight, context, 0f).coerceIn(0f, 1f)
            legs.getOrPut(chain.pelvis) { mutableListOf() } += Leg(footing.shift * weight, reach, footing.firm, chain.blend)
        }
    }

    legs.forEach { (pelvis, standing) ->
        val node = nodes[pelvis] ?: return@forEach
        val wanted = if (ground.moving || standing.any(Leg::firm)) 1f else 0f
        val share = state.ease("$PELVIS_KEY$pelvis", wanted, context.deltaTime, standing.maxOf(Leg::blend)).coerceIn(0f, 1f)
        if (share <= 0f) return@forEach
        val limit = minOf(ground.maxDrop, standing.minOf(Leg::reach) * MAX_LEG_DROP)
        val drop = standing.map { minOf(it.shift, 0f) }.average().toFloat()
        lower(node, drop.coerceIn(-limit, 0f) * share)
    }
    return goals
}

/** One leg standing under a pelvis: how far its foot moves, how long it is, whether it stands firm, and its [IkChainSpec.blend]. */
private class Leg(val shift: Float, val reach: Float, val firm: Boolean, val blend: Float)

/** The prefix a pelvis's eased share of its drop is kept under, apart from the chains' ground. */
private const val PELVIS_KEY = "pelvis:"

/** Moves [node] down by [drop] in model space, whatever its parent turns it by. */
private fun lower(node: RuntimeNode, drop: Float) {
    if (drop == 0f) return
    val down = Vec3f(0f, drop, 0f)
    val local = (node.parent as? RuntimeNode)?.let { parent ->
        val inverse = MutableMat4f(parent.modelMatrix())
        if (!inverse.invert()) return
        inverse.transform(down, 0f, MutableVec3f())
    } ?: down
    node.transform.translation.set(Vec3f(node.transform.translation) + local)
    node.transform.markDirty()
}

/** How far down a pelvis goes at most, as a share of the shortest leg standing on it. */
private const val MAX_LEG_DROP = 0.5f

/** How far down a pelvis goes at most, in blocks. */
private const val MAX_DROP_BLOCKS = 0.5f

/** How far below the entity's feet a foot's ground may be for the foot to still stand firm, in blocks. */
private const val FIRM = 0.05

/** How far a foot goes up or down to stand on the ground, and whether it stands on solid ground no lower than the entity. */
private class Footing(val shift: Float, val firm: Boolean)

/** What a ray under a foot found: the height to put it at, and whether there is solid ground there. */
private class Probe(val height: Double, val solid: Boolean)

/**
 * The ground chains put their goal on: the world under an entity, or a floor at the model's origin for a model
 * with no world around it, as in an editor's preview. Model space is taken to stand upright.
 */
private sealed interface IkGround {
    /** The furthest a pelvis goes down here, in model space. */
    val maxDrop: Float

    /** Whether the model walks on, so a body lowered toward the ground ahead stays lowered with no foot firm. */
    val moving: Boolean

    /**
     * How far, in model space, the goal of the chain kept under [key] goes up or down to stand on the ground,
     * eased by [state] over [blend] seconds, and whether the foot stands firm. [goal] is where the animation
     * puts it and [rest] how high it stands in the rest pose.
     */
    fun footing(key: String, goal: Vec3f, rest: Float, reach: Float, state: RigGroundingState, seconds: Float, blend: Float): Footing

    /**
     * The preview's floor. A model on it stands on it already, so the only thing to undo is a body lowered by
     * hand: a foot goes no lower than in the rest pose, and the legs bend instead.
     */
    object Floor : IkGround {
        override val maxDrop: Float = MAX_DROP_BLOCKS

        override val moving: Boolean = false

        override fun footing(key: String, goal: Vec3f, rest: Float, reach: Float, state: RigGroundingState, seconds: Float, blend: Float) =
            Footing(state.ease(key, maxOf(goal.y, rest) - goal.y, seconds, blend), firm = true)
    }

    /**
     * The blocks under an entity. The height of the ground is eased where it is, in the world, and only then
     * measured from the entity's feet: an entity stepping up onto a block rises at once, and a foot left on the
     * lower ground has to follow at once too, or the body would bob up and sink back.
     */
    class World(private val entity: Entity, toWorld: TrsTransformF, partialTick: Float) : IkGround {
        private val feet: Vec3 = entity.getPosition(partialTick)
        private val rotation: QuatF = MutableQuatF(toWorld.rotation)
        private val scale: Vec3f = Vec3f(toWorld.scale)

        private val shift: Vec3f = Vec3f(toWorld.translation) - Vec3f(feet.x.toFloat(), feet.y.toFloat(), feet.z.toFloat())

        /** An entity in the air has nothing under its feet, or its legs would reach down for the ground. */
        private val standing: Boolean = entity.onGround() || entity.isNoGravity

        /** Where the entity is heading, a few ticks of its last motion on, for feet to rise onto a step early. */
        private val ahead: Vec3 = Vec3(entity.x - entity.xo, 0.0, entity.z - entity.zo).scale(LOOKAHEAD_TICKS).let { motion ->
            if (motion.length() > LOOKAHEAD_MAX) motion.normalize().scale(LOOKAHEAD_MAX) else motion
        }

        override val maxDrop: Float = MAX_DROP_BLOCKS / scale.y

        override val moving: Boolean = ahead.lengthSqr() >= MIN_MOTION

        override fun footing(key: String, goal: Vec3f, rest: Float, reach: Float, state: RigGroundingState, seconds: Float, blend: Float): Footing {
            val offset = shift + IkSolver.rotate(goal * scale, rotation)
            val span = (reach * scale.y).coerceAtLeast(MIN_REACH).toDouble()
            val probe = groundAt(feet.x + offset.x, feet.z + offset.z, span)
            val eased = state.easeGround(key, probe?.height?.toFloat(), feet.y.toFloat(), seconds, blend)
            val firm = probe != null && probe.solid && probe.height - feet.y >= -FIRM
            return Footing(((eased - feet.y.toFloat()) - shift.y) / scale.y, firm)
        }

        /**
         * The highest ground under a foot at [x], [z] or a little ahead of it, so a swinging foot lifts onto a
         * step before its toe goes through it; null in the air, or where it is a wall rather than ground.
         */
        private fun groundAt(x: Double, z: Double, span: Double): Probe? {
            if (!standing) return null
            val under = probe(x, z, span)
            if (!moving) return under
            return listOfNotNull(under, probe(x + ahead.x, z + ahead.z, span)).maxByOrNull(Probe::height)
        }

        private fun probe(x: Double, z: Double, span: Double): Probe? {
            val hit = entity.level().clip(
                ClipContext(Vec3(x, feet.y + span, z), Vec3(x, feet.y - span, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)
            )
            if (hit.type == HitResult.Type.MISS) return Probe(feet.y - span, solid = false)
            if (hit.location.y - feet.y > entity.maxUpStep() + STEP_SLACK) return null
            return Probe(hit.location.y, solid = true)
        }

        private companion object {
            const val MIN_REACH = 0.5f

            /** Room over the step height for blocks whose top sits a hair above it, as soul sand does. */
            const val STEP_SLACK = 0.05

            /** How many ticks of motion ahead a foot looks for ground, and how far at most. */
            const val LOOKAHEAD_TICKS = 3.0
            const val LOOKAHEAD_MAX = 0.35
            const val MIN_MOTION = 1e-6
        }
    }

    companion object {
        fun of(context: AnimatorEvaluationContext): IkGround {
            val entity = context.entity ?: return Floor
            val toWorld = context.modelToWorld ?: return Floor
            return World(entity, toWorld, context.partialTick)
        }
    }
}
