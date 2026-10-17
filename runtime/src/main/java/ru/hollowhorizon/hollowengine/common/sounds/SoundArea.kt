package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import kotlin.math.*

private const val LANG = "hollowengine.gui.sound_area"

/**
 * One piece of the space a sound fills, in the space of what plays it. Inside any piece the sound is all
 * around; outside, it comes from the nearest of them and fades from its edge.
 */
@Serializable
sealed interface SoundArea {
    val offset: Vec3f

    /**
     * How far [point] is past the edge, negative inside; the nearest point of the area goes to [nearest]. Both
     * are in the space the area is placed in.
     */
    fun depth(point: Vec3f, nearest: MutableVec3f): Float

    @Serializable
    @SerialName("box")
    @EditorName("$LANG.box")
    data class Box(
        @EditorName("$LANG.offset")
        override val offset: Vec3f = Vec3f.ZERO,
        /** Euler degrees, applied X, then Y, then Z. */
        @EditorName("$LANG.rotation")
        val rotation: Vec3f = Vec3f.ZERO,
        @EditorName("$LANG.size")
        val size: Vec3f = Vec3f(4f, 3f, 4f),
    ) : SoundArea {
        override fun depth(point: Vec3f, nearest: MutableVec3f): Float {
            val place = MutableMat4f().translate(offset).rotate(eulerRotationXyz(rotation))
            val inverse = MutableMat4f(place).also { it.invert() }
            val local = inverse.transform(point, 1f, MutableVec3f())
            val hx = abs(size.x) / 2f
            val hy = abs(size.y) / 2f
            val hz = abs(size.z) / 2f
            val dx = abs(local.x) - hx
            val dy = abs(local.y) - hy
            val dz = abs(local.z) - hz

            val inside = dx <= 0f && dy <= 0f && dz <= 0f
            val clamped = MutableVec3f(local.x.coerceIn(-hx, hx), local.y.coerceIn(-hy, hy), local.z.coerceIn(-hz, hz))
            place.transform(if (inside) local else clamped, 1f, nearest)
            if (inside) return max(dx, max(dy, dz))
            val ox = max(dx, 0f)
            val oy = max(dy, 0f)
            val oz = max(dz, 0f)
            return sqrt(ox * ox + oy * oy + oz * oz)
        }
    }

    @Serializable
    @SerialName("sphere")
    @EditorName("$LANG.sphere")
    data class Sphere(
        @EditorName("$LANG.offset")
        override val offset: Vec3f = Vec3f.ZERO,
        @EditorName("$LANG.radius")
        @EditorRange(min = 0.0)
        val radius: Float = 4f,
    ) : SoundArea {
        override fun depth(point: Vec3f, nearest: MutableVec3f): Float {
            val away = point - offset
            val length = away.length()
            val depth = length - radius
            if (depth <= 0f || length < 1e-5f) nearest.set(point)
            else nearest.set(away).mul(radius / length).add(offset)
            return depth
        }
    }
}

/**
 * How far [point] is past the edge of the nearest of [areas], negative inside one of them, with the nearest
 * point of them in [nearest]; inside, that is [point] itself. Without areas it is infinitely far.
 */
fun List<SoundArea>.depth(point: Vec3f, nearest: MutableVec3f): Float {
    var best = Float.POSITIVE_INFINITY
    val candidate = MutableVec3f()
    forEach { area ->
        val depth = area.depth(point, candidate)
        if (depth < best) {
            best = depth
            nearest.set(candidate)
        }
    }
    if (best <= 0f) nearest.set(point)
    return best
}

/** The edges of the area as lines, in the space it is placed in: the twelve of a box, three great circles of a sphere. */
fun SoundArea.outline(line: (Vec3f, Vec3f) -> Unit) {
    when (this) {
        is SoundArea.Box -> {
            val frame = MutableMat4f().translate(offset).rotate(eulerRotationXyz(rotation)).scale(size)
            val corners = List(8) { index ->
                val x = if (index and 1 == 0) -0.5f else 0.5f
                val y = if (index and 2 == 0) -0.5f else 0.5f
                val z = if (index and 4 == 0) -0.5f else 0.5f
                frame.transform(Vec3f(x, y, z), 1f, MutableVec3f())
            }
            for (index in 0 until 8) for (axis in intArrayOf(1, 2, 4)) {
                if (index and axis == 0) line(corners[index], corners[index or axis])
            }
        }

        is SoundArea.Sphere -> for (plane in 0 until 3) {
            var previous: Vec3f? = null
            for (step in 0..CIRCLE_STEPS) {
                val angle = step * 2.0 * PI / CIRCLE_STEPS
                val a = (cos(angle) * radius).toFloat()
                val b = (sin(angle) * radius).toFloat()
                val point = offset + when (plane) {
                    0 -> Vec3f(a, b, 0f)
                    1 -> Vec3f(a, 0f, b)
                    else -> Vec3f(0f, a, b)
                }
                previous?.let { line(it, point) }
                previous = point
            }
        }
    }
}

private const val CIRCLE_STEPS = 32
