package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator
import ru.hollowhorizon.hollowengine.common.models.IkChainSpec
import ru.hollowhorizon.hollowengine.common.models.IkEndRotation
import ru.hollowhorizon.hollowengine.common.models.IkSolver
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.ikChains
import ru.hollowhorizon.hollowengine.common.models.ikTarget
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

/**
 * Bends every IK chain of [rig] on the nodes under [roots].
 *
 * Chains are solved in the order the rig lists them, each on the pose the ones before it left.
 */
fun applyRigConstraints(roots: List<RuntimeNode>, rig: ModelRig, context: AnimatorEvaluationContext) {
    val chains = rig.ikChains()
    if (chains.isEmpty()) return

    val nodes = HashMap<String, RuntimeNode>()
    roots.forEach { root -> root.walk().forEach { nodes.putIfAbsent(it.name, it) } }
    val ground = IkGround.of(context)
    chains.forEach { (bone, chain) -> nodes[bone]?.let { solveChain(chain, it, nodes, rig, context, ground) } }
}

/** [this] node's matrix in the space of its model, from the local transforms as they are now. */
fun RuntimeNode.modelMatrix(): Mat4f {
    val above = parent as? RuntimeNode ?: return transform.matrixF
    return above.modelMatrix().mul(transform.matrixF, MutableMat4f())
}

/** [this] node's turn in the space of its model. */
fun RuntimeNode.modelRotation(): QuatF {
    val above = parent as? RuntimeNode ?: return MutableQuatF(transform.rotation)
    return above.modelRotation() * transform.rotation
}

/** [this] node's matrix in the space of its model in the rest pose, before anything animates it. */
fun RuntimeNode.restModelMatrix(): Mat4f {
    val above = parent as? RuntimeNode ?: return definition.baseTransform.matrixF
    return above.restModelMatrix().mul(definition.baseTransform.matrixF, MutableMat4f())
}

/** The bones a chain hung on [end] bends, from its root down to [end] itself. */
fun IkChainSpec.links(end: RuntimeNode): List<RuntimeNode> {
    val links = mutableListOf(end)
    while (links.size <= bones) links += links.last().parent as? RuntimeNode ?: break
    return links.asReversed()
}

/** Where the target named [id] stands in model space, turned as it is turned; null when the rig has none. */
fun ModelRig.ikTargetMatrix(id: String, nodes: (String) -> RuntimeNode?): Mat4f? {
    if (id.isBlank()) return null
    val (bone, spec) = ikTarget(id) ?: return null
    val holder = if (bone == null) Mat4f.IDENTITY else nodes(bone)?.modelMatrix() ?: return null
    return holder.mul(spec.localTransform().matrixF, MutableMat4f())
}

private fun solveChain(
    chain: IkChainSpec,
    end: RuntimeNode,
    nodes: Map<String, RuntimeNode>,
    rig: ModelRig,
    context: AnimatorEvaluationContext,
    ground: IkGround,
) {
    val links = chain.links(end)
    if (links.size < 2) return

    val weight = AnimatorExpressionEvaluator.float(chain.weight, context, 0f).coerceIn(0f, 1f)
    if (weight <= 0f) {
        if (chain.stretch > 1f) stretchGeometry(links, 1f)
        return
    }

    val joints = links.map { it.modelMatrix().getTranslation() }
    val target = rig.ikTargetMatrix(chain.target, nodes::get)
    var goal: Vec3f = target?.getTranslation() ?: joints.last()
    if (chain.ground) {
        val reach = joints.zipWithNext { a, b -> a.distance(b) }.sum()
        val floor = ground.heightUnder(goal, reach) ?: return
        val rest = links.last().restModelMatrix().getTranslation().y
        goal = Vec3f(goal.x, floor + maxOf(goal.y, rest), goal.z)
    }

    val pole = rig.ikTargetMatrix(chain.pole, nodes::get)?.getTranslation()
    val bendAxis = IkSolver.rotate(Vec3f.X_AXIS, links.first().modelRotation())
    val solution = IkSolver.solve(joints, goal, pole, bendAxis, chain.stretch, restReach(links))

    val stretch = 1f + (solution.stretch - 1f) * weight
    if (stretch != 1f) {
        for (i in 1..links.lastIndex) {
            val translation = links[i].transform.translation
            translation.set(Vec3f(translation) * stretch)
            links[i].transform.markDirty()
        }
    }
    if (chain.stretch > 1f) stretchGeometry(links, stretch)

    val original = links.map { MutableQuatF(it.transform.rotation) }
    val endRotation = links.last().modelRotation()
    val solved = arrayOfNulls<QuatF>(links.size)
    val current = joints.map { MutableVec3f(it) }
    var above: QuatF = (links.first().parent as? RuntimeNode)?.modelRotation() ?: QuatF.IDENTITY

    for (i in 0 until links.lastIndex) {
        val turn = IkSolver.rotationBetween(current[i + 1] - current[i], solution.joints[i + 1] - solution.joints[i])
        for (j in i + 1..links.lastIndex) current[j].set(current[i] + IkSolver.rotate(current[j] - current[i], turn))
        val global = turn * (above * original[i])
        solved[i] = above.inverted() * global
        above = global
    }
    solved[links.lastIndex] = when (chain.end) {
        IkEndRotation.KEEP -> above.inverted() * endRotation
        IkEndRotation.TARGET -> above.inverted() * (target?.getRotation()?.norm() ?: endRotation)
        IkEndRotation.FOLLOW -> null
    }

    links.forEachIndexed { i, node ->
        val turn = solved[i] ?: return@forEachIndexed
        node.transform.rotation.set(original[i].mix(turn, weight).norm())
        node.transform.markDirty()
    }
}

