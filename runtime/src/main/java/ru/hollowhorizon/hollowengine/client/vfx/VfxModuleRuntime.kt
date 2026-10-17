package ru.hollowhorizon.hollowengine.client.vfx

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.*
import ru.hollowhorizon.hollowengine.common.vfx.modules.*
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A behavior attached to a live emitter.
 */
interface VfxModule {
    /** Once, as the particle appears. The emitter context is already filled for it. */
    fun onSpawn(emitter: VfxEmitter, slot: Int) = Unit

    /** Once per step, before any particle. */
    fun beginStep(emitter: VfxEmitter, dt: Float) = Unit

    /** Before the particle is integrated: forces and frames. */
    fun onStep(emitter: VfxEmitter, slot: Int, dt: Float) = Unit

    /** After the particle moved: collisions and anything that reacts to it. */
    fun onMoved(emitter: VfxEmitter, slot: Int, dt: Float) = Unit
}

/**
 * What a module is built from, besides its own spec.
 */
class VfxModuleContext(
    val emitter: VfxEmitterSpec,
    val expressions: VfxExpressions,
    val drive: (field: String) -> VfxDrive?,
) {
    fun scalar(field: String, value: VfxValue, default: Float = 0f): VfxFloatSampler = VfxSamplers.driven(
        VfxSamplers.scalar(value, expressions, default, VfxRangeMode.PER_PARTICLE, field.hashCode()),
        drive(field),
    )

    fun vector(field: String, value: VfxVec3Value, default: Float = 0f): VfxVec3Sampler =
        VfxSamplers.vec3(value, expressions, default, VfxRangeMode.PER_PARTICLE, field.hashCode(), drive(field))
}

/** Turns one module spec into something that runs. Returning null leaves the module out. */
fun interface VfxModuleFactory {
    fun create(spec: VfxModuleSpec, context: VfxModuleContext): VfxModule?
}

/**
 * What each kind of module does at runtime, keyed by the same id the spec is registered under.
 */
object VfxModuleRuntimes {
    val point = ExtensionPoints.create<VfxModuleFactory>("hollowengine:vfx/module_runtimes".rl)

    init {
        register("hollowengine:vfx/velocity_over_lifetime") { spec, context ->
            VfxVelocityOverLifetimeModule(spec as VfxVelocityOverLifetimeSpec, context)
        }
        register("hollowengine:vfx/force") { spec, context ->
            VfxForceModule(spec as VfxForceSpec, context)
        }
        register("hollowengine:vfx/noise") { spec, context ->
            VfxNoiseModule(spec as VfxNoiseSpec, context)
        }
        register("hollowengine:vfx/collision") { spec, _ ->
            VfxCollisionModule(spec as VfxCollisionSpec)
        }
        register("hollowengine:vfx/uv_animation") { spec, _ ->
            val flipbook = spec as VfxUvAnimationSpec
            if (flipbook.frameCount <= 1) null else VfxUvAnimationModule(flipbook)
        }
    }

    fun register(typeId: String, factory: VfxModuleFactory): ExtensionHandle = point.register(typeId.rl, factory)

    fun create(spec: VfxModuleSpec, context: VfxModuleContext): VfxModule? {
        val type = VfxModuleTypes.of(spec) ?: return null
        return point.find(type.key)?.create(spec, context)
    }
}

private class VfxVelocityOverLifetimeModule(
    spec: VfxVelocityOverLifetimeSpec,
    context: VfxModuleContext,
) : VfxModule {
    private val velocity = context.vector("velocity", spec.velocity)

    /** Whether the authored frame and simulation frame are same one. */
    private val sameSpace = spec.space == context.emitter.space || context.emitter.space == VfxSimulationSpace.LOCAL

    private val scratch = MutableVec3f()
    private val turned = MutableVec3f()

    override fun onStep(emitter: VfxEmitter, slot: Int, dt: Float) {
        velocity.eval(emitter.context, scratch)
        val offset = if (sameSpace) scratch else emitter.frame.transformDirection(scratch, turned)
        emitter.stepVelocityX += offset.x
        emitter.stepVelocityY += offset.y
        emitter.stepVelocityZ += offset.z
    }
}

