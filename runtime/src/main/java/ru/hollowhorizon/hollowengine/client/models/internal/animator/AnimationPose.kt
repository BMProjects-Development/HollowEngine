package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.utils.math.*

class AnimationPose {
    private val bones = linkedMapOf<Int, BonePose>()

    val entries: Collection<Map.Entry<Int, BonePose>> get() = bones.entries
    val isEmpty: Boolean get() = bones.isEmpty()

    fun bone(index: Int): BonePose = bones.getOrPut(index) { BonePose(index) }

    operator fun get(index: Int): BonePose? = bones[index]

    fun clear() {
        bones.clear()
    }

    companion object {
        fun sample(animation: AnimationClip, time: Float, allowedNodes: Set<Int>? = null): AnimationPose {
            val pose = AnimationPose()
            animation.nodes.forEach { (node, channels) ->
                if (allowedNodes != null && node !in allowedNodes) return@forEach
                val bone = pose.bone(node)
                channels.translation?.let { bone.translation = Vec3f(it.compute(time)) }
                channels.rotation?.let { bone.rotation = QuatF(it.compute(time)) }
                channels.scale?.let { bone.scale = Vec3f(it.compute(time)) }
                channels.weights?.let { bone.weights = it.compute(time).copyOf() }
            }
            return pose
        }

        fun mix(first: AnimationPose, second: AnimationPose, factor: Float): AnimationPose {
            val result = AnimationPose()
            val clamped = factor.coerceIn(0f, 1f)
            val nodeIds = first.entries.map { it.key }.toMutableSet().also { ids ->
                ids += second.entries.map { it.key }
            }

            nodeIds.forEach { node ->
                val a = first[node]
                val b = second[node]
                val mixed = result.bone(node)

                mixed.translation = when {
                    a?.translation != null && b?.translation != null -> Vec3f(a.translation!!).mix(
                        b.translation!!,
                        clamped
                    )

                    b?.translation != null -> Vec3f.ZERO.mix(b.translation!!, clamped)
                    a?.translation != null -> Vec3f(a.translation!!).mix(Vec3f.ZERO, clamped)
                    else -> null
                }
                mixed.rotation = when {
                    a?.rotation != null && b?.rotation != null -> QuatF(a.rotation!!).mix(b.rotation!!, clamped)
                    b?.rotation != null -> QuatF.IDENTITY.mix(b.rotation!!, clamped)
                    a?.rotation != null -> QuatF(a.rotation!!).mix(QuatF.IDENTITY, clamped)
                    else -> null
                }
                mixed.scale = when {
                    a?.scale != null && b?.scale != null -> Vec3f(a.scale!!).mix(b.scale!!, clamped)
                    b?.scale != null -> Vec3f.ONES.mix(b.scale!!, clamped)
                    a?.scale != null -> Vec3f(a.scale!!).mix(Vec3f.ONES, clamped)
                    else -> null
                }
                mixed.weights = mixWeights(a?.weights, b?.weights, clamped)
            }

            return result
        }
    }
}

class BonePose(val node: Int) {
    var translation: Vec3f? = null
    var rotation: QuatF? = null
    var scale: Vec3f? = null
    var weights: FloatArray? = null
}

fun applyAnimationPose(
    pose: AnimationPose,
    nodes: Map<Int, RuntimeNode>,
    blendMode: LayerBlendMode,
    weight: Float,
    referencePose: AnimationPose? = null,
) {
    if (pose.isEmpty || weight <= 0f) return
    val clampedWeight = weight.coerceIn(0f, 1f)

    pose.entries.forEach { (nodeIndex, bonePose) ->
        val runtimeNode = nodes[nodeIndex] ?: return@forEach
        val transform = runtimeNode.transform
        val base = runtimeNode.definition.baseTransform
        val reference = referencePose?.get(nodeIndex)

        when (blendMode) {
            LayerBlendMode.Override -> transform.applyOverride(bonePose, base, clampedWeight)
            LayerBlendMode.Additive -> transform.applyAdditive(bonePose, reference, clampedWeight)
        }
        transform.markDirty()

        bonePose.weights?.let { weights ->
            blendWeights(
                current = runtimeNode.morphWeights,
                sampled = weights,
                reference = reference?.weights ?: runtimeNode.baseMorphWeights,
                blendMode = blendMode,
                weight = clampedWeight,
            )
        }
    }
}

private fun TrsTransformF.applyOverride(pose: BonePose, base: TrsTransformF, weight: Float) {
    pose.translation?.let { sampled ->
        val target = Vec3f(base.translation).add(sampled, MutableVec3f())
        translation.set(Vec3f(translation).mix(target, weight))
    }

    pose.rotation?.let { sampled ->
        val target = MutableQuatF(base.rotation).mul(sampled).norm()
        rotation.set(QuatF(rotation).mix(target, weight))
    }

    pose.scale?.let { sampled ->
        val target = Vec3f(base.scale).mul(sampled, MutableVec3f())
        scale.set(Vec3f(scale).mix(target, weight))
    }
}

private fun TrsTransformF.applyAdditive(
    pose: BonePose,
    reference: BonePose?,
    weight: Float,
) {
    pose.translation?.let { sampled ->
        val referenceTranslation = reference?.translation ?: Vec3f.ZERO
        val delta = Vec3f.ZERO.mix(sampled - referenceTranslation, weight)
        translation.set(Vec3f(translation).add(delta, MutableVec3f()))
    }

    pose.rotation?.let { sampled ->
        val referenceRotation = reference?.rotation ?: QuatF.IDENTITY
        val delta = QuatF.IDENTITY.mix(MutableQuatF(referenceRotation).invert().mul(sampled), weight)
        rotation.set(MutableQuatF(rotation).mul(delta).norm())
    }

    pose.scale?.let { sampled ->
        val referenceScale = reference?.scale ?: Vec3f.ONES
        val delta = Vec3f.ONES.mix(sampled / referenceScale, weight)
        scale.set(Vec3f(scale).mul(delta, MutableVec3f()))
    }
}

private fun blendWeights(
    current: FloatArray,
    sampled: FloatArray,
    reference: FloatArray,
    blendMode: LayerBlendMode,
    weight: Float,
) {
    val count = minOf(current.size, sampled.size)
    for (index in 0 until count) {
        current[index] = when (blendMode) {
            LayerBlendMode.Override -> current[index] + (sampled[index] - current[index]) * weight
            LayerBlendMode.Additive -> current[index] + (sampled[index] - reference.getOrElse(index) { 0f }) * weight
        }
    }
}

private fun mixWeights(first: FloatArray?, second: FloatArray?, factor: Float): FloatArray? {
    if (first == null && second == null) return null
    val size = maxOf(first?.size ?: 0, second?.size ?: 0)
    return FloatArray(size) { index ->
        val a = first?.getOrNull(index) ?: 0f
        val b = second?.getOrNull(index) ?: 0f
        a + (b - a) * factor
    }
}
