package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator as evaluator
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.models.AnimatorLayerSpec
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.ProceduralLayerSpec

class PoseTarget(
    val nodesByIndex: Map<Int, RuntimeNode>,
    val animations: Map<String, AnimationClip>,
    val aliases: Map<String, String> = emptyMap(),
    val rig: ModelRig = ModelRig.EMPTY,
) {
    private val masks = HashMap<BoneMask, Set<Int>>()

    private val nodesByName: Map<String, RuntimeNode> by lazy {
        buildMap {
            nodesByIndex.values.forEach { node ->
                putIfAbsent(node.name, node)
                putIfAbsent(node.definition.path, node)
            }
            aliases.forEach { (alias, name) -> this[name]?.let { putIfAbsent(alias, it) } }
        }
    }

    fun node(name: String): RuntimeNode? = nodesByName[name]

    fun mask(mask: BoneMask): Set<Int> = masks.getOrPut(mask) {
        nodesByIndex.values.asSequence()
            .filter { node ->
                val name = node.name
                val path = node.definition.path
                (mask.includes.isEmpty() || mask.includes.any { it == name || path.endsWith(it) }) &&
                        mask.excludes.none { it == name || path.endsWith(it) }
            }
            .map { it.definition.index }
            .toSet()
    }

    fun apply(pose: AnimationPose, blendMode: LayerBlendMode, weight: Float, reference: AnimationPose? = null) {
        applyAnimationPose(pose, nodesByIndex, blendMode, weight, reference)
    }
}

/** What a layer contributes: a pose, an extra weight factor, and what to blend it against. */
class LayerPose(
    val pose: AnimationPose,
    val weightScale: Float = 1f,
    val reference: AnimationPose? = null,
)

/**
 * One contributor to a model's pose.
 *
 * Layers are stacked by [priority] and blended in.
 */
interface AnimationLayer {
    val id: String
    val priority: Int get() = 0
    val blendMode: LayerBlendMode get() = LayerBlendMode.Override

    /** True once the layer has nothing left to do and can be dropped, as a finished one-shot clip. */
    val finished: Boolean get() = false

    /** How strongly the layer contributes; zero skips it entirely, [sample] is not even called. */
    fun weight(context: AnimatorEvaluationContext): Float = 1f

    /** Null when the layer has nothing to contribute this frame. */
    fun sample(target: PoseTarget, context: AnimatorEvaluationContext): LayerPose?
}

/**
 * A layer described by the animator component: it takes its priority, weight, fade-in and mask from the
 * spec, and leaves only the sampling to the subclass.
 */
abstract class SpecLayer(initialSpec: AnimatorLayerSpec) : AnimationLayer {
    var spec: AnimatorLayerSpec = initialSpec
        private set

    override val id: String get() = spec.id
    override val priority: Int get() = spec.priority
    override val blendMode: LayerBlendMode get() = spec.blendMode

    /** Seconds this layer has existed, which is what the fade-in is measured against. */
    var age: Float = 0f
        private set

    /** Where the layer is inside its animation, for callers showing progress. */
    open val time: Float get() = 0f

    /**
     * Applies a new description without discarding playback owned by this layer.
     */
    fun reconfigure(next: AnimatorLayerSpec): Boolean {
        if (next.id != id || !accepts(next)) return false
        spec = next
        onReconfigured(next)
        return true
    }

    override fun weight(context: AnimatorEvaluationContext): Float {
        age = nextAge(context)
        context.layerAge = age
        context.layerTime = time
        val declared = evaluator.float(spec.weight, context).coerceIn(0f, 1f)
        context.layerWeight = declared
        val fadeIn = if (spec.fadeIn <= 0f) 1f else (age / spec.fadeIn).coerceIn(0f, 1f)
        return declared * fadeIn
    }

    /** The layer's age this frame: by default it grows by the frame's step. */
    protected open fun nextAge(context: AnimatorEvaluationContext): Float = age + context.deltaTime.coerceAtLeast(0f)

    protected abstract fun accepts(spec: AnimatorLayerSpec): Boolean

    protected open fun onReconfigured(spec: AnimatorLayerSpec) = Unit

    protected fun mask(target: PoseTarget): Set<Int> = target.mask(spec.mask)
}

/**
 * Plays one clip.
 *
 * A clip with a start time plays the game time elapsed since that tick, so a late first frame catches up
 * and a long stall does not lose time; a clip without one plays the animator's frame step.
 */
class ClipLayer(clip: ClipAnimationLayerSpec) : SpecLayer(clip) {
    private val playback = ClipPlayback()
    private val clip: ClipAnimationLayerSpec get() = spec as ClipAnimationLayerSpec

    /** Seconds since the start time that playback has already covered. */
    private var played = 0f

    override val time: Float get() = playback.time
    override var finished: Boolean = false
        private set

    /** Another start time is another playback, so it gets a fresh layer rather than this one's position. */
    override fun accepts(spec: AnimatorLayerSpec): Boolean =
        spec is ClipAnimationLayerSpec && spec.startGameTime == clip.startGameTime

    override fun nextAge(context: AnimatorEvaluationContext): Float =
        clip.startGameTime?.let { context.secondsSince(it).coerceAtLeast(0f) } ?: super.nextAge(context)

