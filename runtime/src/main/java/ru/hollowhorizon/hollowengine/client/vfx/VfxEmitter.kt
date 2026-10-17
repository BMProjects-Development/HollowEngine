package ru.hollowhorizon.hollowengine.client.vfx

import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.*
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxUvAnimationSpec
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One emitter of a playing effect. It only simulates; the renderers under its node draw.
 */
class VfxEmitter(
    val spec: VfxEmitterSpec,
    val node: VfxNodeRuntime,
) : VfxNodeBehavior {
    val instance: VfxInstance get() = node.instance
    private val expressions: VfxExpressions = node.expressions
    private val seed: Int = node.seed

    val particles = VfxParticles(spec.emission.maxParticles.coerceIn(1, MAX_PARTICLES_PER_EMITTER))

    val frame: VfxFrame get() = node.frame

    val simToRender: MutableMat4f = MutableMat4f().setIdentity()

    val context = VfxEvalContext()

    private var random = Random(seed)

    private val rate = scalar(VfxProperty.RATE, spec.emission.rate, 0f)
    private val shape = VfxEmitterShape(spec.shape, node)

    private val lifetime = scalar(VfxProperty.LIFETIME, spec.spawn.lifetime, 1f)
    private val speed = scalar(VfxProperty.SPEED, spec.spawn.speed, 0f)
    private val offset = vector(VfxProperty.OFFSET, spec.spawn.offset, 0f)
    private val inheritVelocity = scalar(VfxProperty.INHERIT_VELOCITY, spec.spawn.inheritVelocity, 0f)

    private val gravity = scalar(VfxProperty.GRAVITY, spec.motion.gravity, 0f, VfxRangeMode.PER_PARTICLE)
    private val drag = scalar(VfxProperty.DRAG, spec.motion.drag, 0f, VfxRangeMode.PER_PARTICLE)

    private val modules: List<VfxModule> = spec.modules
        .filter { it.enabled }
        .mapNotNull { module ->
            VfxModuleRuntimes.create(module, VfxModuleContext(spec, expressions) { field ->
                node.drive(VfxProperty.module(module.id, field))
            })
        }

    /** Set when this emitter spawns from the particles of the emitter it sits under. */
    private val subInput: VfxSubEmitterInput? = spec.subEmission?.let { sub ->
        node.parent?.emitter?.let { source -> VfxSubEmitterInput(sub, source, node) }
    }

    /** The sub-emitters directly under this emitter; its children exist only after it does. */
    private val subEmitters: List<VfxSubEmitterInput> by lazy {
        node.children.mapNotNull { child -> child.emitter?.subInput?.takeIf { it.source === this } }
    }

    /** What the renderers under this emitter give its particles, read while it simulates. */
    private val looks = ArrayList<VfxParticleLook>()

    /** Called by a renderer under this emitter as it is built. */
    fun addLook(look: VfxParticleLook) {
        looks += look
    }

    /** The flipbook grid the renderer cuts the material region into; one cell without a flipbook. */
    val uvColumns: Int
    val uvRows: Int

    init {
        val flipbook = spec.modules.filterIsInstance<VfxUvAnimationSpec>().firstOrNull { it.enabled }
        uvColumns = flipbook?.columns?.coerceAtLeast(1) ?: 1
        uvRows = flipbook?.rows?.coerceAtLeast(1) ?: 1
    }

    /** An emitter whose node is off neither emits nor draws, but keeps the particles it has. */
    val enabled: Boolean get() = node.enabled

    var age: Float = 0f
        private set

    /** Seconds until the next loop starts, while the emitter is sleeping between loops. */
    private var sleepLeft: Float = 0f
    private var spawnDebt: Float = 0f
    private var born: Int = 0
    private var stopping: Boolean = false

    /** Set while the effect is held: the emitter makes nothing and its own clock stands, while what it made lives on. */
    private var held: Boolean = false

    /** Whether this emitter will ever produce anything again. */
    val isFinished: Boolean
        get() {
            if (particles.count > 0) return false
            val source = subInput?.source ?: return stopping || isExpired()
            return stopping || source.isFinished
        }

    override val isIdle: Boolean get() = particles.count == 0

    private val spawnOrigin = MutableVec3f()
    private var spawnRotation: QuatF = QuatF.IDENTITY
    private val spawnScale = MutableVec3f(Vec3f.ONES)

    private val scratchPosition = MutableVec3f()
    private val scratchDirection = MutableVec3f()
    private val scratchVector = MutableVec3f()

    /** Extra velocity the modules asked for, for this particle and this step only. */
    var stepVelocityX: Float = 0f
    var stepVelocityY: Float = 0f
    var stepVelocityZ: Float = 0f

    /** Where the particle was before it moved, so a collision can put it back. */
    var previousX: Float = 0f
    var previousY: Float = 0f
    var previousZ: Float = 0f

    init {
        restart()
    }

    /** Stops emitting; whatever is alive lives out its lifetime, unless [immediate] drops it at once. */
    override fun stop(immediate: Boolean) {
        if (immediate) particles.clear()
        stopping = true
    }

    /**
     * Holds the emitter: it makes nothing new and its loops stand where they are, while its particles live on. A
     * sub-emitter is not held, as what it makes comes from those particles.
     */
    fun hold() {
        held = true
    }

    /**
     * Goes on after [hold], from where the loop stood. An emitter that had already run its course without looping
     * runs it again, since it is let go of to play, and would otherwise make nothing.
     */
    fun release() {
        if (!held) return
        held = false
        if (subInput == null && !spec.emission.loop && isExpired()) startLoop()
    }

    /**
     * Puts the emitter back where it was when the effect was created.
     */
    override fun restart() {
        particles.clear()
        subInput?.events?.clear()
        random = Random(seed)
        context.rng = random
        context.variables.clear()
        age = 0f
        sleepLeft = 0f
        born = 0
        stopping = false
        held = false
        spawnDebt = spec.emission.burst.toFloat()
        rollEmitterRandoms()
    }

    private fun rollEmitterRandoms() {
        repeat(4) { context.variables["emitter_random_${it + 1}"] = random.nextFloat() }
    }

    /**
     * Recomputes how the emitter maps to what is drawn, after the node has placed its frame.
     */
    override fun place() {
        when (spec.space) {
            VfxSimulationSpace.LOCAL -> {
                frame.toFollowedMatrix(simToRender, spec.inheritRotation, spec.inheritScale)
                spawnOrigin.set(Vec3f.ZERO)
                spawnRotation = if (spec.inheritRotation) QuatF.IDENTITY else frame.rotation
                spawnScale.set(if (spec.inheritScale) Vec3f.ONES else frame.scale)
            }

            VfxSimulationSpace.WORLD -> {
                val offset = instance.originMinusAnchor
                simToRender.setIdentity().translate(-offset.x, -offset.y, -offset.z)
                spawnOrigin.set(frame.position).add(offset)
                spawnRotation = frame.rotation
                spawnScale.set(frame.scale)
            }
        }
        subInput?.place(simToRender)
    }

    /**
     * Advances the emitter by [dt] seconds, split into steps no longer than the emitter asked for.
     */
    override fun update(dt: Float) {
        if (dt <= 0f) return

        val maxStep = spec.maxStep.coerceAtLeast(MIN_STEP)
        val steps = ceil(dt / maxStep).toInt().coerceIn(1, MAX_SUBSTEPS)
        val step = dt / steps
        repeat(steps) { advance(step) }
    }

    private fun advance(dt: Float) {
        val input = subInput
        if (input != null) {
            age += dt
            if (enabled && !stopping) spawnEvents(input)
            input.events.clear()
        } else if (!held) {
            age += dt
            advanceEmission(dt)
        }

        fillEmitterContext()
        modules.forEach { it.beginStep(this, dt) }
        simulate(dt)
    }

    private fun advanceEmission(dt: Float) {
        if (sleepLeft > 0f) {
            sleepLeft -= dt
            if (sleepLeft <= 0f) startLoop()
        } else if (isExpired()) {
            val emission = spec.emission
            if (emission.loop && !stopping) {
                sleepLeft = emission.loopDelay
                if (sleepLeft <= 0f) startLoop()
            }
        }

        if (enabled && !stopping && !isExpired() && sleepLeft <= 0f) emit(dt)
    }

    /** Spawns what the emitter this one listens to handed over since the last step. */
    private fun spawnEvents(input: VfxSubEmitterInput) {
        val events = input.events
        for (event in 0 until events.size) {
            repeat(events.count(event)) {
                if (!spawn(input, event)) return
            }
        }
    }

    private fun startLoop() {
        age = 0f
        born = 0
        spawnDebt += spec.emission.burst.toFloat()
        rollEmitterRandoms()
    }

    private fun isExpired(): Boolean {
        val duration = spec.emission.duration
        return duration > 0f && age >= duration
    }

    private fun emit(dt: Float) {
        fillEmitterContext()
        val perSecond = rate.eval(context)
        if (perSecond > 0f) spawnDebt += perSecond * dt

        while (spawnDebt >= 1f) {
            spawnDebt -= 1f
            if (!spawn()) {
                spawnDebt = 0f
                return
            }
        }
    }

    /**
     * Puts one particle into the world. Returns false when the emitter is full. A sub-emitter spawns
     * around [event] of [input] instead of around its own origin.
     */
    private fun spawn(input: VfxSubEmitterInput? = null, event: Int = -1): Boolean {
        if (!instance.requestParticle()) return false

        val slot = particles.allocate()
        if (slot < 0) return false

        fillEmitterContext()
        val events = input?.events
        context.particleRandom = random.nextFloat()
        context.parentRandom = events?.random(event) ?: 0f
        context.particleIndex = born.toFloat()
        context.age = 0f
        context.progress = 0f
        context.speed = 0f

        val life = lifetime.eval(context).coerceAtLeast(MIN_LIFETIME)
        context.lifetime = life

        shape.pick(scratchPosition, scratchDirection, context, random)
        offset.eval(context, scratchVector)
        scratchPosition.add(scratchVector)

        val launch = speed.eval(context)
        scratchDirection.set(
            scratchDirection.x * launch,
            scratchDirection.y * launch,
            scratchDirection.z * launch,
        )

        applySpawnSpace(scratchPosition, point = events == null)
        applySpawnSpace(scratchDirection, point = false)
        if (events != null) {
            scratchPosition.set(
                scratchPosition.x + events.x(event),
                scratchPosition.y + events.y(event),
                scratchPosition.z + events.z(event),
            )
            scratchDirection.set(
                scratchDirection.x + events.velocityX(event),
                scratchDirection.y + events.velocityY(event),
                scratchDirection.z + events.velocityZ(event),
            )
        }

        particles.positionX[slot] = scratchPosition.x
        particles.positionY[slot] = scratchPosition.y
        particles.positionZ[slot] = scratchPosition.z

        val inherit = if (events == null) inheritVelocity.eval(context) else 0f
        particles.velocityX[slot] = scratchDirection.x + instance.velocityX * inherit
        particles.velocityY[slot] = scratchDirection.y + instance.velocityY * inherit
        particles.velocityZ[slot] = scratchDirection.z + instance.velocityZ * inherit

        particles.age[slot] = 0f
        particles.lifetime[slot] = life
        particles.random[slot] = context.particleRandom
        particles.index[slot] = context.particleIndex
        particles.parentRandom[slot] = context.parentRandom
        particles.frame[slot] = 0f

        looks.forEach { it.spawn(slot, context) }

        particles.light[slot] = instance.lightAt(this, slot)

        born++
        modules.forEach { it.onSpawn(this, slot) }
        fire(VfxParticleEvent.SPAWN, slot, 0f)
        return true
    }

    /** Hands the particle in [slot] to the sub-emitters that listen for [event]. */
    private fun fire(event: VfxParticleEvent, slot: Int, dt: Float) {
        subEmitters.forEach { it.take(event, slot, dt) }
    }

    /** Called by a module when the particle in [slot] hits a block. */
    fun particleCollided(slot: Int, dt: Float) {
        if (subEmitters.isNotEmpty()) fire(VfxParticleEvent.COLLISION, slot, dt)
    }

    private fun die(slot: Int, dt: Float) {
        if (subEmitters.isNotEmpty()) {
            fillParticleContext(slot)
            fire(VfxParticleEvent.DEATH, slot, dt)
        }
        particles.kill(slot)
    }

    private fun simulate(dt: Float) {
        var slot = 0
        while (slot < particles.count) {
            particles.age[slot] += dt
            if (particles.age[slot] >= particles.lifetime[slot]) {
                die(slot, dt)
                continue
            }

            fillParticleContext(slot)

            stepVelocityX = 0f
            stepVelocityY = 0f
            stepVelocityZ = 0f
            modules.forEach { it.onStep(this, slot, dt) }

            val fall = gravity.eval(context)
            if (fall != 0f) particles.velocityY[slot] -= fall * dt

            val friction = drag.eval(context)
            if (friction > 0f) {
                val keep = (1f - friction * dt).coerceIn(0f, 1f)
                particles.velocityX[slot] *= keep
                particles.velocityY[slot] *= keep
                particles.velocityZ[slot] *= keep
            }

            previousX = particles.positionX[slot]
            previousY = particles.positionY[slot]
            previousZ = particles.positionZ[slot]

            particles.positionX[slot] += (particles.velocityX[slot] + stepVelocityX) * dt
            particles.positionY[slot] += (particles.velocityY[slot] + stepVelocityY) * dt
            particles.positionZ[slot] += (particles.velocityZ[slot] + stepVelocityZ) * dt

            modules.forEach { it.onMoved(this, slot, dt) }

            if (slot < particles.count && particles.age[slot] >= particles.lifetime[slot]) {
                die(slot, dt)
                continue
            }

            looks.forEach { it.step(slot, context) }
            if (subEmitters.isNotEmpty()) fire(VfxParticleEvent.ALIVE, slot, dt)

            if (instance.readsLight) particles.light[slot] = instance.lightAt(this, slot)
            slot++
        }
    }

    /** Fills context with what does not depend on a particular particle. */
    fun fillEmitterContext() {
        context.emitterAge = age
        context.emitterCount = particles.count.toFloat()
        context.effectTime = instance.time
        context.partialTick = instance.partialTick
        context.gameTime = instance.gameTime
        context.data = instance.data
    }

    /** Fills context for one particle, which is what every sampler reads. */
    fun fillParticleContext(slot: Int) {
        fillEmitterContext()
        val life = particles.lifetime[slot]
        context.age = particles.age[slot]
        context.lifetime = life
        context.progress = if (life > 0f) (particles.age[slot] / life).coerceIn(0f, 1f) else 1f
        context.positionX = particles.positionX[slot]
        context.positionY = particles.positionY[slot]
        context.positionZ = particles.positionZ[slot]
        val vx = particles.velocityX[slot]
        val vy = particles.velocityY[slot]
        val vz = particles.velocityZ[slot]
        context.velocityX = vx
        context.velocityY = vy
        context.velocityZ = vz
        context.speed = sqrt(vx * vx + vy * vy + vz * vz)
        context.particleRandom = particles.random[slot]
        context.particleIndex = particles.index[slot]
        context.parentRandom = particles.parentRandom[slot]
    }

    /** A particle position in effect space, which is what the world is read in. */
    fun toEffectSpace(slot: Int, into: MutableVec3f): MutableVec3f {
        into.set(particles.positionX[slot], particles.positionY[slot], particles.positionZ[slot])
        return simToRender.transform(into, 1f)
    }

    /** Turns a direction of effect space into the space this emitter simulates in. */
    fun toSimDirection(direction: Vec3f, into: MutableVec3f): MutableVec3f = when (spec.space) {
        VfxSimulationSpace.WORLD -> into.set(direction)
        VfxSimulationSpace.LOCAL -> {
            val turned = direction.rotateBy(frame.rotation.inverted())
            into.set(turned.x, turned.y, turned.z)
        }
    }

    /** A point of the emitter frame, in the space particles live in; how a module places a force. */
    fun nodeToSimPoint(point: Vec3f, into: MutableVec3f): MutableVec3f {
        into.set(point)
        applySpawnSpace(into, point = true)
        return into
    }

    fun nodeToSimDirection(direction: Vec3f, into: MutableVec3f): MutableVec3f {
        into.set(direction)
        applySpawnSpace(into, point = false)
        return into
    }

    /** Moves a birth position or direction out of node space into the space particles live in. */
    private fun applySpawnSpace(vector: MutableVec3f, point: Boolean) {
        vector.set(vector.x * spawnScale.x, vector.y * spawnScale.y, vector.z * spawnScale.z)
        if (spawnRotation !== QuatF.IDENTITY) {
            val rotated = (vector as Vec3f).rotateBy(spawnRotation)
            vector.set(rotated.x, rotated.y, rotated.z)
        }
        if (point) vector.add(spawnOrigin)
    }

    fun noiseSeed(axis: Int): Int = seed + axis * 6151

    private fun scalar(
        property: VfxProperty,
        value: VfxValue,
        default: Float,
        range: VfxRangeMode = VfxRangeMode.FRESH,
    ): VfxFloatSampler = VfxSamplers.driven(
        VfxSamplers.scalar(value, expressions, default, range, property.hashCode()),
        node.drive(property),
    )

    private fun vector(
        property: VfxProperty,
        value: VfxVec3Value,
        default: Float,
        range: VfxRangeMode = VfxRangeMode.FRESH,
    ): VfxVec3Sampler = VfxSamplers.vec3(value, expressions, default, range, property.hashCode(), node.drive(property))

    companion object {
        private const val MIN_STEP = 1f / 240f
        private const val MAX_SUBSTEPS = 8
        private const val MIN_LIFETIME = 0.01f

        /** A ceiling on what one emitter may ask for, whatever the file says. */
        const val MAX_PARTICLES_PER_EMITTER = 20_000
    }
}
