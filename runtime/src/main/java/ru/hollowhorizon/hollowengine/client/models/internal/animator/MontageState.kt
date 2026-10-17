package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurve
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator as evaluator

/**
 * What a montage poses at one moment of it, whoever plays it.
 */
object MontageSampler {
    fun sample(montage: Montage, time: Float, target: PoseTarget, allowed: Set<Int>): AnimationPose {
        val pose = AnimationPose()
        montage.clips.sortedBy(MontageClip::start).forEach { clip ->
            val weight = weight(clip, time)
            if (weight <= 0f) return@forEach
            val animation = target.animations[clip.animation] ?: return@forEach
            val bones = if (clip.mask == FULL) allowed else allowed intersect target.mask(clip.mask)
            AnimationPose.sample(
                animation,
                clipTime(clip, time, animation.duration),
                bones
            ).entries.forEach { (node, bone) ->
                cover(pose[node] ?: pose.bone(node).also { it.cover = 0f }, bone, weight)
            }
        }
        montage.bones.forEach { track ->
            if (track.property == MontageBoneProperty.VISIBLE) return@forEach
            val node = target.node(track.bone)?.definition?.index?.takeIf { it in allowed } ?: return@forEach
            val bone = pose[node] ?: pose.bone(node).also { it.cover = 1f }
            val value = track.valueAt(time)
            when (track.property) {
                MontageBoneProperty.POSITION -> bone.translation = (bone.translation ?: Vec3f.ZERO) + value
                MontageBoneProperty.ROTATION -> bone.rotation =
                    MutableQuatF(bone.rotation ?: QuatF.IDENTITY).mul(eulerRotationXyz(value)).norm()

                MontageBoneProperty.SCALE -> bone.scale = (bone.scale ?: Vec3f.ONES) * value
                MontageBoneProperty.VISIBLE -> Unit
            }
        }
        return pose
    }

    /**
     * Lays [over] onto [under] by [weight]. Whatever lies beneath both shows through `1 - cover` of the bone,
     * so the two keep their shares of the rest: the bone stays `under` laid at its cover, then `over` at [weight].
     */
    private fun cover(under: BonePose, over: BonePose, weight: Float) {
        val covered = 1f - (1f - under.cover) * (1f - weight)
        val share = if (covered <= 0f) 0f else weight / covered
        under.translation = over.translation?.let { top -> under.translation?.let { Vec3f(it).mix(top, share) } ?: top }
            ?: under.translation
        under.rotation =
            over.rotation?.let { top -> under.rotation?.let { QuatF(it).mix(top, share) } ?: top } ?: under.rotation
        under.scale = over.scale?.let { top -> under.scale?.let { Vec3f(it).mix(top, share) } ?: top } ?: under.scale
        under.weights = over.weights?.let { top ->
            val below = under.weights ?: return@let top
            FloatArray(maxOf(below.size, top.size)) { i ->
                val a = below.getOrElse(i) { 0f }
                a + (top.getOrElse(i) { 0f } - a) * share
            }
        } ?: under.weights
        under.cover = covered
    }

    /**
     * How much [clip] counts at [time]: nothing outside it, ramping over its fades at either end, and as much
     * as its weight curve says in between.
     */
    fun weight(clip: MontageClip, time: Float): Float {
        if (time < clip.start || time > clip.end) return 0f
        val fadeIn = if (clip.fadeIn <= 0f) 1f else ((time - clip.start) / clip.fadeIn).coerceIn(0f, 1f)
        val fadeOut = if (clip.fadeOut <= 0f) 1f else ((clip.end - time) / clip.fadeOut).coerceIn(0f, 1f)
        val curve = if (clip.weight.isEmpty()) 1f else VfxCurve(clip.weight).valueAt(time - clip.start).coerceIn(0f, 1f)
        return minOf(fadeIn, fadeOut) * curve
    }

    /** Where in its own animation, [duration] seconds long, [clip] is at montage [time]. */
    fun clipTime(clip: MontageClip, time: Float, duration: Float): Float {
        if (duration <= 0f) return 0f
        val played = clip.from + (time - clip.start) * clip.speed
        return if (clip.loop) (played % duration + duration) % duration else played.coerceIn(0f, duration)
    }

    private val FULL = BoneMask.full()
}

/**
 * Plays a montage once over the layers below it, by the game time since its start; one without a start,
 * as in a preview, by the animator's frame step.
 */
