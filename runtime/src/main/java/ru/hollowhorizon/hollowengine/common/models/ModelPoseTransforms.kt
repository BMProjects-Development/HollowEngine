package ru.hollowhorizon.hollowengine.common.models

import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF

/** This node's current matrix in model space. The root's matrix is a live view. */
fun ModelPoseNode.modelMatrix(): Mat4f =
    if (poseParent == null) poseTransform.matrixF else modelMatrix(MutableMat4f())

/** Write into caller-owned storage, which must not be any node's local matrix. */
fun ModelPoseNode.modelMatrix(result: MutableMat4f): MutableMat4f {
    val local = poseTransform.matrixF
    require(result !== local) { "Result must not alias a local pose matrix" }
    val above = poseParent ?: return result.set(local)
    return above.modelMatrix(result).mul(local)
}

/** This node's current rotation in model space. */
fun ModelPoseNode.modelRotation(): QuatF = modelRotation(MutableQuatF())

/** Write into caller-owned storage, which must not be any node's local rotation. */
fun ModelPoseNode.modelRotation(result: MutableQuatF): MutableQuatF {
    val local = poseTransform.rotation
    require(result !== local) { "Result must not alias a local pose rotation" }
    val above = poseParent ?: return result.set(local)
    return above.modelRotation(result).mul(local)
}
