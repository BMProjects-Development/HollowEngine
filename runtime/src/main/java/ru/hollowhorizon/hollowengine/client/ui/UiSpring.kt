package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * A number that springs towards its target instead of jumping there. Whoever owns it calls
 * [advance] from a `withFrameNanos` loop.
 *
 * [rest] is how close counts as arrived; it has to be small next to the steps the value takes, or
 * the last stretch of the motion is a visible jump.
 */
internal class SpringFloat(initial: Float, private val rest: Float = 0.05f) {
    var value by mutableStateOf(initial)
        private set

    var target: Float = initial

    private var velocity = 0f
    private var lastFrame = 0L

    /** Puts the value there at once, for changes the pointer is already animating by hand. */
    fun snapTo(next: Float) {
        target = next
        value = next
        velocity = 0f
    }

    fun advance(frameNanos: Long) {
        val previous = lastFrame
        lastFrame = frameNanos
        if (previous == 0L) return

        val delta = target - value
        if (abs(delta) < rest && abs(velocity) < rest) {
            if (value != target) value = target
            velocity = 0f
            return
        }

        val dt = ((frameNanos - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
        velocity += (delta * 300f - velocity * 32f) * dt
        value += velocity * dt
    }
}

/**
 * The zoom of an orbit preview, kept as a camera distance or as a model scale.
 */
internal class SpringZoom(
    initial: Float,
    private val min: Float,
    private val max: Float,
    private val perNotch: Float,
) {
    private val spring = SpringFloat(initial, rest = 0.0005f)

    val value: Float get() = spring.value

    fun scroll(scrollY: Float) {
        if (scrollY == 0f) return
        val factor = if (scrollY > 0f) perNotch else 1f / perNotch
        spring.target = (spring.target * factor).coerceIn(min, max)
    }

    fun advance(frameNanos: Long) = spring.advance(frameNanos)

    fun snapTo(next: Float) = spring.snapTo(next.coerceIn(min, max))
}

/** How much one wheel notch scales a model preview. */
internal const val ModelZoomPerNotch = 1f / 1.2f
