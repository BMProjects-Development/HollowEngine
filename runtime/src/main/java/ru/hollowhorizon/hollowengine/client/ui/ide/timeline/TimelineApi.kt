package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.common.utils.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round


enum class ChannelSampling {
    CONTINUOUS,
    DISCRETE,
}

fun interface ChannelValueFormatter {
    fun format(value: Float): String
}

data class ChannelValueOption(
    val value: Float,
    val labelKey: String,
)

/** Name, color and editor behavior of one scalar component of a property. */
data class ChannelSpec(
    val name: String,
    val color: Color,
    /** Whether the channel is an angle in degrees, and so wraps at +-180. */
    val isAngle: Boolean = false,
    /** Period used to keep captured cyclic values close to the neighbouring curve value. */
    val cyclePeriod: Float? = if (isAngle) 360f else null,
    val sampling: ChannelSampling = ChannelSampling.CONTINUOUS,
    /** Optional labels for enum-like values. Their keys are localized by the editor. */
    val valueOptions: List<ChannelValueOption> = emptyList(),
    /** Optional graph-axis formatter, used when every visible curve has the same formatter. */
    val graphValueFormatter: ChannelValueFormatter? = null,
) {
    val supportsCurveEditor: Boolean get() = sampling == ChannelSampling.CONTINUOUS

    fun normalize(value: Float): Float =
        valueOptions.minByOrNull { option -> abs(option.value - value) }?.value ?: value

    fun unwrap(value: Float, reference: Float): Float {
        val period = cyclePeriod?.takeIf { it > 0f } ?: return value
        return value + round((reference - value) / period) * period
    }
}

/** A scalar keyframe on one channel curve. */
class Keyframe(
    var time: Float,
    var value: Float,
    var interpolation: KeyInterpolation = KeyInterpolation.BEZIER,
    var handleMode: HandleMode = HandleMode.AUTO,
    var incoming: KeyTangent = KeyTangent.ZERO,
    var outgoing: KeyTangent = KeyTangent.ZERO,
) {
    fun tangent(side: TangentSide): KeyTangent = when (side) {
        TangentSide.INCOMING -> incoming
        TangentSide.OUTGOING -> outgoing
    }

    fun setTangent(side: TangentSide, tangent: KeyTangent) {
        when (side) {
            TangentSide.INCOMING -> incoming = tangent
            TangentSide.OUTGOING -> outgoing = tangent
        }
    }

    fun copy(time: Float = this.time) = Keyframe(time, value, interpolation, handleMode, incoming, outgoing)
}

/** A handle of a key picked in the graph on its own. */
data class HandlePick(val keyframe: Keyframe, val side: TangentSide)

/** Which of the two keyframe handles is being modified. */
enum class TangentSide {
    INCOMING, OUTGOING,
}

/**
 * One scalar component of a property, with the curve, that graph editor draws.
 */
class ChannelCurve(val spec: ChannelSpec) {
    val keyframes = mutableStateListOf<Keyframe>()
    var isVisible by mutableStateOf(true)

    /** Whether a channel of a property of several has its track in the list; the property itself may still be off it. */
    var isListed by mutableStateOf(true)

    val name: String get() = spec.name
    val color: Color get() = spec.color

    fun sort() {
        if (isSorted()) return
        val sorted = keyframes.sortedBy { it.time }
        keyframes.clear()
        keyframes.addAll(sorted)
    }

    private fun isSorted(): Boolean {
        for (index in 1 until keyframes.size) {
            if (keyframes[index - 1].time > keyframes[index].time) return false
        }
        return true
    }

    private fun ordered(): List<Keyframe> = if (isSorted()) keyframes else keyframes.sortedBy { it.time }

    fun keyAt(time: Float, epsilon: Float = KEY_TIME_EPSILON): Keyframe? =
        keyframes.firstOrNull { abs(it.time - time) <= epsilon }

