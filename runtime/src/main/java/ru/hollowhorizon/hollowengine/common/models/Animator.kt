package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.*

/**
 * Everything a model animates with: a stack of layers, blended in priority order.
 */
@Serializable
data class Animator(
    val layers: List<AnimatorLayerSpec> = emptyList(),
    /**
     * Where the editor left each state on its canvas, keyed by [graphKey].
     */
    val layout: Map<String, GraphPoint> = emptyMap(),
)

@Serializable
data class GraphPoint(val x: Float = 0f, val y: Float = 0f)

/** Names one state of one layer in [Animator.layout]. */
fun graphKey(layerId: String, stateId: String): String = "$layerId/$stateId"

/**
 * One entry of an [Animator]'s stack.
 */
@Serializable
abstract class AnimatorLayerSpec {
    abstract val id: String
    abstract val weight: AnimationExpression
    abstract val priority: Int
    abstract val blendMode: LayerBlendMode
    abstract val mask: BoneMask
    abstract val fadeIn: Float
    abstract val fadeOut: Float

    /**
     * A copy with the shared fields replaced, whichever kind of layer this is.
     */
    abstract fun withCommon(
        id: String = this.id,
        weight: AnimationExpression = this.weight,
        priority: Int = this.priority,
        blendMode: LayerBlendMode = this.blendMode,
        mask: BoneMask = this.mask,
        fadeIn: Float = this.fadeIn,
        fadeOut: Float = this.fadeOut,
    ): AnimatorLayerSpec

    /**
     * Every expression this layer evaluates, so the animator bakes them all in one pass.
     */
    open fun expressions(): List<AnimationExpression> = listOf(weight)
}

@Serializable
@SerialName("hollowengine:animator/clip")
data class ClipAnimationLayerSpec(
    override val id: String = newAnimationLayerId("clip"),
    val animation: String,
    val playMode: AnimationPlayMode = AnimationPlayMode.Once,
    val speed: AnimationExpression = AnimationExpression.ONE,
    override val weight: AnimationExpression = AnimationExpression.ONE,
    override val priority: Int = 0,
    override val blendMode: LayerBlendMode = LayerBlendMode.Override,
    override val mask: BoneMask = BoneMask.full(),
    override val fadeIn: Float = 0f,
    override val fadeOut: Float = 0f,
    val referencePose: String? = null,
    val removeOnEnd: Boolean = playMode == AnimationPlayMode.Once,
    val removeAtGameTime: Long? = null,
    val stopAtGameTime: Long? = null,
) : AnimatorLayerSpec() {
    override fun withCommon(
        id: String,
        weight: AnimationExpression,
        priority: Int,
        blendMode: LayerBlendMode,
        mask: BoneMask,
        fadeIn: Float,
        fadeOut: Float,
    ) = copy(
        id = id, weight = weight, priority = priority, blendMode = blendMode,
        mask = mask, fadeIn = fadeIn, fadeOut = fadeOut,
    )

    override fun expressions() = listOf(weight, speed)
}

@Serializable
@SerialName("hollowengine:animator/controller")
data class AnimationControllerLayerSpec(
    override val id: String = newAnimationLayerId("controller"),
    val states: List<AnimationControllerStateSpec> = emptyList(),
    val transitions: List<AnimationControllerTransitionSpec> = emptyList(),
    val entryState: String? = states.firstOrNull()?.id,
    override val weight: AnimationExpression = AnimationExpression.ONE,
    override val priority: Int = 0,
    override val blendMode: LayerBlendMode = LayerBlendMode.Override,
    override val mask: BoneMask = BoneMask.full(),
    override val fadeIn: Float = 0f,
    override val fadeOut: Float = 0f,
) : AnimatorLayerSpec() {
    override fun withCommon(
        id: String,
        weight: AnimationExpression,
        priority: Int,
        blendMode: LayerBlendMode,
        mask: BoneMask,
        fadeIn: Float,
        fadeOut: Float,
    ) = copy(
        id = id, weight = weight, priority = priority, blendMode = blendMode,
        mask = mask, fadeIn = fadeIn, fadeOut = fadeOut,
    )

    override fun expressions() = buildList {
        add(weight)
        states.forEach { addAll(it.expressions()) }
        transitions.forEach {
            add(it.condition)
            add(it.duration)
        }
    }
}