/**
 * A force placed in emitter frame.
 */
private class VfxForceModule(private val spec: VfxForceSpec, context: VfxModuleContext) : VfxModule {
    private val strength = context.scalar("strength", spec.strength)
    private val radius = context.scalar("radius", spec.radius)

    private val center = MutableVec3f()
    private val axis = MutableVec3f()
    private var reach = 0f
    private var hasAxis = false

    override fun beginStep(emitter: VfxEmitter, dt: Float) {
        emitter.nodeToSimPoint(spec.center, center)
        emitter.nodeToSimDirection(spec.direction, axis)
        hasAxis = axis.sqrLength() > EPSILON
        if (hasAxis) axis.norm()
        reach = radius.eval(emitter.context)
    }

    override fun onStep(emitter: VfxEmitter, slot: Int, dt: Float) {
        val force = strength.eval(emitter.context)
        if (force == 0f) return

        val particles = emitter.particles
        val toX = particles.positionX[slot] - center.x
        val toY = particles.positionY[slot] - center.y
        val toZ = particles.positionZ[slot] - center.z
        val distance = sqrt(toX * toX + toY * toY + toZ * toZ)
        if (reach > 0f && distance > reach) return

        val fade = if (!spec.falloff || reach <= 0f) 1f else (1f - distance / reach).coerceIn(0f, 1f)
        val push = force * fade * dt

        when (spec.kind) {
            VfxForceKind.DIRECTIONAL -> {
                if (!hasAxis) return
                particles.velocityX[slot] += axis.x * push
                particles.velocityY[slot] += axis.y * push
                particles.velocityZ[slot] += axis.z * push
            }

            VfxForceKind.POINT -> {
                if (distance <= EPSILON) return
                particles.velocityX[slot] += toX / distance * push
                particles.velocityY[slot] += toY / distance * push
                particles.velocityZ[slot] += toZ / distance * push
            }

            VfxForceKind.VORTEX -> {
                if (!hasAxis) return
                val swirlX = axis.y * toZ - axis.z * toY
                val swirlY = axis.z * toX - axis.x * toZ
                val swirlZ = axis.x * toY - axis.y * toX
                val length = sqrt(swirlX * swirlX + swirlY * swirlY + swirlZ * swirlZ)
                if (length <= EPSILON) return
                particles.velocityX[slot] += swirlX / length * push
                particles.velocityY[slot] += swirlY / length * push
                particles.velocityZ[slot] += swirlZ / length * push
            }

            VfxForceKind.DRAG -> {
                val keep = (1f - push).coerceIn(0f, 1f)
                particles.velocityX[slot] *= keep
                particles.velocityY[slot] *= keep
                particles.velocityZ[slot] *= keep
            }
        }
    }

    private companion object {
        const val EPSILON = 1.0e-6f
    }
}

private class VfxNoiseModule(spec: VfxNoiseSpec, context: VfxModuleContext) : VfxModule {
    private val strength = context.scalar("strength", spec.strength)
    private val frequency = context.scalar("frequency", spec.frequency, 0.5f)
    private val scrollSpeed = spec.scrollSpeed
    private val octaves = spec.octaves.coerceIn(1, 4)

    override fun onStep(emitter: VfxEmitter, slot: Int, dt: Float) {
        val force = strength.eval(emitter.context)
        if (force == 0f) return

        val cells = frequency.eval(emitter.context)
        val particles = emitter.particles
        val x = particles.positionX[slot] * cells
        val y = particles.positionY[slot] * cells
        val z = particles.positionZ[slot] * cells
        val scroll = emitter.instance.time * scrollSpeed

        val push = force * dt
        particles.velocityX[slot] += VfxNoise.fractal(x + scroll, y, z, emitter.noiseSeed(0), octaves) * push
        particles.velocityY[slot] += VfxNoise.fractal(x, y + scroll, z, emitter.noiseSeed(1), octaves) * push
        particles.velocityZ[slot] += VfxNoise.fractal(x, y, z + scroll, emitter.noiseSeed(2), octaves) * push
    }
}

