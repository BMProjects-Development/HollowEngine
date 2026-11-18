package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.models.ModelPoseNode

/** Authored constraints resolve after animation layers, before hierarchy matrices are published. */
fun interface ModelPoseConstraint {
    fun apply(nodes: Map<Int, ModelPoseNode>)
}