class MontageLayer(montage: MontageLayerSpec) : SpecLayer(montage) {
    private val layer: MontageLayerSpec get() = spec as MontageLayerSpec

    override var time: Float = 0f
        private set
    override var finished: Boolean = false
        private set

    /** Another start time is another playback, so it gets a fresh layer rather than this one's position. */
    override fun accepts(spec: AnimatorLayerSpec): Boolean =
        spec is MontageLayerSpec && spec.startGameTime == layer.startGameTime && spec.montage == layer.montage

    override fun nextAge(context: AnimatorEvaluationContext): Float =
        layer.startGameTime?.let { context.secondsSince(it).coerceAtLeast(0f) } ?: super.nextAge(context)

    override fun sample(target: PoseTarget, context: AnimatorEvaluationContext): LayerPose? {
        val montage = MontageAssets.of(layer.montage, context.isClientSide) ?: return null
        val speed = layer.speed.coerceAtLeast(MIN_SPEED)
        val played = layer.played(montage, context.gameTicks, context.partialTick, { evaluator.condition(it, context) })
            ?.time ?: (age * speed)
        time = played.coerceIn(0f, montage.duration)

        val fadeOut = fadeOutScale(context, (played - montage.duration) / speed)
        if (fadeOut <= 0f) {
            finished = true
            return null
        }
        context.montages += MontageMoment(montage, time, (weight * fadeOut).coerceIn(0f, 1f))
        return LayerPose(MontageSampler.sample(montage, time, target, mask(target)), weightScale = fadeOut)
    }

    /** The weight this frame, kept for how much the montage's fields ease in over what the rig authors. */
    private var weight = 0f

    override fun weight(context: AnimatorEvaluationContext): Float = super.weight(context).also { weight = it }

    /** What is left of the layer [over] seconds after the montage played through, or after it was stopped. */
    private fun fadeOutScale(context: AnimatorEvaluationContext, over: Float): Float {
        val since = layer.stopAtGameTime?.let(context::secondsSince) ?: over
        if (since <= 0f) return 1f
        if (layer.fadeOut <= 0f) return 0f
        return (1f - since / layer.fadeOut).coerceIn(0f, 1f)
    }

    private companion object {
        const val MIN_SPEED = 0.01f
    }
}

/** Plays a montage while the controller stays in the state. */
class MontageState(private var spec: MontageStateSpec) : AnimationState {
    private val playback = ClipPlayback()

    /** Where a montage with rules in its sections is, played forward through them rather than by [playback]. */
    private var sectioned = 0f
    private var entering = true

    override var time: Float = 0f
        private set

    override fun enter(from: AnimationPose?) {
        playback.reset()
        sectioned = 0f
        entering = true
        time = 0f
    }

    override fun reconfigure(spec: AnimationControllerStateSpec): Boolean {
        this.spec = spec as? MontageStateSpec ?: return false
        return true
    }

    override fun sample(target: PoseTarget, allowed: Set<Int>, context: AnimatorEvaluationContext): AnimationPose? {
        val montage = MontageAssets.of(spec.montage, context.isClientSide) ?: return null
        context.stateTime = time
        val speed = evaluator.float(spec.speed, context, 1f)
        val time = if (montage.hasRules) throughSections(montage, speed, context)
        else playback.advance(montage.duration, spec.playMode, speed, context.deltaTime)
        this.time = if (montage.hasRules) time else playback.time
        context.montages += MontageMoment(montage, time, 1f)
        return MontageSampler.sample(montage, time, target, allowed)
            .also { pose -> pose.entries.forEach { it.value.cover = 1f } }
    }

    /**
     * Plays forward through the sections, starting over at the end when the state loops and holding the last frame
     * otherwise; sections send the playhead where they will, so it never plays backwards.
     */
    private fun throughSections(montage: Montage, speed: Float, context: AnimatorEvaluationContext): Float {
        val condition = { source: String -> evaluator.condition(source, context) }
        val by = (context.deltaTime * speed).coerceAtLeast(0f)
        var at = montage.advance(sectioned, by, entering, condition).time
        entering = false
        val duration = montage.duration
        if (duration > 0f && at >= duration) {
            at = if (spec.playMode == AnimationPlayMode.Loop) {
                montage.advance(0f, (at - duration).coerceAtMost(duration), true, condition).time.coerceAtMost(duration)
            } else duration
        }
        sectioned = at
        return at
    }
}
