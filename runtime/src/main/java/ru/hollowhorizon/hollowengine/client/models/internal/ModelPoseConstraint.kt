package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode

/** Authored constraints resolve after animation layers, before hierarchy matrices are published. */
fun interface ModelPoseConstraint {
    fun apply(nodes: Map<Int, RuntimeNode>)
}
