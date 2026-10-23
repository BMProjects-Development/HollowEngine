package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.max

/** The engine inherits TRS as RSrs. Other FBX modes coincide only for the scale cases checked here. */
internal fun validateTransformInheritance(document: Document, models: Collection<Model>, animated: List<AnimationCurveNode> = emptyList()) {
    val byId = models.associateBy { it.id }
    val scales = animated.filter { it.prop == "Lcl Scaling" }.associateBy { it.target?.id }
    for (model in models) {
        if (model.inheritType == Model.TransformInheritance.RSrs) continue
        var current = model
        val visited = mutableSetOf<Long>()
        while (visited.add(current.id)) {
            val parent = document.getConnectionsBySourceSequenced(current.id, "Model")
                .firstOrNull { it.prop.isEmpty() }?.dest?.let { byId[it] } ?: break
            val scale = parent.props("Lcl Scaling", Vec3f.ONES)
            val inherited = floatArrayOf(scale.x, scale.y, scale.z)
            val supported = if (model.inheritType == Model.TransformInheritance.Rrs) inherited.all { near(it, 1f) }
            else near(scale.x, scale.y) && near(scale.y, scale.z)
            if (!supported) domError("${model.inheritType} scale inheritance from ${parent.name} is unsupported", model.element)
            scales[parent.id]?.let { node ->
                val curves = listOf("d|X", "d|Y", "d|Z").map { node.curves()[it] }
                val identity = curves.withIndex().all { (axis, curve) -> curve?.values?.all { near(it, inherited[axis]) } != false }
                val uniform = curves.all { it != null } && curves.drop(1).all { curve ->
                    curve!!.keys == curves[0]!!.keys && curve.values == curves[0]!!.values &&
                        curve.flags == curves[0]!!.flags && curve.attributes == curves[0]!!.attributes
                }
                if (!identity && (model.inheritType == Model.TransformInheritance.Rrs || !uniform)) {
                    domError("animated ${model.inheritType} scale inheritance from ${parent.name} is unsupported", model.element)
                }
            }
            current = parent
        }
    }
}

private fun near(a: Float, b: Float) = abs(a - b) <= 1e-5f * max(1f, max(abs(a), abs(b)))
