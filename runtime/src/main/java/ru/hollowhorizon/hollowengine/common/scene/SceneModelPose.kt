package ru.hollowhorizon.hollowengine.common.scene

import ru.hollowhorizon.hollowengine.common.models.ModelPoseNode
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

/**
 * Per-instance model pose whose spatial state is owned by the scene object.
 * The caller initializes the object's local transform or invokes [resetPose] after attaching the model.
 * [restTransform] belongs to the shared asset and must be treated as read-only.
 */
class SceneModelPose(
    val sceneObject: SceneObject,
    override val restTransform: TrsTransformF,
    initialMorphWeights: FloatArray? = null,
    internal var hasSyntheticGeometry: Boolean = false,
    override val poseParent: ModelPoseNode? = null,
) : ModelPoseNode {
    init {
        require(restTransform !== sceneObject.localTransform) { "The rest transform must not alias the scene object's pose" }
    }

    override val poseTransform: TrsTransformF get() = sceneObject.localTransform
    override val acceptsPose: Boolean
        get() {
            if (!sceneObject.isActive) return false
            if (!sceneObject.isSynthetic || hasSyntheticGeometry) return true
            val children = sceneObject.children
            return children.isEmpty() || children.any { it.isActive }
        }
    override var isAnimated: Boolean = false
    override val baseMorphWeights: FloatArray = initialMorphWeights?.takeIf { it.isNotEmpty() }?.copyOf() ?: EMPTY_WEIGHTS
    override val morphWeights: FloatArray = if (baseMorphWeights.isEmpty()) EMPTY_WEIGHTS else baseMorphWeights.copyOf()

    override fun resetPose() {
        if (!acceptsPose) return
        isAnimated = false
        val current = poseTransform
        if (current.translation != restTransform.translation ||
            current.rotation != restTransform.rotation || current.scale != restTransform.scale
        ) {
            current.set(restTransform)
        }
        baseMorphWeights.copyInto(morphWeights)
    }
}

private val EMPTY_WEIGHTS = FloatArray(0)
