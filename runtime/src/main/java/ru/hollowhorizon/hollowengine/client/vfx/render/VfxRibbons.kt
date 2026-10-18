package ru.hollowhorizon.hollowengine.client.vfx.render

import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.client.vfx.*
import ru.hollowhorizon.hollowengine.common.utils.MutableColor
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Collects the strips of one ribbon node for one frame, in the space of the view.
 */
internal class VfxRibbonBuilder {
    private var points = FloatArray(VfxRibbonDraw.STRIDE * 64)
    private var count = 0
    private var strips = IntArray(16)
    private var stripCount = 0
    private var stripStart = 0

    fun begin() {
        count = 0
        stripCount = 0
    }

    fun startStrip() {
        stripStart = count
    }

    fun point(x: Float, y: Float, z: Float, halfWidth: Float, color: MutableColor, along: Float, light: Int) {
        val stride = VfxRibbonDraw.STRIDE
        if (points.size < (count + 1) * stride) points = points.copyOf(points.size * 2)
        val at = count * stride
        points[at] = x
        points[at + 1] = y
        points[at + 2] = z
        points[at + 3] = halfWidth
        points[at + 4] = color.r
        points[at + 5] = color.g
        points[at + 6] = color.b
        points[at + 7] = color.a
        points[at + 8] = along
        points[at + 9] = Float.fromBits(light)
        count++
    }

    /** Closes the strip; one with less than two points is dropped. [scale] multiplies what [point] got as `along`. */
    fun endStrip(scale: Float = 1f, shift: Float = 0f) {
        val length = count - stripStart
        if (length < 2) {
            count = stripStart
            return
        }
        for (index in stripStart until count) {
            val at = index * VfxRibbonDraw.STRIDE + 8
            points[at] = points[at] * scale + shift
        }
        if (strips.size < (stripCount + 1) * 2) strips = strips.copyOf(strips.size * 2)
        strips[stripCount * 2] = stripStart
        strips[stripCount * 2 + 1] = length
        stripCount++
    }

    fun build(material: VfxMaterialSpec, uniforms: VfxUniformValues?, repeat: Boolean): VfxRibbonDraw? {
        if (stripCount == 0) return null
        return VfxRibbonDraw(
            material = material,
            uniforms = uniforms,
            points = points.copyOf(count * VfxRibbonDraw.STRIDE),
            strips = strips.copyOf(stripCount * 2),
            stripCount = stripCount,
            repeat = repeat,
        )
    }
}

/** What a trail and a beam share: width and tint read along their length, and the material. */
abstract class VfxRibbonNode(spec: VfxSurfaceSpec, protected val node: VfxNodeRuntime) : VfxNodeBehavior {
    private val uniforms = spec.material.shader?.let { shader ->
        VfxUniformBinding(shader, spec.material.uniforms, spec.material.samplers, node)
    }

    protected val tint: VfxColorSampler = VfxSamplers.color(
        spec.tint,
        node.expressions,
        salt = VfxProperty.TINT.hashCode(),
        drive = node.drive(VfxProperty.TINT),
    )

    /** The inputs of the point being read; `p.progress` is how far along the ribbon it is. */
    protected val pointContext = VfxEvalContext()
    protected val color = MutableColor(1f, 1f, 1f, 1f)
    internal val builder = VfxRibbonBuilder()

    protected fun scalar(property: VfxProperty, value: VfxValue, default: Float): VfxFloatSampler =
        VfxSamplers.driven(
            VfxSamplers.scalar(value, node.expressions, default, VfxRangeMode.PER_PARTICLE, property.hashCode()),
            node.drive(property),
        )

    protected fun uniforms(): VfxUniformValues? = uniforms?.evaluate(node.context)

    /** How much [matrix] scales lengths, for widths given in the space of the ribbon. */
    protected fun scaleOf(matrix: Matrix4f): Float =
        sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01() + matrix.m02() * matrix.m02())
}

/**
 * The trail of the node itself, or of every particle of the emitter it sits under.
 */
class VfxTrailRenderer(private val spec: VfxTrailSpec, node: VfxNodeRuntime) : VfxRibbonNode(spec, node) {
    private val source = node.particleSource
    private val maxPoints = spec.maxPoints.coerceIn(2, MAX_POINTS)
    private val lifetime = spec.lifetime.coerceAtLeast(MIN_LIFETIME)
    private val minDistanceSquared = spec.minDistance * spec.minDistance
    private val width = scalar(VfxProperty.WIDTH, spec.width, 0.2f)

    private val own = FloatArray(maxPoints * 4)
    private var ownCount = 0

    private val points: FloatArray? = source?.particles?.channel("trail:${spec.id}:points", maxPoints * 4)

    /** The color this trail gives each particle; only the color, a trail has a width of its own. */
    private val look: FloatArray? = source?.let { emitter ->
        VfxParticleLook(spec.particle, node, emitter.particles).also(emitter::addLook).data
    }

