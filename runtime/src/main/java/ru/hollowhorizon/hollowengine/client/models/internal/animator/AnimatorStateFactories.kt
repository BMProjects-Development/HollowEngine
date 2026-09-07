package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerStateSpec
import ru.hollowhorizon.hollowengine.common.models.AnimatorStateTypes
import ru.hollowhorizon.hollowengine.common.models.ClipStateSpec
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * Describes controller's behavior in a given state.
 *
 * For each frame, as long as state is the current one, a pose is requested from it;
 * during a smooth transition, poses are requested for both state being exited and the state being entered,
 * so that the controller can blend them.
 */
interface AnimationState {
    val time: Float get() = 0f

    /** Returns `null` if state does not contain data for this frame. */
    fun sample(target: PoseTarget, allowed: Set<Int>, context: AnimatorEvaluationContext): AnimationPose?

    /**
     * The controller is moving into this state.
     *
     * [from] is the pose it is coming from. It is null when nothing was playing before.
     */
    fun enter(from: AnimationPose?) = Unit

    /** When controller has switched states and will no longer make any requests from that state. */
    fun exit() = Unit

    /**
     * Applies the modified description without interrupting playback.
     * Returns false if this object cannot execute the new description.
     */
    fun reconfigure(spec: AnimationControllerStateSpec): Boolean = false
}

/** Converts a specification of a single state type into an object that implements it. */
fun interface AnimatorStateFactory {
    fun create(spec: AnimationControllerStateSpec): AnimationState
}

object AnimatorStateFactories {
    val point = ExtensionPoints.create<AnimatorStateFactory>("hollowengine:animator/state_factories".rl)

    init {
        register("hollowengine:animator/state/clip") { ClipState(it as ClipStateSpec) }
    }

    fun register(typeId: String, factory: AnimatorStateFactory): ExtensionHandle = point.register(typeId.rl, factory)

    fun create(spec: AnimationControllerStateSpec): AnimationState? {
        val type = AnimatorStateTypes.of(spec) ?: return null
        return point.find(type.key)?.create(spec)
    }
}