    fun valueAt(time: Float, fallback: Float): Float {
        val keys = ordered()
        if (keys.isEmpty()) return fallback
        val first = keys.first()
        val last = keys.last()
        if (keys.size == 1 || time <= first.time) return first.value
        if (time >= last.time) return last.value

        val index = keys.indexOfLast { it.time <= time }.coerceIn(0, keys.size - 2)
        val start = keys[index]
        val end = keys[index + 1]
        val duration = end.time - start.time
        if (duration <= KEY_TIME_EPSILON) return start.value
        if (spec.sampling == ChannelSampling.DISCRETE) return start.value

        return when (start.interpolation) {
            KeyInterpolation.CONSTANT -> start.value
            KeyInterpolation.LINEAR -> start.value + (end.value - start.value) * ((time - start.time) / duration)
            KeyInterpolation.BEZIER -> TimelineCurve.sampleSegment(
                startTime = start.time,
                startValue = start.value,
                outgoing = effectiveTangents(start).outgoing,
                endTime = end.time,
                endValue = end.value,
                incoming = effectiveTangents(end).incoming,
                time = time,
            )
        }
    }

    fun effectiveTangents(keyframe: Keyframe): ChannelTangents {
        if (keyframe.handleMode != HandleMode.AUTO) {
            return ChannelTangents(keyframe.incoming, keyframe.outgoing)
        }
        val keys = ordered()
        val index = keys.indexOfFirst { it === keyframe }
        if (index < 0) return ChannelTangents(keyframe.incoming, keyframe.outgoing)
        val previous = keys.getOrNull(index - 1)
        val next = keys.getOrNull(index + 1)
        return TimelineCurve.autoTangents(
            previousTime = previous?.time,
            previousValue = previous?.value,
            time = keyframe.time,
            value = keyframe.value,
            nextTime = next?.time,
            nextValue = next?.value,
        )
    }

    fun isTangentUsed(keyframe: Keyframe, side: TangentSide): Boolean {
        if (!spec.supportsCurveEditor) return false
        val keys = ordered()
        val index = keys.indexOfFirst { it === keyframe }
        if (index < 0) return false
        return when (side) {
            TangentSide.OUTGOING -> index < keys.lastIndex && keyframe.interpolation == KeyInterpolation.BEZIER

            TangentSide.INCOMING -> index > 0 && keys[index - 1].interpolation == KeyInterpolation.BEZIER
        }
    }

    fun useSpline(keyframe: Keyframe, side: TangentSide) {
        if (!spec.supportsCurveEditor) return
        val keys = ordered()
        val index = keys.indexOfFirst { it === keyframe }
        if (index < 0) return
        when (side) {
            TangentSide.OUTGOING -> if (index < keys.lastIndex) {
                keyframe.interpolation = KeyInterpolation.BEZIER
            }

            TangentSide.INCOMING -> keys.getOrNull(index - 1)?.interpolation = KeyInterpolation.BEZIER
        }
    }

    companion object {
        const val KEY_TIME_EPSILON = 0.0001f
    }
}

data class ChannelBounds(val minimum: Float? = null, val maximum: Float? = null) {
    fun clamp(value: Float): Float {
        var result = value
        minimum?.let { result = max(result, it) }
        maximum?.let { result = min(result, it) }
        return result
    }

    companion object {
        val Unbounded = ChannelBounds()
    }
}

/**
 * One animated property: a name, a type, and one curve per scalar component of it.
 */
