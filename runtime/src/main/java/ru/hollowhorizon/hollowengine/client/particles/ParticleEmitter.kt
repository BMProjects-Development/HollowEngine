package ru.hollowhorizon.hollowengine.client.particles

import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.bedrock.Query
import ru.hollowhorizon.hollowengine.client.models.bedrock.VariablesMap
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.client.particles.file.BedrockParticleFile
import ru.hollowhorizon.hollowengine.client.sounds.ClientSounds
import ru.hollowhorizon.hollowengine.client.sounds.SoundEmitter
import ru.hollowhorizon.hollowengine.client.sounds.SoundEmitterState
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.client.utils.math.rotateSelfBy
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

class ParticleEmitter(
    val system: ParticleSystem,
    val effect: ParticleEffect,
    val sourceContext: AnimatorEvaluationContext,
    var position: Vec3f,
    var rotation: QuatF,
    var velocity: Vec3f,
    val transform: Transform?,
    val offset: Vec3f? = null,
) {
    val particles = arrayListOf<BedrockParticle>()
    private val components = effect.components
    private val curveVariables = CurveVariables({ context }, effect.curves)
    private val variables = VariablesMap().fallbackBackTo(curveVariables)
    val context: BedrockContext = BedrockContext(object : Query by sourceContext {
        // Particle expressions use the host clock, independent of any model clip sampled on the context.
        override val anim_time: Float get() = sourceContext.life_time
        override fun hasValue(name: String): Boolean = name == "anim_time" || sourceContext.hasValue(name)
    }, variables)
    val sourceEntity get() = sourceContext.entity

    init {
        updateSource(0f)
        variables["entity_scale"] = 1f
        components.emitterInitialization?.creationExpression?.eval(context)
    }

    private var firedCreationEvents = false
    private var firedExpirationEvents = false

    private var age: Float by variables.getOrPut("emitter_age", 0f)
    private var activeTime: Float by variables.getOrPut("emitter_lifetime", 0f)
    private var sleepTime: Float = 0f
    private var maxParticles: Float = 0f
    private var cooldown: Float = 0f
    private var nextTimelineEvent: Map.Entry<Float, List<String>>? = null

    fun startLoop(timeSince: Float) {
        for (i in 1..4) variables["emitter_random_$i"] = system.random.nextFloat()

        age = 0f
        activeTime = components.emitterLifetimeLooping?.activeTime?.eval(context)
            ?: components.emitterLifetimeOnce?.activeTime?.eval(context) ?: Float.POSITIVE_INFINITY
        sleepTime = components.emitterLifetimeLooping?.sleepTime?.eval(context) ?: 0f

        repeat(components.emitterRateInstant?.numParticles?.eval(context)?.toInt() ?: 0) {
            emit(timeSince)
        }

        maxParticles = components.emitterRateSteady?.maxParticles?.eval(context) ?: Float.POSITIVE_INFINITY

        cooldown = components.emitterRateSteady?.spawnRate?.eval(context)?.let { 1 / it }
            ?: Float.POSITIVE_INFINITY

        if (!firedCreationEvents) {
            firedCreationEvents = true
            fire(timeSince, components.emitterLifetimeEvents.creationEvents, null)
        }

        nextTimelineEvent = components.emitterLifetimeEvents.timeline.lowestEntry()
    }

    fun skip(dt: Float) {
        age += dt
    }

    fun update(dt: Float): Boolean {
        updateSource(dt)
        context.temporaries.clear()
        val alive = doUpdate(dt)

        if (!alive && !firedExpirationEvents) {
            firedExpirationEvents = true
            fire(0f, components.emitterLifetimeEvents.expirationEvents, null)
        }

        val expiredParticles = particles.filter { !it.update(dt) }.onEach(::onRemove)

        particles.removeAll(expiredParticles.toSet())

        return alive
    }

    private fun updateSource(dt: Float) {
        val entity = sourceEntity ?: return
        fillAnimationVariables(sourceContext, entity, TickHandler.partialTick)
        sourceContext.deltaTime = dt
    }

    private fun doUpdate(dt: Float): Boolean {
        age += dt

        curveVariables.update()
        components.emitterInitialization?.perUpdateExpression?.eval(context)

        transform?.let {
            position = it.position.add(offset?.rotateBy(rotation) ?: Vec3f.ZERO, MutableVec3f())
            rotation = it.rotation
            velocity = it.velocity

            if (!transform.isValid) return false
        }

        fireTimelineEvents()

        components.emitterLifetimeExpression?.let { config ->
            if (config.expirationExpression.eval(context) != 0f) return false
            if (config.activationExpression.eval(context) == 0f) return true
        }

        if (age > activeTime) {
            if (components.emitterLifetimeOnce != null) return false

            val loopTime = age - activeTime - sleepTime
            if (loopTime < 0) return true
            startLoop(loopTime)
        }

        cooldown -= dt
        while (cooldown < 0) {
            if (particles.size < maxParticles) {
                val emitTime = -cooldown
                age -= emitTime
                emit(emitTime)
                cooldown += components.emitterRateSteady?.spawnRate?.eval(context)?.let { 1 / it }
                    ?: Float.POSITIVE_INFINITY
                age += emitTime
            } else {
                cooldown = 0f
            }
        }

        return true
    }

    private fun emit(dt: Float, velocity: Boolean = false) {
        val localSpace = if (components.emitterLocalSpace?.position == true) transform else null

        val particle = BedrockParticle(this, localSpace)

        particle.emit(velocity)

        addParticle(particle)

        particle.update(dt)
    }

    private fun addParticle(particle: BedrockParticle) {
        particles.add(particle)

        val effect = particle.emitter.effect
        val renderPass = effect.renderPass ?: return
        if (effect.components.particleAppearanceBillboard == null) return

        system.billboardRenderPasses.getOrPut(renderPass, ::mutableSetOf).add(particle)
    }

    fun onRemove(particle: BedrockParticle) {
        val effect = particle.emitter.effect
        val renderPass = effect.renderPass ?: return
        if (effect.components.particleAppearanceBillboard == null) return

        val renderPassSet = system.billboardRenderPasses[renderPass] ?: return
        renderPassSet.remove(particle)
        if (renderPassSet.isNotEmpty()) return

        system.billboardRenderPasses.remove(renderPass)
    }

    private fun fireTimelineEvents() {
        while (true) {
            val (time, events) = nextTimelineEvent ?: return
            val timeSinceEvent = age - time
            if (timeSinceEvent < 0) return

            fire(timeSinceEvent, events, null)

            nextTimelineEvent = components.emitterLifetimeEvents.timeline.higherEntry(time)
        }
    }

    fun fire(timeSince: Float, events: List<String>, particle: BedrockParticle?) =
        events.forEach { fire(timeSince, it, particle) }

    fun fire(timeSince: Float, eventName: String, particle: BedrockParticle?) {
        val event = effect.events[eventName] ?: return
        fire(timeSince, event, particle)
    }

    fun fire(timeSince: Float, event: BedrockParticleFile.Event, particle: BedrockParticle?) {
        event.sequence?.forEach { fire(timeSince, it, particle) }

        event.randomize?.let { options ->
            val weights = options.sumOf { it.weight.toDouble() }
            var choice = system.random.nextFloat() * weights
            for (option in options) {
                choice -= option.weight
                if (choice <= 0) {
                    fire(timeSince, option.value, particle)
                    break
                }
            }
        }

        event.expression?.let { expr ->
            age -= timeSince
            expr.eval(context)
            age += timeSince
        }

        event.particle?.let { config ->
            val targetEffect = effect.referencedEffects[config.effect] ?: return@let

            val targetEmitter = if (config.type.isBound && transform != null) {
                ParticleEmitter(
                    system,
                    targetEffect,
                    sourceContext,
                    particle?.globalPosition ?: position,
                    rotation,
                    particle?.globalVelocity ?: velocity,
                    transform,
                    particle?.globalPosition?.minus(transform.position)?.rotateSelfBy(transform.rotation.inverted())
                        ?: offset,
                )
            } else {
                ParticleEmitter(
                    system,
                    targetEffect,
                    sourceContext,
                    particle?.globalPosition ?: position,
                    QuatF.IDENTITY,
                    particle?.globalVelocity ?: velocity,
                    null,
                    null,
                )
            }
            config.preEffectExpression.eval(targetEmitter.context)
            if (config.type.isParticle) {
                targetEmitter.emit(timeSince, config.type.inheritVelocity)
            } else {
                system.emitters.add(targetEmitter)
                targetEmitter.startLoop(timeSince)
                targetEmitter.update(timeSince)
            }
        }

        event.sound?.let { config ->
            val targetSound = effect.referencedSounds[config.eventName] ?: return@let

            ClientSounds.play(targetSound, emitter = SoundEmitter {
                if (transform == null) SoundEmitterState.Listener else {
                    val p = transform.position
                    SoundEmitterState.Available(Vec3(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()))
                }
            })
        }
    }
}
