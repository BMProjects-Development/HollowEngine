package ru.hollowhorizon.hollowengine.client.models.bbmodel

import kotlinx.serialization.json.jsonPrimitive
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockExpressions
import ru.hollowhorizon.hollowengine.client.models.bedrock.FloatExpr
import ru.hollowhorizon.hollowengine.client.models.bedrock.MolangDefaults
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

internal object BBAnimations {
    fun convert(file: BlockBenchModel, nodes: Map<String, BBNode>, defaults: MolangDefaults): List<AnimationClip> =
        file.animations.map { animation ->
            require(animation.length.isFinite() && animation.length >= 0f) { "Invalid Blockbench animation length for ${animation.name}" }
            val globalRotations = hashSetOf<Int>()
            val channels = linkedMapOf<Int, AnimationData>()
            animation.animators.forEach { (uuid, animator) ->
                if (animator.keyframes.isEmpty()) return@forEach
                val node = nodes[uuid]
                if (node == null) {
                    HollowEngine.LOGGER.warn(
                        "Blockbench animation '{}' targets missing/effect animator '{}'", animation.name, uuid
                    )
                    return@forEach
                }
                val tracks = animator.keyframes.groupBy { it.channel }
                val legacy = file.meta.version.substringBefore('.').toInt() < 5
                fun track(channel: String): BBVectorTrack? = tracks[channel]?.let {
                    BBVectorTrack(it, legacy, animation.loop == "loop", defaults)
                }

                val translation = track("position")
                val rotation = track("rotation")?.let { track ->
                    BBRotationTrack(track, node.element.rotation.bbVector(), animator.quaternionInterpolation)
                }
                val scale = track("scale")
                if (animator.rotationGlobal && rotation != null) globalRotations += node.index
                channels[node.index] = AnimationData(translation, rotation, scale, null)
            }
            val length = animation.length.takeIf { it > 0f } ?: channels.values.maxOfOrNull { it.duration } ?: 0f
            AnimationClip(
                animation.name, channels, length,
                poseTransform = if (globalRotations.isEmpty()) null else { pose ->
                    globalToLocal(pose, nodes.values, globalRotations)
                },
            )
        }

    private fun globalToLocal(pose: AnimationPose, nodes: Collection<BBNode>, global: Set<Int>) {
        val rotations = hashMapOf<Int, QuatF>()
        nodes.forEach { node ->
            val parent = node.parent?.let { rotations.getValue(it.index) } ?: QuatF.IDENTITY
            val bone = pose[node.index]
            val local = node.transform.rotation * (bone?.rotation ?: QuatF.IDENTITY)
            rotations[node.index] = if (node.index in global) {
                bone?.rotation = node.transform.rotation.inverted() * parent.inverted() * local
                local
            } else parent * local
        }
    }
}

