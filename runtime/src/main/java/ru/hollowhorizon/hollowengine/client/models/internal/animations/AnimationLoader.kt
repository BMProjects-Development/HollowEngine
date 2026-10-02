package ru.hollowhorizon.hollowengine.client.models.internal.animations

import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.toQuatF
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import ru.hollowhorizon.hollowengine.client.models.internal.ChannelData
import ru.hollowhorizon.hollowengine.client.models.internal.ImportedAnimation
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.*


object AnimationLoader {

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun createAnimation(
        nodes: Map<Int, NodeDefinition>,
        animationModel: ImportedAnimation,
    ): AnimationClip {
        val animData = animationModel.channels
            .map { channel ->
                val node = nodes[channel.node]
                    ?: throw IllegalStateException("Node with index ${channel.node} not found!")

                val timeKeys = channel.times.toFloatArray()
                val target = AnimationTarget.valueOf(channel.path.uppercase())

                val size = if (target == AnimationTarget.WEIGHTS) node.mesh?.weights?.size ?: 0
                else -1

                node.index to (target to readAnimationData(
                    node, channel.interpolation,
                    target, channel.values,
                    timeKeys, size
                ))
            }

        val result = Object2ObjectOpenHashMap<Int, AnimationData>()

        animData.forEach { (key, pair) ->
            val (target, interpolator) = pair
            val data = result.computeIfAbsent(key) { AnimationData(null, null, null, null) }
            when (target) {
                AnimationTarget.TRANSLATION -> data.translation = interpolator as Interpolator<Vec3f>
                AnimationTarget.ROTATION -> data.rotation = interpolator as Interpolator<QuatF>
                AnimationTarget.SCALE -> data.scale = interpolator as Interpolator<Vec3f>
                AnimationTarget.WEIGHTS -> data.weights = interpolator as Interpolator<FloatArray>
            }
        }

        return AnimationClip(animationModel.name ?: "Unnamed", result)
    }

    private fun readAnimationData(
        node: NodeDefinition,
        interpolation: String,
        target: AnimationTarget,
        outputData: ChannelData,
        timeKeys: FloatArray,
        componentCount: Int = -1,
    ): Interpolator<*> {
        return when (interpolation) {
            "STEP" -> loadStep(node, outputData, timeKeys, target, componentCount)
            "LINEAR" -> loadLinear(node, outputData, timeKeys, target, componentCount)
            else -> throw UnsupportedOperationException("Animation type $interpolation not supported yet!")
        }
    }

    private fun loadStep(
        node: NodeDefinition,
        outputData: ChannelData,
        keys: FloatArray,
        target: AnimationTarget,
        componentCount: Int = -1,
    ): Interpolator<*> {
        return when (target) {
            AnimationTarget.TRANSLATION -> Vec3Step(
                keys,
                outputData.asVec3f().map { it - node.baseTransform.translation }.toTypedArray()
            )

            AnimationTarget.ROTATION -> QuatStep(
                keys,
                outputData.asVec4f().map {
                    MutableQuatF(node.baseTransform.rotation).inverted().mul(it.toQuatF())
                }.toTypedArray()
            )

            AnimationTarget.SCALE -> Vec3Step(
                keys,
                outputData.asVec3f().map { it / node.baseTransform.scale }.toTypedArray()
            )

            AnimationTarget.WEIGHTS -> FloatsStep(
                keys,
                splitListByN(outputData.asFloats(), componentCount).toTypedArray()
            )
        }
    }

    private fun loadLinear(
        node: NodeDefinition,
        outputData: ChannelData,
        keys: FloatArray,
        target: AnimationTarget,
        componentCount: Int = -1,
    ): Interpolator<*> {
        return when (target) {
            AnimationTarget.TRANSLATION -> Linear(
                keys,
                outputData.asVec3f().map { it - node.baseTransform.translation }.toTypedArray()
            )

            AnimationTarget.ROTATION -> SphericalLinear(
                keys,
                outputData.asVec4f().map {
                    MutableQuatF(node.baseTransform.rotation).inverted().mul(it.toQuatF())
                }.toTypedArray()
            )

            AnimationTarget.SCALE -> Linear(
                keys,
                outputData.asVec3f().map { it / node.baseTransform.scale }.toTypedArray()
            )

            AnimationTarget.WEIGHTS -> LinearSingle(
                keys,
                splitListByN(outputData.asFloats(), componentCount).toTypedArray()
            )
        }
    }
}

fun splitListByN(list: List<Float>, n: Int): List<FloatArray> {
    if (n < 1) return listOf(list.toFloatArray())

    val result = mutableListOf<FloatArray>()
    var startIndex = 0
    while (startIndex < list.size) {
        val endIndex = kotlin.math.min(startIndex + n, list.size)
        val subList = list.subList(startIndex, endIndex).toFloatArray()
        result.add(subList)
        startIndex = endIndex
    }
    return result
}