class AnimProperty<T>(
    val id: String,
    name: String,
    type: PropertyType<T>,
    val defaultValue: T,
    val apply: ((T) -> Unit)? = null,
) {
    var nameState by mutableStateOf(name)

    /** Whether the curves of this property are drawn in the graph; it does not change the value. */
    var isVisible by mutableStateOf(true)

    /** A locked property cannot be edited, which is what keeps a finished track finished. */
    var isLocked by mutableStateOf(false)

    var isListed by mutableStateOf(true)

    var type: PropertyType<T> by mutableStateOf(type)
        private set

    var curves: List<ChannelCurve> = type.channels.map { ChannelCurve(it) }
        private set

    val channels: List<ChannelSpec> get() = type.channels

    val keyframes: List<Keyframe> get() = curves.flatMap { it.keyframes }

    fun retype(next: PropertyType<T>) {
        if (next.channels == type.channels) {
            type = next
            return
        }

        val resampled = resample(type, next)
        type = next
        curves = next.channels.mapIndexed { channel, spec ->
            ChannelCurve(spec).also { curve -> curve.keyframes.addAll(resampled[channel]) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun restoreState(type: PropertyType<*>, curves: List<ChannelCurve>) {
        this.type = type as PropertyType<T>
        this.curves = curves
    }

    /**
     * Takes over the curves of [other], which has to be of the same type.
     *
     * Used when a property is rebound to a new target and its keys have to survive the swap.
     */
    fun adoptCurves(other: AnimProperty<*>) {
        if (other.type.channels != type.channels) return
        curves = other.curves
        isVisible = other.isVisible
        isLocked = other.isLocked
    }

    /** Keyframes carried over when the property changes type, such as euler to quaternion. */
    private fun resample(from: PropertyType<T>, to: PropertyType<T>): List<List<Keyframe>> {
        val times = curves.flatMap { curve -> curve.keyframes.map { it.time } }
            .distinctBy { round(it / ChannelCurve.KEY_TIME_EPSILON) }.sorted()
        val channelValues = to.channels.indices.map { mutableListOf<Keyframe>() }
        val buffer = FloatArray(from.channels.size)
        times.forEach { time ->
            from.decompose(defaultValue, buffer)
            curves.forEachIndexed { index, curve ->
                buffer[index] = curve.valueAt(time, buffer[index])
            }
            val value = from.compose(buffer)
            val next = FloatArray(to.channels.size)
            to.decompose(value, next)
            next.forEachIndexed { index, component ->
                channelValues[index].add(Keyframe(time, component))
            }
        }
        return channelValues
    }

    fun bounds(channel: Int): ChannelBounds = type.bounds(channel)

    fun valueAt(time: Float): T {
        val size = type.channels.size
        val values = FloatArray(size)
        type.decompose(defaultValue, values)
        for (channel in 0 until size) {
            val curve = curves.getOrNull(channel)?.takeIf { it.isVisible } ?: continue
            val base = values[channel]
            val sampled = curve.valueAt(time, base)
            values[channel] = type.bounds(channel).clamp(
                if (curve.spec.sampling == ChannelSampling.DISCRETE) curve.spec.normalize(sampled) else sampled
            )
        }
        return type.compose(values)
    }

    fun update(time: Float) {
        apply?.invoke(valueAt(time))
    }

    fun curveOf(keyframe: Keyframe): ChannelCurve? =
        curves.firstOrNull { curve -> curve.keyframes.any { it === keyframe } }
}

interface PropertyType<T> {
    val id: String
    val channels: List<ChannelSpec>

    fun bounds(channel: Int): ChannelBounds = ChannelBounds.Unbounded

    val isChannelSpaceLinear: Boolean get() = true

    fun decompose(value: T, into: FloatArray)
    fun compose(values: FloatArray): T
}

/**
 * Which objects of an editor have rows in the track list, as the menu by the list asks: an editor whose objects
 * the author picks in a scene provides it.
 */
interface TrackListing {
    /** Whether only the objects selected in the editor have rows. */
    var onlySelected: Boolean

    /** Gives every object its rows back, with the tracks the author closed, and lists more than the selection. */
    fun showAll()

    /** Closes the rows of every object but the selected ones. */
    fun hideUnselected()
}

class TrackGroup(name: String) {
    var nameState by mutableStateOf(name)
    var isCollapsed by mutableStateOf(false)
    var isLocked by mutableStateOf(false)
    var isVisible by mutableStateOf(true)

    /** Whether the group row stays in the list when none of its properties is listed. */
    var isListed by mutableStateOf(true)

    /** Whether anything of this group has a row, which is what decides whether the group has one. */
    val hasListedContent: Boolean
        get() = isListed || lanes.any { it.isShown } || properties.any { it.isListed } || children.any { it.hasListedContent }

    /** Rows of segments shown under the group's own row, above its properties. */
    val lanes = mutableStateListOf<SegmentLane>()

    val children = mutableStateListOf<TrackGroup>()
    val properties = mutableStateListOf<AnimProperty<*>>()

    fun allProperties(): List<AnimProperty<*>> = properties + children.flatMap { it.allProperties() }
}
