package ru.hollowhorizon.hollowengine.common.models

import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f

/** Mutable per-instance pose and shared, read-only-by-contract rest transform consumed by animation layers. */
interface ModelPoseNode {
    /** Source data must stay stable for this pose's lifetime; its index is not a persistent address. */
    val source: ModelNodeSource
    val poseIndex: Int get() = source.index
    val poseName: String get() = source.name ?: "Node_$poseIndex"
    val poseParent: ModelPoseNode? get() = null
    val poseTransform: TrsTransformF
    /** Matrix in the same space as the owning model's mesh matrices. */
    val poseGlobalMatrix: Mat4f
    val restTransform: TrsTransformF get() = source.restTransform
    val acceptsPose: Boolean get() = true
    var isAnimated: Boolean
    val baseMorphWeights: FloatArray
    val morphWeights: FloatArray

    /** Restore this instance's rest pose before the next animation evaluation. */
    fun resetPose()
}