    /** Per particle: how many points, and the index and random of the particle they were taken for. */
    private val meta: FloatArray? = source?.particles?.channel("trail:${spec.id}:meta", 3)

    private val head = MutableVec3f()
    private val scratch = Matrix4f()

    override val isIdle: Boolean get() = source != null || ownCount == 0

    override fun restart() {
        ownCount = 0
    }

    override fun stop(immediate: Boolean) {
        if (immediate) ownCount = 0
    }

    override fun update(dt: Float) {
        val emitter = source
        if (emitter == null) {
            ownCount = age(own, 0, ownCount, dt)
            if (node.isActive) {
                val offset = node.instance.originMinusAnchor
                val position = node.frame.position
                ownCount = record(own, 0, ownCount, position.x + offset.x, position.y + offset.y, position.z + offset.z)
            }
            return
        }

        val particles = emitter.particles
        val points = points ?: return
        val meta = meta ?: return
        for (slot in 0 until particles.count) {
            val at = slot * 3
            if (meta[at + 1] != particles.index[slot] || meta[at + 2] != particles.random[slot]) {
                meta[at] = 0f
                meta[at + 1] = particles.index[slot]
                meta[at + 2] = particles.random[slot]
            }
            val base = slot * maxPoints * 4
            var count = age(points, base, meta[at].toInt(), dt)
            val x = particles.positionX[slot]
            val y = particles.positionY[slot]
            val z = particles.positionZ[slot]
            count = record(points, base, count, x, y, z)
            meta[at] = count.toFloat()
        }
    }

    /** Ages every point and drops the ones past their lifetime; returns how many are left. */
    private fun age(data: FloatArray, base: Int, count: Int, dt: Float): Int {
        for (index in 0 until count) data[base + index * 4 + 3] += dt
        var left = count
        while (left > 0 && data[base + (left - 1) * 4 + 3] > lifetime) left--
        return left
    }

    /** Takes the head as a new point once it has moved far enough from the newest one. */
    private fun record(data: FloatArray, base: Int, count: Int, x: Float, y: Float, z: Float): Int {
        if (count > 0) {
            val dx = x - data[base]
            val dy = y - data[base + 1]
            val dz = z - data[base + 2]
            if (dx * dx + dy * dy + dz * dz < minDistanceSquared) return count
        }
        val kept = count.coerceAtMost(maxPoints - 1)
        System.arraycopy(data, base, data, base + 4, kept * 4)
        data[base] = x
        data[base + 1] = y
        data[base + 2] = z
        data[base + 3] = 0f
        return kept + 1
    }

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        if (!node.isActive) return
        builder.begin()

        val emitter = source
        if (emitter == null) {
            val offset = node.instance.originMinusAnchor
            val matrix = scratch.set(placement).translate(-offset.x, -offset.y, -offset.z)
            val position = node.frame.position
            head.set(position.x + offset.x, position.y + offset.y, position.z + offset.z)
            pointContext.copyInputs(node.context)
            strip(matrix, own, 0, ownCount, 1f, 1f, 1f, 1f, node.instance.lightAt(position))
        } else {
            val particles = emitter.particles
            val points = points ?: return
            val meta = meta ?: return
            val look = look ?: return
            val matrix = scratch.set(placement).mul(emitter.simToRender.asMatrix4f())
            for (slot in 0 until particles.count) {
                head.set(particles.positionX[slot], particles.positionY[slot], particles.positionZ[slot])
                emitter.fillParticleContext(slot)
                pointContext.copyInputs(emitter.context)
                val tone = slot * VfxParticleLook.STRIDE + VfxParticleLook.COLOR
                strip(
                    matrix, points, slot * maxPoints * 4, meta[slot * 3].toInt(),
                    look[tone], look[tone + 1], look[tone + 2], look[tone + 3],
                    particles.light[slot],
                )
            }
        }

        builder.build(spec.material, uniforms(), repeat = spec.uvMode == VfxRibbonUv.TILE)?.let(into.ribbons::add)
    }

    private fun strip(
        matrix: Matrix4f,
        data: FloatArray,
        base: Int,
        count: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        light: Int,
    ) {
        val scale = scaleOf(matrix)
        builder.startStrip()
        addPoint(matrix, head.x, head.y, head.z, 0f, 0f, scale, red, green, blue, alpha, light)

        var along = 0f
        var previousX = head.x
        var previousY = head.y
        var previousZ = head.z
        for (index in 0 until count) {
            val at = base + index * 4
            val dx = data[at] - previousX
            val dy = data[at + 1] - previousY
            val dz = data[at + 2] - previousZ
            val step = sqrt(dx * dx + dy * dy + dz * dz)
            if (step < 1.0e-5f) continue
            along += step
            addPoint(
                matrix, data[at], data[at + 1], data[at + 2], data[at + 3], along, scale,
                red, green, blue, alpha, light,
            )
            previousX = data[at]
            previousY = data[at + 1]
            previousZ = data[at + 2]
        }

        when (spec.uvMode) {
            VfxRibbonUv.STRETCH -> builder.endStrip(if (along > 0f) 1f / along else 0f)
            VfxRibbonUv.TILE -> builder.endStrip(1f / spec.tileLength.coerceAtLeast(MIN_TILE))
        }
    }

    private fun addPoint(
        matrix: Matrix4f,
        x: Float,
        y: Float,
        z: Float,
        age: Float,
        along: Float,
        scale: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        light: Int,
    ) {
        pointContext.progress = (age / lifetime).coerceIn(0f, 1f)
        val halfWidth = width.eval(pointContext) * scale * 0.5f
        tint.eval(pointContext, color)
        color.set(color.r * red, color.g * green, color.b * blue, color.a * alpha)
        builder.point(
            matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30(),
            matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31(),
            matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32(),
            halfWidth, color, along, light,
        )
    }

    private companion object {
        const val MAX_POINTS = 256
        const val MIN_LIFETIME = 0.01f
        const val MIN_TILE = 0.01f
    }
}

