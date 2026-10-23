package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.client.models.internal.FloatChannelData
import ru.hollowhorizon.hollowengine.client.models.internal.Channel
import ru.hollowhorizon.hollowengine.client.models.internal.ImportedAnimation
import ru.hollowhorizon.hollowengine.client.models.internal.QuatfChannelData
import ru.hollowhorizon.hollowengine.client.models.internal.Vec3fChannelData
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

internal const val FBX_TIME_UNITS_PER_SECOND = 46186158000.0

internal fun convertAnimationStack(stack: AnimationStack, converter: FbxConverter): ImportedAnimation? {
    val layers = stack.layers.filter { !it.props("Mute", false) }
    if (layers.any { it.props("Weight", 100f) != 100f }) domError("animation layer weighting is unsupported", stack.element)
    val nodes = layers.flatMap { it.nodes() }
    validateTransformInheritance(converter.document, converter.models.values, nodes)
    val sourceTimes = nodes.flatMap { it.curves().values }.flatMap { it.keys }
    if (sourceTimes.isEmpty()) return null
    val start = if (stack.localStop > stack.localStart) stack.localStart else sourceTimes.min()
    val stop = if (stack.localStop > stack.localStart) stack.localStop else sourceTimes.max()
    val channels = mutableListOf<Channel>()
    val seen = mutableSetOf<Pair<Long, String>>()
    for (node in nodes) {
        val model = node.targetAsModel ?: continue
        val path = when (node.prop) {
            "Lcl Translation" -> "translation"
            "Lcl Rotation" -> "rotation"
            "Lcl Scaling" -> "scale"
            else -> continue
        }
        if (!seen.add(model.id to path)) domError("multiple animation layers target the same property", node.element)
        val target = converter.animationTargets[model.id]?.get(node.prop) ?: continue
        val curves = listOf("d|X", "d|Y", "d|Z").map { node.curves()[it] }
        val fallback = model.props(node.prop, if (path == "scale") Vec3f.ONES else Vec3f.ZERO)
        val defaults = floatArrayOf(
            node.props("d|X", fallback.x), node.props("d|Y", fallback.y), node.props("d|Z", fallback.z),
        )
        if (path == "scale" && listOf(fallback.x, fallback.y, fallback.z).any { it == 0f }) domError("animation of a zero base scale is unsupported", node.element)
        val samples = sampleCurves(curves, defaults, start, stop, model.rotationOrder.takeIf { path == "rotation" })
        val sampled = samples.values
        val data = if (path == "rotation") QuatfChannelData(sampled.map {
            val matrix = getRotationMatrix(model.rotationOrder, Vec3f(it[0], it[1], it[2]))
            matrix.getRotation(MutableQuatF())
        }.toTypedArray()) else Vec3fChannelData(sampled.map { Vec3f(it[0], it[1], it[2]) }.toTypedArray())
        channels += Channel(target, path, samples.times, "LINEAR", data)
    }
    val morphNodes = nodes.filter { it.target is BlendShapeChannel && it.prop == "DeformPercent" }
    for ((index, mesh) in converter.morphMeshes) {
        val shapeChannels = mesh.blendShapes.flatMap { it.channels }
        val animated = shapeChannels.map { channel -> morphNodes.filter { it.target?.id == channel.id } }
        if (animated.all { it.isEmpty() }) continue
        if (animated.any { it.size > 1 }) domError("multiple animation layers target the same morph channel", mesh.element)
        val curves = animated.map { entries ->
            entries.singleOrNull()?.curves()?.let { it["d|DeformPercent"] ?: it["d|X"] }
        }
        val defaults = shapeChannels.map { it.defaultWeight }.toFloatArray()
        val samples = sampleCurves(curves, defaults, start, stop) { sample ->
            morphWeights(mesh, shapeChannels.mapIndexed { i, channel -> channel.id to sample[i] }.toMap())
        }
        val values = samples.values.flatMap { it.toList() }
        channels += Channel(index, "weights", samples.times, "LINEAR", FloatChannelData(values))
    }
    return channels.takeIf { it.isNotEmpty() }?.let { ImportedAnimation(stack.name.substringAfter("::"), it) }
}
