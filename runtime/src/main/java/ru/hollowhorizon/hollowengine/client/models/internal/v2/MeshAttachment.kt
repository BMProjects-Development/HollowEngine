package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.SkinPose
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f

/**
 * Defines the shape and appearance of an object attached to a node.
 */
class MeshAttachment(
    val primitive: Primitive,
    private val node: RenderModelNode,
    val material: Material,
) : BaseAttachment(node) {
    val matrix: Mat4f get() = globalMatrix
    val morphWeights: FloatArray get() = node.sceneObject.morphWeights
    internal var replaced = false
    val isVisible: Boolean get() = node.isVisible && !replaced

    private var skinPose: SkinPose? = null

    fun skinMatrices(): Array<Mat4f> {
        val pose = skinPose ?: checkNotNull(node.definition.skin).pose().also { skinPose = it }
        return pose.compute(globalMatrix, node.modelPoseNodes)
    }

    /** Only a skinned or morphed primitive draws itself; the model draws its static ones together. */
    override fun collectCommands(pipeline: RenderPipeline) {
        if (primitive.isDynamic) primitive.setupPipeline(pipeline, this)
    }
}
