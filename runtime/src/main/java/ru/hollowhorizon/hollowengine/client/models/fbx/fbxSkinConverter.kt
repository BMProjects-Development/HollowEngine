package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.*
import ru.hollowhorizon.hollowengine.client.models.internal.Skin as InternalSkin

internal fun convertSkin(skin: Skin, nodeIds: Map<Long, Int>, geometryIndex: Int, geometric: Mat4f): InternalSkin {
    if (skin.clusters.isEmpty()) domError("skin has no clusters", skin.element)
    val ids = skin.clusters.map { cluster ->
        nodeIds[cluster.node?.id] ?: domError("skin joint is missing from the model hierarchy", cluster.element)
    }
    val matrices = skin.clusters.map { cluster ->
        MutableMat4f(cluster.meshToJoint).mul(geometric) as Mat4f
    }.toTypedArray()
    return InternalSkin(ids + geometryIndex, matrices + Mat4f.IDENTITY)
}

internal fun skinData(mesh: MeshGeometry, skin: InternalSkin?): Pair<Array<Vec4i>?, Array<Vec4f>?> {
    val source = mesh.skin ?: return null to null
    if (skin == null) domError("missing converted skin", mesh.element)
    val influences = Array(mesh.controlPoints.size) { mutableMapOf<Int, Float>() }
    source.clusters.forEachIndexed { joint, cluster ->
        cluster.indices.forEachIndexed { index, point ->
            if (point !in influences.indices) domError("skin control point $point is out of range", cluster.element)
            val weight = cluster.weights[index]
            if (!weight.isFinite() || weight < 0f) domError("invalid skin weight", cluster.element)
            if (weight > 0f) influences[point].merge(joint, weight, Float::plus)
        }
    }
    val modes = source.clusters.map { it.linkMode }.distinct()
    if (modes.size > 1) domError("mixed cluster link modes in one skin", source.element)
    if (modes.singleOrNull() == "TotalOne") influences.forEach { values ->
        val total = values.values.sum()
        if (total > 1f + 1e-5f) domError("TotalOne skin weights exceed one", source.element)
        if (total < 1f) values[skin.jointsIds.lastIndex] = 1f - total
    }
    val pointJoints = Array(influences.size) { Vec4i.ZERO }
    val pointWeights = Array(influences.size) { Vec4f.ZERO }
    influences.forEachIndexed { point, values ->
        val strongest =
            values.entries.sortedByDescending { it.value }.take(if (source.skinningType == "Rigid") 1 else 4)
        val total = strongest.sumOf { it.value.toDouble() }
        if (total == 0.0) {
            pointJoints[point] = Vec4i(skin.jointsIds.lastIndex, 0, 0, 0)
            pointWeights[point] = Vec4f(1f, 0f, 0f, 0f)
            return@forEachIndexed
        }
        val j = IntArray(4)
        val w = FloatArray(4)
        strongest.forEachIndexed { index, influence ->
            j[index] = influence.key; w[index] = (influence.value / total).toFloat()
        }
        pointJoints[point] = Vec4i(j[0], j[1], j[2], j[3])
        pointWeights[point] = Vec4f(w[0], w[1], w[2], w[3])
    }
    return Array(mesh.vertices.size) { pointJoints[mesh.vertexControlPoints[it]] } to Array(mesh.vertices.size) { pointWeights[mesh.vertexControlPoints[it]] }
}

private fun MeshGeometry.shapes() = blendShapes.flatMap { it.channels }.flatMap { it.shapes }

internal fun morphTargets(mesh: MeshGeometry): List<Map<String, FloatArray>> = mesh.shapes().map { shape ->
    if (shape.indices.isEmpty() && shape.vertices.size != mesh.controlPoints.size) {
        domError("sparse morph target has no control point indices", shape.element)
    }
    val pointIndices = shape.indices.takeIf { it.isNotEmpty() } ?: shape.vertices.indices.toList()
    if (pointIndices.size != shape.vertices.size || (shape.normals.isNotEmpty() && shape.normals.size != pointIndices.size)) {
        domError("morph target array lengths do not match", shape.element)
    }
    val lookup = mutableMapOf<Int, Int>()
    pointIndices.forEachIndexed { index, point ->
        if (point !in mesh.controlPoints.indices || lookup.put(
                point,
                index
            ) != null
        ) domError("invalid morph control point $point", shape.element)
    }
    fun expand(values: List<Vec3f>): FloatArray {
        val result = FloatArray(mesh.vertices.size * 3)
        mesh.vertexControlPoints.forEachIndexed { vertex, point ->
            lookup[point]?.let { index ->
                val value = values[index]
                result[vertex * 3] = value.x
                result[vertex * 3 + 1] = value.y
                result[vertex * 3 + 2] = value.z
            }
        }
        return result
    }
    buildMap {
        put("POSITION", expand(shape.vertices))
        if (shape.normals.isNotEmpty()) put("NORMAL", expand(shape.normals))
    }
}

internal fun morphWeights(mesh: MeshGeometry, overrides: Map<Long, Float> = emptyMap()): FloatArray =
    mesh.blendShapes.flatMap { it.channels }.flatMap { channel ->
        val thresholds = channel.fullWeights.ifEmpty { List(channel.shapes.size) { 100f } }
        if (thresholds.size != channel.shapes.size || thresholds.any { it <= 0f } || thresholds.zipWithNext()
                .any { (a, b) -> a >= b }) {
            domError("invalid in-between shape weights", channel.element)
        }
        val result = MutableList(thresholds.size) { 0f }
        if (thresholds.isNotEmpty()) {
            val weight = overrides[channel.id] ?: channel.defaultWeight
            val upper = thresholds.indexOfFirst { it >= weight }.takeIf { it >= 0 } ?: thresholds.lastIndex
            val lowerWeight = if (upper > 0) thresholds[upper - 1] else 0f
            val fraction = (weight - lowerWeight) / (thresholds[upper] - lowerWeight)
            result[upper] = fraction
            if (upper > 0) result[upper - 1] = 1f - fraction
        }
        result
    }.toFloatArray()
