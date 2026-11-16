package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.applyAnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigPose
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.scene.SceneAddress
import ru.hollowhorizon.hollowengine.common.scene.SceneModelPose
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import java.util.*

/** A model's mutable pose and hierarchy under one scene object; the model asset remains shared. */
class ModelSceneInstance private constructor(
    val root: SceneObject,
    val model: Model,
    private val poses: Map<Int, SceneModelPose>,
) : AutoCloseable {
    private var authoredConstraintsApplied = false
    private var target: PoseTarget? = null

    /** Animation target backed by this instance's scene objects, without legacy rig or physics nodes. */
    fun poseTarget(rig: ModelRig = ModelRig.EMPTY): PoseTarget {
        target?.takeIf { it.rig == rig }?.let { return it }
        return PoseTarget.forScene(poses, model.activeNodesByIndex, model.animationsByName, rig, model.molangDefaults)
            .also { target = it }
    }

    fun node(path: List<ScenePathSegment>): SceneObject? =
        root.scene.resolve(SceneAddress(checkNotNull(root.createdId), path))

    fun resetPose() {
        authoredConstraintsApplied = false
        poses.values.forEach(SceneModelPose::resetPose)
    }

    /** Resolve imported model constraints after animation and before consuming the pose. */
    fun applyAuthoredConstraints() {
        if (authoredConstraintsApplied) return
        check(model.constraints.all { it is SharedModelPoseConstraint }) {
            "The model contains constraints that do not support scene-owned poses"
        }
        model.constraints.forEach { (it as SharedModelPoseConstraint).applyPose(poses) }
        authoredConstraintsApplied = true
    }

    /** Apply pose offsets to imported nodes; this does not create rig attachments or added bones. */
    fun applyRigPose(rig: ModelRig) {
        if (rig.bones.values.none { it.pose != null }) return
        val definitions = model.activeNodesByIndex
        poses.forEach { (index, node) ->
            val definition = definitions[index] ?: return@forEach
            val name = definition.name ?: "Node_${definition.index}"
            rig.bone(name)?.pose?.let(node::applyRigPose)
        }
    }

    fun applyPose(
        pose: AnimationPose,
        blendMode: LayerBlendMode,
        weight: Float,
        reference: AnimationPose? = null,
    ) {
        applyAnimationPose(pose, poses, blendMode, weight, reference)
    }

    override fun close() = root.remove()

    companion object {
        /** Constructs an independent instance below [parent], rolling back if the asset is invalid. */
        fun create(parent: SceneObject, name: String, model: Model, id: UUID = UUID.randomUUID()): ModelSceneInstance {
            val paths = model.nodePaths
            val root = parent.create(name, id)
            try {
                val poses = LinkedHashMap<Int, SceneModelPose>()
                fun visit(definition: NodeDefinition, holder: SceneObject, parentPose: SceneModelPose?): Boolean {
                    val node = if (definition.isSynthetic) {
                        holder.importSynthetic(
                            definition.name?.takeUnless(String::isBlank) ?: "Node_${definition.index}"
                        )
                    } else {
                        holder.importModelChild(checkNotNull(paths.segment(definition)))
                    }
                    node.localTransform.set(definition.baseTransform)
                    val weights = definition.mesh?.primitives?.firstOrNull()?.weights
                    val nodePose = SceneModelPose(node, definition.baseTransform, weights, poseParent = parentPose)
                    require(poses.put(definition.index, nodePose) == null) {
                        "Duplicate model node index: ${definition.index}"
                    }
                    var syntheticGeometry = definition.mesh != null
                    definition.children.forEach { child ->
                        val childGeometry = visit(child, node, nodePose)
                        if (child.isSynthetic && childGeometry) syntheticGeometry = true
                    }
                    nodePose.hasSyntheticGeometry = syntheticGeometry
                    return syntheticGeometry
                }
                model.scenes.getOrNull(model.scene)?.nodes.orEmpty().forEach { visit(it, root, null) }
                return ModelSceneInstance(root, model, poses)
            } catch (exception: Exception) {
                try {
                    root.remove()
                } catch (cleanup: Exception) {
                    if (cleanup !== exception) exception.addSuppressed(cleanup)
                }
                throw exception
            }
        }
    }
}