/**
 * A ribbon from the node to its target, bent sideways by noise that leaves both ends in place.
 */
class VfxBeamRenderer(private val spec: VfxBeamSpec, node: VfxNodeRuntime) : VfxRibbonNode(spec, node) {
    private val width = scalar(VfxProperty.WIDTH, spec.width, 0.15f)
    private val noise = scalar(VfxProperty.BEAM_NOISE, spec.noise, 0f)
    private val end: VfxVec3Sampler = VfxSamplers.vec3(
        VfxVec3Value.of(spec.end.x, spec.end.y, spec.end.z),
        node.expressions,
        drive = node.drive(VfxProperty.BEAM_END),
    )

    private val target: VfxNodeRuntime? by lazy { spec.target.takeIf { it.isNotBlank() }?.let(node.instance::node) }

    private val start = MutableVec3f()
    private val finish = MutableVec3f()
    private val scratch = MutableVec3f()
    private val side = MutableVec3f()
    private val lift = MutableVec3f()

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        if (!node.isActive) return

        start.set(node.frame.position)
        val targetNode = target
        if (targetNode != null) {
            finish.set(targetNode.frame.position)
        } else {
            end.eval(node.context, scratch)
            node.frame.transformPoint(Vec3f(scratch.x, scratch.y, scratch.z), finish)
        }

        val dx = finish.x - start.x
        val dy = finish.y - start.y
        val dz = finish.z - start.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0e-4f) return
        perpendiculars(dx / length, dy / length, dz / length)

        pointContext.copyInputs(node.context)
        val amplitude = noise.eval(node.context)
        val segments = spec.segments.coerceIn(1, MAX_SEGMENTS)
        val time = node.instance.time
        val light = node.instance.lightAt(node.frame.position)
        val scale = scaleOf(placement)

        builder.begin()
        builder.startStrip()
        for (index in 0..segments) {
            val t = index.toFloat() / segments
            var x = start.x + dx * t
            var y = start.y + dy * t
            var z = start.z + dz * t
            if (amplitude != 0f) {
                val envelope = sin(PI.toFloat() * t) * amplitude
                val phase = t * length * spec.noiseFrequency
                val wave = time * spec.noiseSpeed
                val sideways = VfxNoise.sample(phase, wave, 0f, node.seed) * envelope
                val upward = VfxNoise.sample(phase, wave, 0f, node.seed + 1) * envelope
                x += side.x * sideways + lift.x * upward
                y += side.y * sideways + lift.y * upward
                z += side.z * sideways + lift.z * upward
            }

            pointContext.progress = t
            tint.eval(pointContext, color)
            builder.point(
                placement.m00() * x + placement.m10() * y + placement.m20() * z + placement.m30(),
                placement.m01() * x + placement.m11() * y + placement.m21() * z + placement.m31(),
                placement.m02() * x + placement.m12() * y + placement.m22() * z + placement.m32(),
                width.eval(pointContext) * scale * 0.5f,
                color,
                t,
                light,
            )
        }

        val tiles = when (spec.uvMode) {
            VfxRibbonUv.STRETCH -> 1f
            VfxRibbonUv.TILE -> length / spec.tileLength.coerceAtLeast(0.01f)
        }
        builder.endStrip(tiles, -time * spec.uvScroll)

        val repeat = spec.uvMode == VfxRibbonUv.TILE || spec.uvScroll != 0f
        builder.build(spec.material, uniforms(), repeat)?.let(into.ribbons::add)
    }

    /** Two directions across the beam, for the noise to push along: off world up, or off X when the beam is upright. */
    private fun perpendiculars(x: Float, y: Float, z: Float) {
        val upright = abs(y) > 0.95f
        val upX = if (upright) 1f else 0f
        val upY = if (upright) 0f else 1f
        side.set(-z * upY, z * upX, x * upY - y * upX).norm()
        lift.set(y * side.z - z * side.y, z * side.x - x * side.z, x * side.y - y * side.x)
    }

    private companion object {
        const val MAX_SEGMENTS = 256
    }
}
