package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.models.ModelPoseNode

/** Authored constraints resolve after animation layers, before hierarchy matrices are published. */
fun interface ModelPoseConstraint {
    fun apply(nodes: Map<Int, RuntimeNode>)
}

/** A constraint that also works with scene-owned pose nodes. */
interface SharedModelPoseConstraint : ModelPoseConstraint {
    fun applyPose(nodes: Map<Int, ModelPoseNode>)

    override fun apply(nodes: Map<Int, RuntimeNode>) = applyPose(nodes)
}
