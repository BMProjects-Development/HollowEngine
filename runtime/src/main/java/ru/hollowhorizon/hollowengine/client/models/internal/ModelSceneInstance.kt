package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.applyAnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigConstraintsToPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigGroundingState
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.addedBones
import ru.hollowhorizon.hollowengine.common.scene.SceneAddress
import ru.hollowhorizon.hollowengine.common.scene.SceneFailures
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import java.util.*

/** A model's mutable pose and hierarchy under one scene object; the model asset remains shared. */
class ModelSceneInstance private constructor(
    val root: SceneObject,
) : AutoCloseable {
    var model: Model = Model.EMPTY
        private set
    private var imported = LinkedHashMap<Int, SceneObject>()
    private var poses = imported
    private val rigBones = LinkedHashMap<String, SceneObject>()
    private var authoredConstraintsApplied = false
    private var target: PoseTarget? = null

    /** Imported pose nodes in hierarchy order, including loader-generated transforms. */
    val poseNodes: Collection<SceneObject> get() = poses.values
    internal val poseNodesByIndex: Map<Int, SceneObject> get() = poses

    internal fun forgetPose(index: Int, node: SceneObject) {
        if (poses[index] !== node) return
        poses.remove(index)
        if (imported[index] === node) imported.remove(index)
        target = null
    }

    internal fun updateRigBoneRestTransforms(previous: ModelRig, rig: ModelRig) {
        for ((name, node) in rigBones) {
            val origin = rig.bone(name)?.origin ?: continue
            if (origin == previous.bone(name)?.origin) continue
            val rest = node.restTransform
            val next = origin.localTransform()
            val pose = node.localTransform
            fun scale(value: Float, before: Float, after: Float): Float =
                if (before == 0f) after else (value / before) * after
            pose.setCompositionOf(
                Vec3f(pose.translation) + (origin.offset - rest.translation),
                QuatF(next.rotation) * QuatF(rest.rotation).inverted() * QuatF(pose.rotation),
                Vec3f(
                    scale(pose.scale.x, rest.scale.x, next.scale.x),
                    scale(pose.scale.y, rest.scale.y, next.scale.y),
                    scale(pose.scale.z, rest.scale.z, next.scale.z),
                ),
            )
            rest.set(next)
        }
    }

    /** Animation target backed by this instance's scene objects. */
    fun poseTarget(rig: ModelRig = ModelRig.EMPTY): PoseTarget {
        target?.takeIf { it.rig == rig }?.let { return it }
        return PoseTarget(poses, model.animationsByName, rig.boneByAlias, rig, model.molangDefaults)
            .also { target = it }
    }

    fun node(path: List<ScenePathSegment>): SceneObject? =
        root.scene.resolve(SceneAddress(checkNotNull(root.createdId), path))

    /** Reconcile imported nodes by authored path, retaining additions on nodes that still exist. */
    fun reload(replacement: Model): List<SceneAddress> {
        check(!root.isRemoved) { "Model instance has been removed" }
        if (replacement === model) return emptyList()
        val paths = replacement.nodePaths
        val definitions = replacement.scenes.getOrNull(replacement.scene)?.nodes.orEmpty()
        val existing = imported.values.filterNot { it.isSynthetic || it.isRemoved }
            .associateBy { it.address.importedPath }
        val indices = HashSet<Int>()

        fun validate(definition: NodeDefinition, logicalPath: List<ScenePathSegment>) {
            require(indices.add(definition.index)) { "Duplicate model node index: ${definition.index}" }
            val path = if (definition.isSynthetic) logicalPath else {
                val segment = checkNotNull(paths.segment(definition))
                val holder = if (logicalPath.isEmpty()) root else existing[logicalPath]
                require(holder?.children?.none { it.createdId != null && it.name == segment.name } != false) {
                    "Imported node conflicts with created child: ${segment.name}"
                }
                logicalPath + segment
            }
            definition.children.forEach { validate(it, path) }
        }
        definitions.forEach { validate(it, emptyList()) }

        val previous = imported.values.toList()
        val remaining = existing.toMutableMap()
        val next = LinkedHashMap<Int, SceneObject>()
        val failures = SceneFailures()
        fun visit(definition: NodeDefinition, holder: SceneObject, logicalPath: List<ScenePathSegment>): Boolean {
            val path = if (definition.isSynthetic) logicalPath else logicalPath + checkNotNull(paths.segment(definition))
            val node = if (definition.isSynthetic) {
                holder.importSynthetic(definition.name?.takeUnless(String::isBlank) ?: "Node_${definition.index}")
            } else {
                val retained = remaining.remove(path)
                if (retained == null) holder.importModelChild(checkNotNull(paths.segment(definition)))
                else retained.also { failures.capture { it.moveWithinLogicalParent(holder) } }
            }
            node.localTransform.set(definition.baseTransform)
            node.setModelSource(definition, definition.mesh?.primitives?.firstOrNull()?.weights)
            next[definition.index] = node
            var geometry = definition.mesh != null
            definition.children.forEach { child ->
                val childGeometry = visit(child, node, path)
                if (child.isSynthetic && childGeometry) geometry = true
            }
            if (definition.isSynthetic && geometry) node.includeSyntheticGeometry()
            return geometry
        }
        definitions.forEach { visit(it, root, emptyList()) }

        val removed = remaining.values.map { it.address }
        val obsolete = remaining.values.toHashSet()
        previous.asReversed().forEach { old ->
            if ((old.isSynthetic || old in obsolete) && !old.isRemoved) {
                failures.capture(old::remove)
            }
        }
        this.model = replacement
        imported = next
        poses = next
        target = null
        authoredConstraintsApplied = false
        failures.throwIfAny()
        return removed
    }

    /** Rebuilds the rig's pose index, preserving scene nodes at the same location. */
    internal fun setRigBones(rig: ModelRig): List<String> {
        val added = rig.addedBones
        if (added.isEmpty() && rigBones.isEmpty()) return emptyList()
        val next = LinkedHashMap(imported)
        val byName = HashMap<String, SceneObject>()
        imported.values.forEach { byName.putIfAbsent(it.poseName, it) }
        var index = (next.keys.maxOrNull() ?: -1) + 1
        val pending = added.filterKeys { it !in byName }.toMutableMap()
        val retained = LinkedHashMap<String, SceneObject>()
        val removed = ArrayList<String>()
        while (pending.isNotEmpty()) {
            val ready = pending.filterValues { it.parent == null || it.parent in byName }
            if (ready.isEmpty()) break
            for ((name, origin) in ready) {
                val parent = origin.parent?.let(byName::getValue)
                val holder = parent ?: root
                val previous = rigBones.remove(name)?.takeUnless { it.isRemoved }
                val node = if (previous?.physicalParent === holder) {
                    previous
                } else {
                    if (previous != null) {
                        previous.remove()
                        removed += name
                    }
                    holder.create(name)
                }
                val definition = NodeDefinition(index++, name, mutableListOf(), origin.localTransform())
                definition.parent = parent?.source as? NodeDefinition
                node.localTransform.set(definition.baseTransform)
                node.setModelSource(definition)
                next[definition.index] = node
                retained[name] = node
                byName[name] = node
                pending.remove(name)
            }
        }
        rigBones.forEach { (name, node) ->
            if (!node.isRemoved) {
                node.remove()
                removed += name
            }
        }
        rigBones.clear()
        rigBones.putAll(retained)
        poses = if (retained.isEmpty()) imported else next
        target = null
        authoredConstraintsApplied = false
        return removed
    }

    fun resetPose() {
        invalidateConstraints()
        poses.values.forEach(SceneObject::resetPose)
    }

    internal fun invalidateConstraints() { authoredConstraintsApplied = false }

    /** Resolve imported model constraints after animation and before consuming the pose. */
    fun applyAuthoredConstraints() {
        if (authoredConstraintsApplied) return
        model.constraints.forEach { it.apply(poses) }
        authoredConstraintsApplied = true
    }

    /** Apply pose offsets to imported nodes; this does not create rig attachments or added bones. */
    fun applyRigPose(rig: ModelRig) {
        if (rig.bones.values.none { it.pose != null }) return
        poses.values.forEach { node ->
            rig.bone(node.poseName)?.pose?.let(node::applyRigPose)
        }
    }

    internal fun applyRigConstraints(
        rig: ModelRig,
        context: AnimatorEvaluationContext,
        grounding: RigGroundingState? = null,
        scaleMeshes: ((SceneObject, Vec3f) -> Unit)? = null,
    ) {
        applyAuthoredConstraints()
        applyRigConstraintsToPose(poseNodes, rig, context, grounding, scaleMeshes)
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
            val root = parent.create(name, id)
            try {
                return ModelSceneInstance(root).also { it.reload(model) }
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

/** Mount for a nested model. Its placement stays separate from the placed model's own local pose. */
internal fun SceneObject.mountModel(spec: ModelAttachmentSpec): SceneObject {
    val baseName = spec.id.ifBlank { spec.model.ifBlank { "model" } }
    val names = if (isSynthetic) checkNotNull(parent).children else children
    var name = baseName
    var suffix = 2
    while (names.any { it.name == name }) name = "$baseName (${suffix++})"
    return importSynthetic(name)
}
