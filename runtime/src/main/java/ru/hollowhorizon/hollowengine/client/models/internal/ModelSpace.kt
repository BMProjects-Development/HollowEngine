package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg

/**
 * The space a loaded model lives in: Y up, one unit per block, front facing -Z, like in vanilla models.
 */
object ModelSpace {
    /**
     * Index of the node a loader adds to hold the correction;
     */
    const val ROOT_INDEX = -1

    /**
     * Brings [nodes] into model space.
     *
     * [facesPositiveZ] is true for anything exported from a 3D editor: glTF puts a model's front at +Z
     * and FBX and OBJ follow it. BlockBench and Bedrock geometry is already authored the way a vanilla
     * model is. [unitsPerBlock] is what the source measures in 16 for Bedrock, 100 for BlockBench's
     * FBX export.
     */
    fun place(
        nodes: List<NodeDefinition>,
        facesPositiveZ: Boolean,
        unitsPerBlock: Float = 1f,
        index: Int = ROOT_INDEX,
    ): List<NodeDefinition> {
        if (nodes.isEmpty()) return nodes
        if (!facesPositiveZ && unitsPerBlock == 1f) return nodes

        val transform = TrsTransformF()
        if (facesPositiveZ) transform.rotate(180f.deg, Vec3f.Y_AXIS)
        if (unitsPerBlock != 1f) transform.scale(Vec3f(1f / unitsPerBlock, 1f / unitsPerBlock, 1f / unitsPerBlock))

        val root = NodeDefinition(
            index = index,
            children = nodes.toMutableList(),
            transform = transform,
        ).also { it.isSynthetic = true }
        nodes.forEach { it.parent = root }
        return listOf(root)
    }
}

fun hostYawDegrees(yaw: Float): Float = 180f - yaw
