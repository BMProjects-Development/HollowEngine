package ru.hollowhorizon.hollowengine.common.models

import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

/** Mutable per-instance pose and shared, read-only-by-contract rest transform consumed by animation layers. */
interface ModelPoseNode {
    val poseParent: ModelPoseNode? get() = null
    val poseTransform: TrsTransformF
    val restTransform: TrsTransformF
    val acceptsPose: Boolean get() = true
    var isAnimated: Boolean
    val baseMorphWeights: FloatArray
    val morphWeights: FloatArray

    /** Restore this instance's rest pose before the next animation evaluation. */
    fun resetPose()
}