/**
 * Stops particles at blocks.
 */
private class VfxCollisionModule(private val spec: VfxCollisionSpec) : VfxModule {
    private val scratch = MutableVec3f()

    override fun onMoved(emitter: VfxEmitter, slot: Int, dt: Float) {
        val particles = emitter.particles

        val x = particles.positionX[slot]
        val y = particles.positionY[slot]
        val z = particles.positionZ[slot]
        if (!isSolidAt(emitter, x, y, z, scratch)) return
        emitter.particleCollided(slot, dt)

        if (spec.action == VfxCollisionAction.DIE) {
            particles.age[slot] = particles.lifetime[slot]
            return
        }

        var hit = false
        if (isSolidAt(emitter, x, emitter.previousY, emitter.previousZ, scratch)) {
            particles.positionX[slot] = emitter.previousX
            particles.velocityX[slot] = bounce(particles.velocityX[slot])
            hit = true
        }
        if (isSolidAt(emitter, particles.positionX[slot], y, emitter.previousZ, scratch)) {
            particles.positionY[slot] = emitter.previousY
            particles.velocityY[slot] = bounce(particles.velocityY[slot])
            hit = true
        }
        if (isSolidAt(emitter, particles.positionX[slot], particles.positionY[slot], z, scratch)) {
            particles.positionZ[slot] = emitter.previousZ
            particles.velocityZ[slot] = bounce(particles.velocityZ[slot])
            hit = true
        }

        if (!hit) {
            particles.positionX[slot] = emitter.previousX
            particles.positionY[slot] = emitter.previousY
            particles.positionZ[slot] = emitter.previousZ
            particles.velocityX[slot] = bounce(particles.velocityX[slot])
            particles.velocityY[slot] = bounce(particles.velocityY[slot])
            particles.velocityZ[slot] = bounce(particles.velocityZ[slot])
        }

        if (spec.friction < 1f) {
            particles.velocityX[slot] *= spec.friction
            particles.velocityY[slot] *= spec.friction
            particles.velocityZ[slot] *= spec.friction
        }
        if (spec.lifetimeLoss > 0f) particles.age[slot] += spec.lifetimeLoss
    }

    private fun bounce(velocity: Float): Float =
        if (spec.action == VfxCollisionAction.BOUNCE) -velocity * spec.bounce else 0f

    private fun isSolidAt(emitter: VfxEmitter, x: Float, y: Float, z: Float, scratch: MutableVec3f): Boolean {
        scratch.set(x, y, z)
        emitter.simToRender.transform(scratch, 1f)
        val origin = emitter.instance.origin
        val radius = spec.radius
        return emitter.instance.environment.isSolid(
            origin.x + scratch.x,
            origin.y + scratch.y - radius,
            origin.z + scratch.z,
        )
    }
}

private class VfxUvAnimationModule(private val spec: VfxUvAnimationSpec) : VfxModule {
    private val frames = spec.frameCount

    override fun onSpawn(emitter: VfxEmitter, slot: Int) {
        if (spec.mode != VfxUvMode.RANDOM_FRAME) return
        emitter.particles.frame[slot] =
            floor(emitter.particles.random[slot] * frames).coerceIn(0f, (frames - 1).toFloat())
    }

    override fun onStep(emitter: VfxEmitter, slot: Int, dt: Float) {
        val particles = emitter.particles
        val index = when (spec.mode) {
            VfxUvMode.RANDOM_FRAME -> return
            VfxUvMode.OVER_LIFETIME -> emitter.context.progress * spec.cycles * frames
            VfxUvMode.FPS -> particles.age[slot] * spec.fps
        }
        particles.frame[slot] = floor(index).mod(frames.toFloat())
    }
}