/** Interpolate Euler values before converting to quaternions: a 360 degree spin must stay a spin. */
internal class BBVectorTrack(
    frames: List<BBKeyframe>,
    private val legacy: Boolean,
    private val loop: Boolean,
    private val defaults: MolangDefaults = MolangDefaults.EMPTY,
) : Interpolator<Vec3f> {
    internal data class Key(val data: BBKeyframe, val points: List<List<FloatExpr>>)

    internal val keys = frames.sortedBy { it.time }.map { key ->
        require(key.time.isFinite() && key.time >= 0f) { "Invalid Blockbench keyframe time ${key.time}" }
        require(key.interpolation in setOf("linear", "step", "catmullrom", "bezier")) {
            "Unsupported Blockbench interpolation '${key.interpolation}'"
        }
        Key(key, key.dataPoints.map { point ->
            listOf("x", "y", "z").map { axis ->
                point[axis]?.jsonPrimitive?.content?.let(BedrockExpressions::parse)
                    ?: FloatExpr.literal(if (key.channel == "scale") 1f else 0f)
            }
        }.also { require(it.isNotEmpty()) { "Blockbench keyframe has no data points" } })
    }
    override val duration: Float = keys.lastOrNull()?.data?.time ?: 0f

    internal fun evaluationContext(time: Float, context: BedrockContext): BedrockContext =
        context.withDefaults(defaults).atAnimationTime(time)

    internal fun value(key: Key, after: Boolean, context: BedrockContext): Vec3f {
        val point = if (after) key.points.last() else key.points.first()
        val value = Vec3f(point[0].eval(context), point[1].eval(context), point[2].eval(context))
        return if (!legacy) value else when (key.data.channel) {
            "position" -> Vec3f(-value.x, value.y, value.z)
            "rotation" -> Vec3f(-value.x, -value.y, value.z)
            else -> value
        }
    }

    internal fun segment(time: Float): Int {
        var low = 0
        var high = keys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keys[mid].data.time < time) low = mid + 1 else high = mid
        }
        return low
    }

    override fun compute(time: Float, context: BedrockContext): Vec3f {
        val timed = evaluationContext(time, context)
        val nextIndex = segment(time)
        if (nextIndex == 0) return value(keys.first(), false, timed)
        if (nextIndex == keys.size) return value(keys.last(), true, timed)
        val next = keys[nextIndex]
        if (next.data.time == time) return value(next, false, timed)
        val previous = keys[nextIndex - 1]
        val a = value(previous, true, timed)
        val b = value(next, false, timed)
        val span = next.data.time - previous.data.time
        val t = (time - previous.data.time) / span
        return when {
            previous.data.interpolation == "step" -> a
            previous.data.interpolation == "catmullrom" || next.data.interpolation == "catmullrom" -> {
                val before = keys.getOrNull(nextIndex - 2) ?: if (loop && keys.size >= 3) keys[keys.size - 2] else null
                val after = keys.getOrNull(nextIndex + 1) ?: if (loop && keys.size >= 3) keys[1] else null
                val p0 = before?.takeIf { previous.points.size == 1 }?.let { value(it, true, timed) } ?: a
                val p3 = after?.takeIf { next.points.size == 1 }?.let { value(it, false, timed) } ?: b
                (a * 2f + (b - p0) * t + (p0 * 2f - a * 5f + b * 4f - p3) * (t * t) + (p3 - p0 + (a - b) * 3f) * (t * t * t)) * 0.5f
            }

            previous.data.interpolation == "bezier" || next.data.interpolation == "bezier" -> {
                val av = listOf(a.x, a.y, a.z)
                val bv = listOf(b.x, b.y, b.z)
                val values = List(3) { axis ->
                    val rightTime = previous.data.bezierRightTime[axis].coerceIn(0f, span) / span
                    val leftTime = 1f + next.data.bezierLeftTime[axis].coerceIn(-span, 0f) / span
                    var low = 0f
                    var high = 1f
                    repeat(24) {
                        val mid = (low + high) * 0.5f
                        if (bezier(0f, rightTime, leftTime, 1f, mid) < t) low = mid else high = mid
                    }
                    val sign = if (legacy && (axis == 0 && previous.data.channel in setOf(
                            "position", "rotation"
                        ) || axis == 1 && previous.data.channel == "rotation")
                    ) -1f else 1f
                    bezier(
                        av[axis],
                        av[axis] + previous.data.bezierRightValue[axis] * sign,
                        bv[axis] + next.data.bezierLeftValue[axis] * sign,
                        bv[axis],
                        (low + high) * 0.5f
                    )
                }
                Vec3f(values[0], values[1], values[2])
            }

            else -> a.mix(b, t)
        }
    }

    private fun bezier(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val s = 1f - t
        return a * s * s * s + 3f * b * s * s * t + 3f * c * s * t * t + d * t * t * t
    }
}

private class BBRotationTrack(
    private val track: BBVectorTrack,
    private val restEuler: Vec3f,
    private val quaternion: Boolean,
) : Interpolator<QuatF> {
    override val duration: Float get() = track.duration
    private val inverseRest = bbRotation(restEuler).inverted()

    override fun compute(time: Float, context: BedrockContext): QuatF {
        if (quaternion) {
            val next = track.segment(time)
            if (next in 1 until track.keys.size && track.keys[next - 1].data.interpolation != "step") {
                val a = track.keys[next - 1]
                val b = track.keys[next]
                val t = (time - a.data.time) / (b.data.time - a.data.time)
                val timed = track.evaluationContext(time, context)
                return inverseRest * bbRotation(
                    restEuler + track.value(
                        a, true, timed
                    )
                ).mix(bbRotation(restEuler + track.value(b, false, timed)), t)
            }
        }
        return inverseRest * bbRotation(restEuler + track.compute(time, context))
    }
}
