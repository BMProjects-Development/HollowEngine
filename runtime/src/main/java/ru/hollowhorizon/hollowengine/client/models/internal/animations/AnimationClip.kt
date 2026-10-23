package ru.hollowhorizon.hollowengine.client.models.internal.animations

import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f

class AnimationClip(
    val name: String,
    val nodes: Map<Int, AnimationData>,
    val duration: Float = nodes.values.maxOfOrNull { it.duration } ?: 0f,
    val poseTransform: ((AnimationPose) -> Unit)? = null,
) {
    override fun toString() = name
}

fun Vec3f.array(): FloatArray = floatArrayOf(x, y, z)

fun QuatF.array(): FloatArray = floatArrayOf(x, y, z, w)

fun Vec4f.array(): FloatArray = floatArrayOf(x, y, z, w)

class AnimationData(
    var translation: Interpolator<Vec3f>?,
    var rotation: Interpolator<QuatF>?,
    var scale: Interpolator<Vec3f>?,
    var weights: Interpolator<FloatArray>?,
) {
    val duration by lazy {
        maxOf(
            translation?.duration ?: 0f,
            rotation?.duration ?: 0f,
            scale?.duration ?: 0f,
            weights?.duration ?: 0f,
        )
    }
}

enum class AnimationTarget { TRANSLATION, ROTATION, SCALE, WEIGHTS }