    override fun sample(target: PoseTarget, context: AnimatorEvaluationContext): LayerPose? {
        val animation = target.animations[clip.animation] ?: return null
        val speed = evaluator.float(clip.speed, context, 1f)
        val sampleTime = playback.advance(animation.duration, clip.playMode, speed, step(context))

        val fadeOut = fadeOutScale(context)
        if (fadeOut <= 0f && (clip.stopAtGameTime != null || (playback.ended && clip.removeOnEnd))) {
            finished = true
            return null
        }

        val allowed = mask(target)
        return LayerPose(
            pose = AnimationPose.sample(animation, sampleTime, allowed),
            weightScale = fadeOut,
            reference = clip.referencePose
                ?.let(target.animations::get)
                ?.let { AnimationPose.sample(it, 0f, allowed) },
        )
    }

    /**
     * Seconds to play this frame. With a start time that is what the clock moved past since the last frame,
     * and nothing while the start is still ahead or the clock steps back.
     */
    private fun step(context: AnimatorEvaluationContext): Float {
        val start = clip.startGameTime ?: return context.deltaTime
        val elapsed = context.secondsSince(start)
        if (elapsed <= played) return 0f
        return (elapsed - played).also { played = elapsed }
    }

    /**
     * How much of the clip is left this frame: a stop request fades from the game time it was asked at,
     * everything else fades only once a one-shot has played out.
     */
    private fun fadeOutScale(context: AnimatorEvaluationContext): Float {
        clip.stopAtGameTime?.let { stoppedAt ->
            if (clip.fadeOut <= 0f) return 0f
            val elapsed = context.secondsSince(stoppedAt)
            return (1f - elapsed / clip.fadeOut).coerceIn(0f, 1f)
        }
        return if (clip.playMode != AnimationPlayMode.Once || clip.fadeOut <= 0f || !playback.ended) 1f
        else (1f - playback.endElapsed / clip.fadeOut).coerceIn(0f, 1f)
    }
}

/** Poses named bones straight from expressions, without any clip behind them. */
class ProceduralLayer(procedural: ProceduralLayerSpec) : SpecLayer(procedural) {
    private val procedural: ProceduralLayerSpec get() = spec as ProceduralLayerSpec

    override fun accepts(spec: AnimatorLayerSpec): Boolean = spec is ProceduralLayerSpec

    override fun sample(target: PoseTarget, context: AnimatorEvaluationContext): LayerPose {
        val allowed = mask(target)
        val pose = AnimationPose()

        procedural.transforms.forEach { transform ->
            val node = target.node(transform.bone) ?: return@forEach
            if (node.definition.index !in allowed) return@forEach
            val bone = pose.bone(node.definition.index)
            transform.translation?.let { bone.translation = evaluator.vector(it, context) }
            transform.rotation?.let { rotation ->
                val euler = evaluator.vector(rotation, context)
                bone.rotation = QuatF(euler.z.deg, Vec3f.Z_AXIS) *
                        QuatF(euler.y.deg, Vec3f.Y_AXIS) *
                        QuatF(euler.x.deg, Vec3f.X_AXIS)
            }
            transform.scale?.let { bone.scale = evaluator.vector(it, context) }
        }

        return LayerPose(pose)
    }
}

/** Where playback of one clip currently is, and which way it is going. */
class ClipPlayback {
    var time: Float = 0f
        private set
    var reversed: Boolean = false
        private set
    var ended: Boolean = false
        private set

    /** Seconds spent past the end of a one-shot clip, which is what its fade-out is measured against. */
    var endElapsed: Float = 0f
        private set

    /** Back to the first frame, which is where a state starts every time it is entered. */
    fun reset() {
        time = 0f
        reversed = false
        ended = false
        endElapsed = 0f
    }

    fun advance(duration: Float, playMode: AnimationPlayMode, speed: Float, deltaTime: Float): Float {
        if (duration <= 0f) return 0f
        if (ended && playMode == AnimationPlayMode.Once) {
            endElapsed += deltaTime.coerceAtLeast(0f) * if (speed < 0f) -speed else speed
            return duration
        }

        val rawTime = time + speed * deltaTime * if (reversed) -1f else 1f
        val result = wrapTime(rawTime, duration, playMode, reversed)
        time = result.time
        reversed = result.reversed
        ended = result.ended
        endElapsed = if (playMode == AnimationPlayMode.Once && result.ended) {
            (rawTime - duration).coerceAtLeast(0f)
        } else {
            0f
        }
        return result.sampleTime
    }
}

internal data class WrappedTime(
    val time: Float,
    val sampleTime: Float,
    val reversed: Boolean,
    val ended: Boolean,
)

internal fun wrapTime(time: Float, duration: Float, playMode: AnimationPlayMode, reversed: Boolean): WrappedTime =
    when (playMode) {
        AnimationPlayMode.Once -> {
            val clamped = time.coerceIn(0f, duration)
            WrappedTime(clamped, clamped, reversed = false, ended = time >= duration)
        }

        AnimationPlayMode.Loop -> {
            val wrapped = time.modPositive(duration)
            WrappedTime(wrapped, wrapped, reversed = false, ended = false)
        }

        AnimationPlayMode.ClampForever -> {
            val clamped = time.coerceIn(0f, duration)
            WrappedTime(clamped, clamped, reversed = false, ended = false)
        }

        AnimationPlayMode.PingPong -> {
            val period = duration * 2f
            val phase = (if (reversed) period - time else time).modPositive(period)
            if (phase <= duration) WrappedTime(phase, phase, reversed = false, ended = false)
            else WrappedTime(period - phase, period - phase, reversed = true, ended = false)
        }
    }

private fun Float.modPositive(divisor: Float): Float = (this % divisor + divisor) % divisor

/** Every node of the hierarchy, indexed the way poses address them. */
fun List<RuntimeNode>.byIndex(): Map<Int, RuntimeNode> =
    flatMap { it.walk() }.associateBy { it.definition.index }
