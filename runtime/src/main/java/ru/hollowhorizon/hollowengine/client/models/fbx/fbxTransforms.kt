package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg

internal data class FbxTransformChain(
    val first: NodeDefinition,
    val last: NodeDefinition,
    val targets: Map<String, Int>,
)

internal fun buildTransformChain(model: Model, index: Int, allocate: () -> Int): FbxTransformChain {
    val targets = mutableMapOf<String, Int>()
    val nodes = mutableListOf<NodeDefinition>()
    fun addTransform(transform: TrsTransformF, property: String? = null) {
        val id = allocate()
        nodes += NodeDefinition(id, null, mutableListOf(), transform)
        if (property != null) targets[property] = id
    }
    fun add(matrix: Mat4f, property: String? = null) = addTransform(TrsTransformF().setMatrix(matrix), property)
    fun translation(value: Vec3f) = Mat4f.translation(value)
    fun inverse(value: Vec3f) = Vec3f(-value.x, -value.y, -value.z)
    fun offset(value: Vec3f) { if (value.sqrLength() != 0f) add(translation(value)) }
    add(translation(model.props("Lcl Translation", Vec3f.ZERO)), "Lcl Translation")
    offset(model.rotationOffset)
    offset(model.rotationPivot)
    if (model.preRotation.sqrLength() != 0f) add(getRotationMatrix(Model.RotOrder.EulerXYZ, model.preRotation))
    add(getRotationMatrix(model.rotationOrder, model.props("Lcl Rotation", Vec3f.ZERO)), "Lcl Rotation")
    if (model.postRotation.sqrLength() != 0f) {
        val post = MutableMat4f(getRotationMatrix(Model.RotOrder.EulerXYZ, model.postRotation))
        post.invert()
        add(post)
    }
    offset(inverse(model.rotationPivot))
    offset(model.scalingOffset)
    offset(model.scalingPivot)
    addTransform(TrsTransformF().scale(model.props("Lcl Scaling", Vec3f.ONES)), "Lcl Scaling")
    offset(inverse(model.scalingPivot))
    nodes += NodeDefinition(index, model.name.substringAfter("::"), mutableListOf(), TrsTransformF())
    nodes.zipWithNext { parent, child -> parent.children += child; child.parent = parent }
    return FbxTransformChain(nodes.first(), nodes.last(), targets)
}

internal fun geometricTransform(model: Model): TrsTransformF = TrsTransformF().apply {
    translate(model.geometricTranslation)
    rotate(getRotationMatrix(Model.RotOrder.EulerXYZ, model.geometricRotation).getRotation(MutableQuatF()))
    scale(model.geometricScaling)
}

fun getRotationMatrix(mode: Model.RotOrder, rotation: Vec3f): Mat4f {
    val order = when (mode) {
        Model.RotOrder.EulerXYZ -> intArrayOf(2, 1, 0)
        Model.RotOrder.EulerXZY -> intArrayOf(1, 2, 0)
        Model.RotOrder.EulerYZX -> intArrayOf(0, 2, 1)
        Model.RotOrder.EulerYXZ -> intArrayOf(2, 0, 1)
        Model.RotOrder.EulerZXY -> intArrayOf(1, 0, 2)
        Model.RotOrder.EulerZYX -> intArrayOf(0, 1, 2)
        Model.RotOrder.SphericXYZ -> domError("spherical Euler rotation is unsupported")
    }
    val angles = floatArrayOf(rotation.x, rotation.y, rotation.z)
    val axes = arrayOf(Vec3f.X_AXIS, Vec3f.Y_AXIS, Vec3f.Z_AXIS)
    val matrix = MutableMat4f()
    order.forEach { matrix.rotate(angles[it].deg, axes[it]) }
    return matrix
}
