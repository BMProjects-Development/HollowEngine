package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f

/**
 * Defines the shape and appearance of an object attached to a node.
 */
class MeshAttachment(
    val primitive: Primitive,
    private val node: RuntimeNode,
    val material: Material,
) : Attachment(node) {
    val matrix: Mat4f get() = globalMatrix
    val morphWeights: FloatArray get() = node.morphWeights
    val isVisible: Boolean get() = node.isVisible

    fun skinMatrices(): Array<Mat4f> =
        node.definition.skin!!.compute(globalMatrix, node.jointGetter)

    override fun collectCommands(pipeline: RenderPipeline) {
        primitive.setupPipeline(pipeline, this)
    }
}
