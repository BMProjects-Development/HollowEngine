package ru.hollowhorizon.hollowengine.client.models.internal.animator

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2

/**
 * Fills [context] with what an animation expression can read this frame.
 *
 * [gameTicks] is the level clock; without an entity, as in a preview, the caller says which clock it runs on.
 */
fun fillAnimationVariables(
    context: AnimatorEvaluationContext,
    entity: Entity?,
    partialTick: Float,
    gameTicks: Long = entity?.level()?.gameTime ?: 0L,
) {
    context.temporaries.clear()
    context.entity = entity
    context.partialTick = partialTick
    context.gameTicks = gameTicks
    context.gameTime = gameTicks.toFloat() + partialTick
    context.time = (entity?.tickCount?.toFloat() ?: gameTicks.toFloat()) + partialTick
    context.data = entity?.let { AttachmentRegistry.entityDataOrNull(it)?.numericPaths() }.orEmpty()

    val velocity = entity?.deltaMovement ?: Vec3.ZERO
    val movementYaw = when (entity) {
        is LivingEntity -> Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
        null -> 0f
        else -> Mth.rotLerp(partialTick, entity.yRotO, entity.yRot)
    }
    context.localForwardSpeed = localForwardSpeed(velocity, movementYaw)
    context.localSideSpeed = localSideSpeed(velocity, movementYaw)
    context.horizontalSpeed = velocity.horizontalDistance().toFloat() * 20f
    context.signedHorizontalSpeed = signedLocomotionSpeed(context.horizontalSpeed, context.localForwardSpeed)
    context.flightPitch = flightPitch(velocity)
    context.flightBank = when (entity) {
        is LivingEntity -> flightBank(velocity.horizontalDistance(), Mth.wrapDegrees(entity.yBodyRot - entity.yBodyRotO))
        else -> 0f
    }

    context.bodyYaw = movementYaw
    context.headYaw = if (entity is LivingEntity) Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) else movementYaw
    context.headPitch = entity?.let { Mth.lerp(partialTick, it.xRotO, it.xRot) } ?: 0f
    context.headBodyYawDelta = Mth.wrapDegrees(context.headYaw - context.bodyYaw)
}

internal fun localForwardSpeed(velocity: Vec3, yaw: Float): Float {
    val yawRad = yaw * Mth.DEG_TO_RAD
    return velocity.x.toFloat() * -Mth.sin(yawRad) + velocity.z.toFloat() * Mth.cos(yawRad)
}

internal fun localSideSpeed(velocity: Vec3, yaw: Float): Float {
    val yawRad = yaw * Mth.DEG_TO_RAD
    return velocity.x.toFloat() * Mth.cos(yawRad) + velocity.z.toFloat() * Mth.sin(yawRad)
}

internal fun signedLocomotionSpeed(horizontalSpeed: Float, localForwardSpeed: Float): Float =
    if (abs(localForwardSpeed) > LOCAL_MOVEMENT_EPSILON && localForwardSpeed < 0f) -horizontalSpeed
    else horizontalSpeed

internal fun flightPitch(velocity: Vec3): Float {
    if (velocity.length() < MIN_FLIGHT_SPEED) return 0f
    return (atan2(velocity.y, velocity.horizontalDistance()) * Mth.RAD_TO_DEG).toFloat()
}

internal fun flightBank(speed: Double, turn: Float): Float {
    val sideways = speed * turn * Mth.DEG_TO_RAD / BANK_GRAVITY
    return (atan(sideways) * Mth.RAD_TO_DEG).toFloat()
}

private const val LOCAL_MOVEMENT_EPSILON = 0.001f

private const val MIN_FLIGHT_SPEED = 0.01
private const val BANK_GRAVITY = 0.08

/**
 * The conditions of an entity's montage sections, asked of the entity as it is at [partialTick]; what they read
 * is gathered the first time one is asked, as most playback asks none.
 */
class EntityConditions(private val entity: Entity, private val partialTick: Float = 1f) : (String) -> Boolean {
    private val context by lazy { AnimatorEvaluationContext().also { fillAnimationVariables(it, entity, partialTick) } }

    override fun invoke(source: String): Boolean = AnimatorExpressionEvaluator.condition(source, context)
}
