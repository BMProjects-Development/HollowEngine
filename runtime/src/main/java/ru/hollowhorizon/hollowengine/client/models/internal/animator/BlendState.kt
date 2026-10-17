package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.common.models.AnimationControllerStateSpec
import ru.hollowhorizon.hollowengine.common.models.BlendMotion
import ru.hollowhorizon.hollowengine.common.models.BlendStateSpec
import ru.hollowhorizon.hollowengine.common.models.BlendWeights
import kotlin.math.abs
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator as evaluator

/**
 * Plays the clips of a [BlendStateSpec] together, weighted by where its parameters are this frame.
 */
class BlendState(private var spec: BlendStateSpec) : AnimationState {
    private val playback = ClipPlayback()

    /** Seconds one cycle took on the last frame. */
    private var cycle = 0f

    override val time: Float get() = playback.time * cycle

    override fun enter(from: AnimationPose?) = playback.reset()

    override fun reconfigure(spec: AnimationControllerStateSpec): Boolean {
        this.spec = spec as? BlendStateSpec ?: return false
        return true
    }

    override fun sample(target: PoseTarget, allowed: Set<Int>, context: AnimatorEvaluationContext): AnimationPose? {
        val clips = spec.motions.map { target.animations[it.animation] }
        val x = evaluator.float(spec.x, context, 0f)
        val y = spec.y?.let { evaluator.float(it, context, 0f) } ?: 0f
        val weights = BlendWeights.of(spec, x, y)

        clips.forEachIndexed { i, clip -> if (clip == null) weights[i] = 0f }
        val total = weights.sum()
        if (total <= 0f) return null

        cycle = clips.indices.sumOf { i ->
            val clip = clips[i] ?: return@sumOf 0.0
            (clip.duration / ownSpeed(spec.motions[i]) * weights[i] / total).toDouble()
        }.toFloat()
        context.stateTime = time
        val speed = evaluator.float(spec.speed, context, 1f)
        val phase = if (cycle <= 0f) 0f else playback.advance(1f, spec.playMode, speed / cycle, context.deltaTime)

        val poses = ArrayList<AnimationPose>(clips.size)
        val used = ArrayList<Float>(clips.size)
        clips.forEachIndexed { i, clip ->
            if (clip == null || weights[i] < MIN_WEIGHT) return@forEachIndexed
            val along = if (spec.motions[i].speed < 0f) 1f - phase else phase
            poses += AnimationPose.sample(clip, along * clip.duration, allowed)
            used += weights[i]
        }
        return AnimationPose.blend(poses, used)
    }

    private companion object {
        /** Below this a clip is not worth sampling. */
        const val MIN_WEIGHT = 0.001f

        /** Slowest a clip is let play, so one set to zero stretches the cycle rather than stopping it forever. */
        const val MIN_SPEED = 0.01f

        fun ownSpeed(motion: BlendMotion): Float = abs(motion.speed).coerceAtLeast(MIN_SPEED)
    }
}