@Serializable
@SerialName("hollowengine:animator/procedural")
data class ProceduralLayerSpec(
    override val id: String = newAnimationLayerId("procedural"),
    val transforms: List<ProceduralBoneTransformSpec> = emptyList(),
    override val weight: AnimationExpression = AnimationExpression.ONE,
    override val priority: Int = 0,
    override val blendMode: LayerBlendMode = LayerBlendMode.Additive,
    override val mask: BoneMask = BoneMask.full(),
    override val fadeIn: Float = 0f,
    override val fadeOut: Float = 0f,
) : AnimatorLayerSpec() {
    override fun withCommon(
        id: String,
        weight: AnimationExpression,
        priority: Int,
        blendMode: LayerBlendMode,
        mask: BoneMask,
        fadeIn: Float,
        fadeOut: Float,
    ) = copy(
        id = id, weight = weight, priority = priority, blendMode = blendMode,
        mask = mask, fadeIn = fadeIn, fadeOut = fadeOut,
    )

    override fun expressions() = buildList {
        add(weight)
        transforms.forEach { transform ->
            listOfNotNull(transform.translation, transform.rotation, transform.scale).forEach {
                add(it.x)
                add(it.y)
                add(it.z)
            }
        }
    }
}

/**
 * One of the controller's states: what the model does while in this state.
 */
@Serializable
abstract class AnimationControllerStateSpec {
    abstract val id: String

    /** The same state under another name. */
    abstract fun withId(id: String): AnimationControllerStateSpec

    /** Every expression this state evaluates, so the animator bakes them all in one pass. */
    open fun expressions(): List<AnimationExpression> = emptyList()
}

/** Plays one clip for as long as the controller stays in the state. */
@Serializable
@SerialName("hollowengine:animator/state/clip")
data class ClipStateSpec(
    override val id: String,
    val animation: String,
    val playMode: AnimationPlayMode = AnimationPlayMode.Loop,
    val speed: AnimationExpression = AnimationExpression.ONE,
) : AnimationControllerStateSpec() {
    override fun withId(id: String) = copy(id = id)

    override fun expressions() = listOf(speed)
}

@Serializable
data class AnimationControllerTransitionSpec(
    val from: String = ANY_STATE,
    val to: String,
    val condition: AnimationExpression = AnimationExpression.TRUE,
    val duration: AnimationExpression = AnimationExpression.ZERO,
    val priority: Int = 0,
    val exitTime: Float? = null,
)

@Serializable
data class ProceduralBoneTransformSpec(
    val bone: String,
    val translation: AnimationVectorExpression? = null,
    val rotation: AnimationVectorExpression? = null,
    val scale: AnimationVectorExpression? = null,
)

@Serializable
data class BoneMask(
    val includes: Set<String> = emptySet(),
    val excludes: Set<String> = emptySet(),
) {
    companion object {
        fun full() = BoneMask(emptySet(), emptySet())
        fun of(vararg bones: String): BoneMask {
            val includes = linkedSetOf<String>()
            val excludes = linkedSetOf<String>()
            bones.forEach { bone ->
                if (bone.startsWith("!")) excludes += bone.drop(1) else includes += bone
            }
            return BoneMask(includes, excludes)
        }
    }
}

@Serializable
data class AnimationExpression(
    val source: String = "0",
) {
    companion object {
        val ZERO = AnimationExpression("0")
        val ONE = AnimationExpression("1")
        val TRUE = AnimationExpression("true")
        val FALSE = AnimationExpression("false")
    }
}

@Serializable
data class AnimationVectorExpression(
    val x: AnimationExpression = AnimationExpression.ZERO,
    val y: AnimationExpression = AnimationExpression.ZERO,
    val z: AnimationExpression = AnimationExpression.ZERO,
) {
    companion object {
        val ZERO = AnimationVectorExpression()
        val ONE = AnimationVectorExpression(AnimationExpression.ONE, AnimationExpression.ONE, AnimationExpression.ONE)
    }
}

enum class LayerBlendMode {
    Additive,
    Override,
}

enum class AnimationPlayMode {
    Once,
    Loop,
    ClampForever,
    PingPong,
}

const val ANY_STATE = "__any__"

fun newAnimationLayerId(prefix: String): String = "$prefix:${UUID.randomUUID()}"
