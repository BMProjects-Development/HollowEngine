package ru.hollowhorizon.hollowengine.client.particles

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.util.Mth
import ru.hollowhorizon.hollowengine.client.models.bedrock.*
import ru.hollowhorizon.hollowengine.client.particles.file.ParticleComponents
import ru.hollowhorizon.hollowengine.client.utils.color
import ru.hollowhorizon.hollowengine.client.utils.math.*
import ru.hollowhorizon.hollowengine.client.utils.uv
import ru.hollowhorizon.hollowengine.client.utils.uv2
import ru.hollowhorizon.hollowengine.client.utils.vertex
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.*
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.sqrt

class BedrockParticle(
    val emitter: ParticleEmitter,
    val localSpace: Transform?,
) {
    private val components = emitter.effect.components
    private val curveVariables = CurveVariables({ context }, emitter.effect.curves)
    private val variables = VariablesMap().fallbackBackTo(curveVariables).fallbackBackTo(emitter.context.variables)
    private val context: BedrockContext = BedrockContext(Query.EMPTY, variables)

    private var firedCreationEvents = false
    private var firedExpirationEvents = false
    private var nextTimelineEvent: Map.Entry<Float, List<String>>? = null

    private var age: Float by variables.getOrPut("particle_age", 0f)
    private var lifetime: Float by variables.getOrPut("particle_lifetime", 0f)

    init {
        for (i in 1..4) variables["particle_random_$i"] = emitter.system.random.nextFloat()

        age = 0f
        lifetime = components.particleLifetimeExpression?.maxLifetime?.eval(context) ?: 0f
        nextTimelineEvent = components.particleLifetimeEvents.timeline.lowestEntry()
    }

    val position = MutableVec3f()
    val velocity = MutableVec3f()
    val direction = MutableVec3f()

    val globalPosition: Vec3f
        get() = if (localSpace != null) position.rotateBy(localSpace.rotation)
            .add(localSpace.position, MutableVec3f()) else position

    val globalVelocity: Vec3f
        get() = if (localSpace != null) velocity.rotateBy(localSpace.rotation) else velocity

    val emitterRotationOnEmit = emitter.rotation

    var rotationAngle = components.particleInitialSpin?.rotation?.eval(context) ?: 0f
    var rotationRate = components.particleInitialSpin?.rotationRate?.eval(context) ?: 0f

    var billboardPosition = Vec3f.ZERO
    var billboardRotation = QuatF.IDENTITY

    var distance: Float = 0f

    fun emit(inheritVelocity: Boolean) {
        var pos: Vec3f = MutableVec3f()
        var dir: Vec3f = MutableVec3f()

        fun ParticleComponents.Direction.computeFor(point: Vec3f): Vec3f = when (this) {
            ParticleComponents.Direction.Inwards -> point.times(-1f)
            ParticleComponents.Direction.Outwards -> point
            is ParticleComponents.Direction.Custom -> vec.eval(context)
        }

        val random = emitter.system.random

        components.emitterShapePoint?.let { config ->
            pos = config.offset.eval(context)

            val vec = createShape(random)

            vec.norm()

            dir = config.direction.computeFor(vec)
        }

        components.emitterShapeBox?.let { config ->
            val point = MutableVec3f(
                (random.nextFloat() - 0.5f) * 2f,
                (random.nextFloat() - 0.5f) * 2f,
                (random.nextFloat() - 0.5f) * 2f,
            )

            if (config.surfaceOnly) {
                val side = random.nextInt(5)
                val value = if (side > 2) 1f else -1f
                when (side % 3) {
                    0 -> point.x = value
                    1 -> point.y = value
                    2 -> point.z = value
                }
            }

            point.mul(config.halfDimensions.eval(context))
            pos = config.offset.eval(context).add(point, MutableVec3f())
            dir = config.direction.computeFor(point)
        }

        components.emitterShapeSphere?.let { config ->
            val vec = createShape(random)

            if (config.surfaceOnly) vec.norm()

            vec.mul(config.radius.eval(context))
            pos = config.offset.eval(context).add(vec, MutableVec3f())
            dir = config.direction.computeFor(vec)
        }

        components.emitterShapeDisc?.let { config ->
            val radius = config.radius.eval(context)
            val normal = config.planeNormal.eval(context).normed()

            var vec = MutableVec3f(1f, 0f, 0f)
            if (vec.dot(normal).absoluteValue > 0.9) vec.set(0f, 1f, 0f)

            vec = vec.cross(normal, MutableVec3f()).norm()
            vec.rotateSelfBy(QuatF.rotation((random.nextFloat() * 2f * Mth.PI).rad, normal))
            vec.mul(radius * if (config.surfaceOnly) 1f else sqrt(random.nextFloat()))

            pos = config.offset.eval(context).add(vec, MutableVec3f())
            dir = config.direction.computeFor(vec)
        }

        if (components.emitterLocalSpace?.rotation != true) {
            pos = pos.rotateBy(emitter.rotation)
            dir = dir.rotateBy(emitter.rotation)
        }

        if (localSpace == null) {
            pos = pos.add(emitter.position, MutableVec3f())
        } else if (emitter.offset != null) {
            pos = pos.add(emitter.offset, MutableVec3f())
        }

        position.set(pos)
        direction.set(dir).norm()
        velocity.set(direction).mul(components.particleInitialSpeed.eval(context))

        if (!inheritVelocity && components.emitterLocalSpace?.velocity != true) return

        velocity.add(emitter.velocity)
    }

    private fun createShape(random: Random) = MutableVec3f().apply {
        do {
            set(
                (random.nextFloat() - 0.5f) * 2f,
                (random.nextFloat() - 0.5f) * 2f,
                (random.nextFloat() - 0.5f) * 2f,
            )
        } while (sqrLength().let { it > 1 || it == 0f })
    }

    fun update(dt: Float): Boolean {
        if (!firedCreationEvents) {
            firedCreationEvents = true
            emitter.fire(dt, components.particleLifetimeEvents.creationEvents, this)
        }

        val alive = doUpdate(dt)

        if (!alive && !firedExpirationEvents) {
            firedExpirationEvents = true
            emitter.fire(0f, components.particleLifetimeEvents.expirationEvents, this)
        }

        return alive
    }

    private fun doUpdate(dt: Float): Boolean {
        age += dt

        curveVariables.update()

        fireTimelineEvents()

        if (age >= lifetime) return false

        components.particleLifetimeExpression?.let { config ->
            if (config.expirationExpression.eval(context) != 0f) return false
        }

        components.particleMotionParametric?.let { config ->
            position.set(config.relativePosition.eval(context))
            rotationAngle = config.rotation.eval(context)
            if (config.direction != null) {
                direction.set(config.direction.eval(context))
                velocity.set(Vec3f.ZERO)
            }
        }

        components.particleMotionDynamic?.let { config ->
            var linearAcceleration = config.linearAcceleration.eval(context)
            linearAcceleration = linearAcceleration.add(Vec3f(velocity).mul(-config.linearDragCoefficient.eval(context), MutableVec3f()), MutableVec3f())
            if (!move(dt, linearAcceleration)) return false

            var rotAcceleration = config.rotationAcceleration.eval(context)
            rotAcceleration -= rotationRate * config.rotationDragCoefficient.eval(context)
            rotAcceleration *= dt
            var deltaRotation = rotationRate
            rotationRate += rotAcceleration
            deltaRotation += rotationRate
            deltaRotation *= 0.5f * dt
            rotationAngle += deltaRotation
        }

        components.particleAppearanceBillboard?.let { config ->
            if (config.direction is ParticleComponents.ParticleBillboard.Direction.FromVelocity) {
                val lengthSqr = velocity.sqrLength()
                if (lengthSqr > config.direction.minSpeedThresholdSqr) direction.set(velocity)
            }
        }

        return true
    }

    private fun fireTimelineEvents() {
        while (true) {
            val (time, events) = nextTimelineEvent ?: return
            val timeSinceEvent = age - time
            if (timeSinceEvent < 0) return

            emitter.fire(timeSinceEvent, events, this)

            nextTimelineEvent = components.particleLifetimeEvents.timeline.higherEntry(time)
        }
    }

    private fun move(dt: Float, acceleration: Vec3f, iteration: Int = 0, sliding: Boolean = false): Boolean {
        var offset = Vec3f(velocity)
        offset = offset.add(Vec3f(acceleration).mul(0.5f * dt, MutableVec3f()), MutableVec3f())
        offset = offset.mul(dt, MutableVec3f())

        val config = components.particleMotionCollision
        if (config == null) {
            position.add(offset)
            velocity.add(Vec3f(acceleration).mul(dt, MutableVec3f()))
            return true
        }

        val collision = emitter.system.collisionProvider.query(position, config.collisionRadius, offset)
        if (collision == null) {
            position.add(offset)
            velocity.add(Vec3f(acceleration).mul(dt, MutableVec3f()))
            if (sliding) {
                val speedSqr = velocity.sqrLength()
                if (speedSqr > 0.0000001f) {
                    val orgSpeed = sqrt(speedSqr)
                    val modifiedSpeed = (orgSpeed - config.collisionDrag * dt).coerceAtLeast(0f)
                    if (modifiedSpeed > 0.0001f) velocity.mul(modifiedSpeed / orgSpeed)
                    else velocity.set(Vec3f.ZERO)
                } else {
                    velocity.set(Vec3f.ZERO)
                }
            }
            return true
        }

        val (maxOffset, surfaceNormal) = collision

        if (iteration >= 3 || config.expireOnContact) {
            position.add(maxOffset)
            velocity.add(Vec3f(acceleration).mul(dt, MutableVec3f()))
            return !config.expireOnContact
        }

        val preDt = sqrt(maxOffset.sqrLength() / offset.sqrLength()).coerceIn(0f, 1f) * dt

        val velocityBeforeHit = velocity.add(Vec3f(acceleration).mul(preDt, MutableVec3f()))
        val velocityAfterHit = reflect(velocityBeforeHit, surfaceNormal)
        velocityAfterHit.add(
            MutableVec3f(surfaceNormal).mul((config.coefficientOfRestitution - 1) * Vec3f(velocityAfterHit).dot(surfaceNormal))
        )
        val positionAtHit = position.add(maxOffset)

        position.set(positionAtHit)
        velocity.set(velocityAfterHit)

        val postDt = dt - preDt
        val postOffset = MutableVec3f(velocityAfterHit)
        postOffset.add(MutableVec3f(acceleration).mul(postDt/2f))
        postOffset.mul(postDt)
        val positionPostBounce = positionAtHit.add(postOffset)

        if (config.events.isNotEmpty()) {
            val sqrSpeed = -velocityBeforeHit.dot(surfaceNormal)
            config.events.forEach { eventConfig ->
                if (sqrSpeed >= eventConfig.minSpeed * eventConfig.minSpeed) {
                    emitter.fire(postDt, eventConfig.event, this)
                }
            }
        }

        if (positionPostBounce.dot(surfaceNormal) > positionAtHit.dot(surfaceNormal)) {
            return move(postDt, acceleration, iteration + 1, sliding)
        }

        val accelerationInPlane = acceleration.add(surfaceNormal.mul(-Vec3f(acceleration).dot(surfaceNormal), MutableVec3f()), MutableVec3f())
        return move(postDt, accelerationInPlane, iteration + 1, true)
    }

    fun prepareBillboard(cameraPos: Vec3f, cameraRot: QuatF) {
        val appearance = components.particleAppearanceBillboard ?: throw UnsupportedOperationException()
        val position = globalPosition

        fun computeDirection(): Vec3f {
            val localDirection = when (val config = appearance.direction) {
                is ParticleComponents.ParticleBillboard.Direction.FromVelocity -> direction
                is ParticleComponents.ParticleBillboard.Direction.Custom -> config.direction.eval(context)
            }
            return if (localSpace != null) localDirection.rotateBy(localSpace.rotation)
            else localDirection
        }

        val localSpaceRotation = localSpace?.rotation ?: QuatF.IDENTITY

        var rot = when (appearance.facingCameraMode) {
            ParticleComponents.ParticleBillboard.FacingCameraMode.ROTATE_XYZ -> cameraRot.opposite()
            ParticleComponents.ParticleBillboard.FacingCameraMode.ROTATE_Y -> cameraRot.opposite()
                .projectAroundAxis(Vec3f(0f, 1f, 0f))

            ParticleComponents.ParticleBillboard.FacingCameraMode.LOOK_AT_XYZ -> QuatF.fromLookAt(
                cameraPos.minus(position), Vec3f(0f, 1f, 0f)
            )

            ParticleComponents.ParticleBillboard.FacingCameraMode.LOOK_AT_Y -> QuatF.fromLookAt(
                cameraPos.minus(position).let { Vec3f(it.x, 0f, it.y) }, Vec3f(0f, 1f, 0f)
            )

            ParticleComponents.ParticleBillboard.FacingCameraMode.LOOK_AT_DIRECTION -> {
                val direction = computeDirection()
                val target = cameraPos.minus(position).apply { add(direction.mul(Vec3f(this).negate().dot(direction), MutableVec3f()), MutableVec3f()) }
                QuatF.fromLookAt(target, direction.cross(target, MutableVec3f()).normed())
            }

            ParticleComponents.ParticleBillboard.FacingCameraMode.DIRECTION_X -> QuatF.fromLookAt(
                computeDirection(), Vec3f(0f, 1f, 0f).rotateBy(localSpaceRotation)
            ) * QuatF((-Mth.PI / 2f).rad, Vec3f(0f, 1f, 0f))

            ParticleComponents.ParticleBillboard.FacingCameraMode.DIRECTION_Y -> QuatF.fromLookAt(
                computeDirection(), Vec3f(0f, 1f, 0f).rotateBy(localSpaceRotation)
            ) * QuatF((-Mth.PI / 2).rad, Vec3f(1f, 0f, 0f)) * QuatF.Y180

            ParticleComponents.ParticleBillboard.FacingCameraMode.DIRECTION_Z -> QuatF.fromLookAt(
                computeDirection(), Vec3f(0f, 1f, 0f).rotateBy(localSpaceRotation)
            )

            ParticleComponents.ParticleBillboard.FacingCameraMode.EMITTER_TRANSFORM_XY -> (localSpace?.rotation
                ?: emitterRotationOnEmit) * QuatF.Y180

            ParticleComponents.ParticleBillboard.FacingCameraMode.EMITTER_TRANSFORM_XZ -> (localSpace?.rotation
                ?: emitterRotationOnEmit) * QuatF.Y180 * QuatF((Mth.PI / 2).rad, Vec3f.X_AXIS)

            ParticleComponents.ParticleBillboard.FacingCameraMode.EMITTER_TRANSFORM_YZ -> (localSpace?.rotation
                ?: emitterRotationOnEmit) * QuatF(
                (-Mth.PI / 2).rad, Vec3f.Y_AXIS
            )
        }

        if (rotationAngle != 0f) {
            rot *= QuatF(-rotationAngle.deg, Vec3f.Z_AXIS)
        }

        billboardPosition = position
        billboardRotation = rot
    }

    fun renderBillboard(
        matrixStack: PoseStack,
        vertexConsumer: VertexConsumer,
        cameraFacing: Vec3f,
        cameraUuid: UUID,
        cameraFirstPerson: Boolean,
    ) {
        if (cameraUuid == (emitter.sourceEntity as? LivingEntityQuery)?.entity?.uuid) {
            if (!components.particleVisibility.let { if (cameraFirstPerson) it.firstPerson else it.thirdPerson }) return
        }

        val appearance = components.particleAppearanceBillboard ?: throw UnsupportedOperationException()

        components.particleInitialization?.perRenderExpression?.eval(context)

        val position = billboardPosition
        val rotation = billboardRotation
        val size = appearance.size.eval(context)
        val sizeX = size.x
        val sizeY = size.y
        val textureSize = Vec2f(appearance.uv.textureWidth.toFloat(), appearance.uv.textureHeight.toFloat())
        val color = components.particleAppearanceTinting?.color?.eval(context) ?: Color.WHITE
        val light = if (components.particleAppearanceLighting != null) {
            emitter.system.lightProvider.query(position)
        } else {
            LightTexture.FULL_BRIGHT
        }

        var minUV: Vec2f
        var maxUV: Vec2f

        val flipbook = appearance.uv.flipbook
        if (flipbook != null) {
            val base = flipbook.base.eval(context)
            val size = flipbook.size.toVec2()
            val step = flipbook.step.toVec2()
            val maxFrame = flipbook.maxFrame.eval(context).toInt()
            val timePerFrame = if (flipbook.stretchToLifetime) {
                lifetime / maxFrame
            } else {
                1 / flipbook.framePerSecond
            }
            val frame = (age / timePerFrame).toInt().let { frame ->
                if (flipbook.loop) {
                    frame % maxFrame
                } else {
                    frame.coerceAtMost(maxFrame)
                }
            }
            minUV = base.add(MutableVec2f(step).mul(frame.toFloat()), MutableVec2f())
            maxUV = MutableVec2f(minUV).add(size)
        } else {
            val base = appearance.uv.uv?.eval(context) ?: Vec2f.ZERO
            val size = appearance.uv.uvSize?.eval(context) ?: textureSize
            minUV = base
            maxUV = MutableVec2f(minUV).add(size)
        }

        minUV = minUV.div(textureSize)
        maxUV = maxUV.div(textureSize)

        fun emitPoint(x: Float, y: Float, u: Float, v: Float) {
            val pos = Vec3f(x, y, 0f)
            pos.rotateSelfBy(rotation)
            vertexConsumer
                .vertex(
                    matrixStack.last().pose(),
                    position.x + pos.x,
                    position.y + pos.y,
                    position.z + pos.z
                )
                .uv(u, v)
                .color(color.r, color.g, color.b, color.a)
                .uv2(light)
        }

        val flip =
            if (emitter.effect.material.backfaceCulling) false
            else {
                val billboardNormal = Vec3f(0f, 0f, -1f).rotateSelfBy(rotation)
                cameraFacing.dot(billboardNormal) > 0
            }

        if (!flip) {
            emitPoint(-sizeX, -sizeY, maxUV.x, maxUV.y)
            emitPoint(-sizeX, +sizeY, maxUV.x, minUV.y)
            emitPoint(+sizeX, +sizeY, minUV.x, minUV.y)
            emitPoint(+sizeX, -sizeY, minUV.x, maxUV.y)
        } else {
            emitPoint(+sizeX, -sizeY, minUV.x, maxUV.y)
            emitPoint(+sizeX, +sizeY, minUV.x, minUV.y)
            emitPoint(-sizeX, +sizeY, maxUV.x, minUV.y)
            emitPoint(-sizeX, -sizeY, maxUV.x, maxUV.y)
        }
    }
}

private fun reflect(vec: Vec3f, norm: Vec3f) = vec.add(MutableVec3f(norm).mul(-2 * Vec3f(vec).dot(norm)), MutableVec3f())

private fun Pair<Float, Float>.toVec2() = Vec2f(first, second)

private fun Pair<FloatExpr, FloatExpr>.eval(context: BedrockContext) =
    Vec2f(first.eval(context), second.eval(context))