/** How far the end of the chain stands from its root in the rest pose, where the limb looks straight. */
private fun restReach(links: List<RuntimeNode>): Float {
    val rest = MutableMat4f(links.first().definition.baseTransform.matrixF)
    val root = rest.getTranslation()
    for (i in 1..links.lastIndex) rest.mul(links[i].definition.baseTransform.matrixF)
    return root.distance(rest.getTranslation())
}

/**
 * Stretches what each bone of the chain draws along the bone, by [factor].
 */
private fun stretchGeometry(links: List<RuntimeNode>, factor: Float) {
    for (i in 0 until links.lastIndex) {
        val bone = links[i]
        val next = links[i + 1]
        val along = Vec3f(next.transform.translation)
        if (along.length() < 1e-5f) continue
        val axis = along.normed()
        val scale =
            Vec3f(1f + (factor - 1f) * abs(axis.x), 1f + (factor - 1f) * abs(axis.y), 1f + (factor - 1f) * abs(axis.z))

        if (bone.definition.skin == null) {
            bone.attachments.forEach { attachment ->
                if (attachment !is MeshAttachment) return@forEach
                attachment.transform.scale.set(scale)
                attachment.transform.markDirty()
            }
        }
        if (factor == 1f) continue
        bone.children.forEach { child ->
            if (child === next || child.children.isNotEmpty()) return@forEach
            child.transform.translation.set(Vec3f(child.transform.translation) * scale)
            child.transform.scale.set(Vec3f(child.transform.scale) * scale)
            child.transform.markDirty()
        }
    }
}

/**
 * The ground chains put their goal on, as a height in model space, which is taken to stand upright: the world
 * under an entity, or a floor at the model's origin for a model with no world around it, as in an editor's
 * preview.
 */
private sealed interface IkGround {
    /** How high the ground under [goal] is in model space; null where there is none within [reach] of the feet. */
    fun heightUnder(goal: Vec3f, reach: Float): Float?

    object Floor : IkGround {
        override fun heightUnder(goal: Vec3f, reach: Float): Float = 0f
    }

    class World(private val entity: Entity, toWorld: TrsTransformF, partialTick: Float) : IkGround {
        private val feet: Vec3 = entity.getPosition(partialTick)
        private val rotation: QuatF = MutableQuatF(toWorld.rotation)
        private val scale: Vec3f = Vec3f(toWorld.scale)

        private val shift: Vec3f = Vec3f(toWorld.translation) - Vec3f(feet.x.toFloat(), feet.y.toFloat(), feet.z.toFloat())

        override fun heightUnder(goal: Vec3f, reach: Float): Float? {
            val offset = shift + IkSolver.rotate(goal * scale, rotation)
            val span = (reach * scale.y).coerceAtLeast(MIN_REACH).toDouble()
            val x = feet.x + offset.x
            val z = feet.z + offset.z
            val hit = entity.level().clip(
                ClipContext(Vec3(x, feet.y + span, z), Vec3(x, feet.y - span, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)
            )
            if (hit.type == HitResult.Type.MISS) return null
            return ((hit.location.y - feet.y).toFloat() - shift.y) / scale.y
        }

        private companion object {
            const val MIN_REACH = 0.5f
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
