package ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations

import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.bedrock.Query

interface Interpolator<T> {
    val duration: Float

    fun compute(time: Float, context: BedrockContext = BedrockContext(Query.EMPTY)): T
}

abstract class StaticInterpolator<T>(val keys: FloatArray, val values: Array<T>) : Interpolator<T> {
    override val duration: Float = keys.lastOrNull() ?: 0f

    override fun compute(time: Float, context: BedrockContext): T = compute(time)

    protected abstract fun compute(time: Float): T

    protected val Float.animIndex: Int
        get() {
            val index = java.util.Arrays.binarySearch(keys, this)
            return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
        }
}

/**
 * Track in which keys retain their values until the next track, rather than smoothly transitioning into it.
 */
abstract class SteppedInterpolator<T>(keys: FloatArray, values: Array<T>) : StaticInterpolator<T>(keys, values) {
    override val duration: Float = when {
        keys.isEmpty() -> 0f
        keys.size < 2 -> keys.last()
        else -> keys.last() + (keys.last() - keys[keys.size - 2])
    }

    override fun compute(time: Float): T = values[time.animIndex.coerceAtMost(values.lastIndex)]
}
