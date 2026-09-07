package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator as evaluator

/**
 * State machine determines: what state the model is in and how it smoothly transitions to the next one.
 *
 * What each state does depends on the state itself. Most states play a clip, and some may also pass bones to
 * the simulation; therefore, the controller only decides when to exit one state and how long two states will overlap.
 */
class AnimationController(initialSpec: AnimationControllerLayerSpec) {
    private var spec = initialSpec
    private val states = LinkedHashMap<String, AnimationState>()
    private var transition: Transition? = null

    /** The state the model is in, or null before the first frame decided. */
    var stateId: String? = null
        private set

    /** How far into its own clip the current state is, for callers showing progress. */
    val stateTime: Float get() = stateId?.let { states[it]?.time } ?: 0f

    init {
        rebuildStates()
    }

    /** Updates controller rules while retaining playback for states that still exist. */
    fun configure(next: AnimationControllerLayerSpec) {
        if (spec == next) return
        spec = next
        rebuildStates()

        if (stateId !in states.keys) {
            stateId = null
            transition = null
        } else if (transition?.let { it.from !in states.keys || it.to !in states.keys } == true) {
            transition = null
        }
    }

    /**
     * Creates object for each state of the specification, retaining the already-running object if
     * the description still applies to it.
     */
    private fun rebuildStates() {
        val rebuilt = LinkedHashMap<String, AnimationState>(spec.states.size)
        spec.states.forEach { state ->
            val existing = states[state.id]?.takeIf { it.reconfigure(state) }
            rebuilt[state.id] = existing ?: AnimatorStateFactories.create(state) ?: return@forEach
        }
        val kept = rebuilt.values.toSet()
        states.values.filterNot(kept::contains).forEach(AnimationState::exit)
        states.clear()
        states.putAll(rebuilt)
    }

    fun sample(target: PoseTarget, allowed: Set<Int>, context: AnimatorEvaluationContext): AnimationPose? {
        val currentId = start() ?: return null
        val current = states.getValue(currentId)
        beginTransition(currentId, context)

        val running = transition ?: return current.sample(target, allowed, context)

        val from = states[running.from] ?: current
        val to = states[running.to] ?: current
        val fromPose = from.sample(target, allowed, context) ?: AnimationPose()

        if (!running.entered) {
            running.entered = true
            to.enter(fromPose)
        }

        running.elapsed += context.deltaTime
        val factor = if (running.duration <= 0f) 1f else (running.elapsed / running.duration).coerceIn(0f, 1f)
        val toPose = to.sample(target, allowed, context) ?: AnimationPose()

        if (factor >= 1f) {
            stateId = running.to
            transition = null
            if (running.from != running.to) from.exit()
        }

        return AnimationPose.mix(fromPose, toPose, factor)
    }

    /**
     * State being played, entering the first one on the frame the controller starts.
     */
    private fun start(): String? {
        stateId?.takeIf(states::containsKey)?.let { return it }
        if (states.isEmpty()) return null

        val entry = spec.entryState?.takeIf(states::containsKey) ?: states.keys.first()
        stateId = entry
        states.getValue(entry).enter(null)
        return entry
    }

    private fun beginTransition(currentId: String, context: AnimatorEvaluationContext) {
        if (transition != null) return

        val selected = selectTransition(currentId, context) ?: return
        val duration = evaluator.float(selected.duration, context, 0f).coerceAtLeast(0f)
        transition = Transition(from = currentId, to = selected.to, duration = duration)
    }

    private fun selectTransition(
        currentId: String,
        context: AnimatorEvaluationContext,
    ): AnimationControllerTransitionSpec? {
        val currentTime = states.getValue(currentId).time
        context.stateTime = currentTime

        return spec.transitions.asSequence().filter { it.from == currentId || it.from == ANY_STATE }
            .filter { transition ->
                val exitTime = transition.exitTime
                exitTime == null || currentTime >= exitTime
            }.filter { evaluator.boolean(it.condition, context, false) }
            .sortedWith(compareByDescending<AnimationControllerTransitionSpec> { it.priority }.thenBy { it.to })
            .firstOrNull()?.takeIf { it.to != currentId && it.to in states }
    }

    private class Transition(val from: String, val to: String, val duration: Float) {
        var elapsed: Float = 0f
        var entered: Boolean = false
    }
}

/** Plays one clip for as long as the controller stays in the state. */
class ClipState(private var spec: ClipStateSpec) : AnimationState {
    private val playback = ClipPlayback()

    override val time: Float get() = playback.time

    override fun enter(from: AnimationPose?) = playback.reset()

    override fun reconfigure(spec: AnimationControllerStateSpec): Boolean {
        val clip = spec as? ClipStateSpec ?: return false
        this.spec = clip
        return true
    }

    override fun sample(
        target: PoseTarget,
        allowed: Set<Int>,
        context: AnimatorEvaluationContext,
    ): AnimationPose? {
        val animation = target.animations[spec.animation] ?: return null
        context.stateTime = playback.time
        val speed = evaluator.float(spec.speed, context, 1f)
        val time = playback.advance(animation.duration, spec.playMode, speed, context.deltaTime)
        return AnimationPose.sample(animation, time, allowed)
    }
}

/** The layer a controller poses through. */
class ControllerLayer(controllerSpec: AnimationControllerLayerSpec) : SpecLayer(controllerSpec) {
    val controller = AnimationController(controllerSpec)

    override val time: Float get() = controller.stateTime

    override fun accepts(spec: AnimatorLayerSpec): Boolean = spec is AnimationControllerLayerSpec

    override fun onReconfigured(spec: AnimatorLayerSpec) {
        controller.configure(spec as AnimationControllerLayerSpec)
    }

    override fun sample(target: PoseTarget, context: AnimatorEvaluationContext): LayerPose? =
        controller.sample(target, mask(target), context)?.let(::LayerPose)
}
