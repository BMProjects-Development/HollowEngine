package ru.hollowhorizon.hollowengine.client.models.internal.animator

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import kotlin.math.abs

/**
 * Fills [context] with what an animation expression can read this frame.
 */
fun fillAnimationVariables(context: AnimatorEvaluationContext, entity: Entity?, partialTick: Float) {
    val gameTime = entity?.level()?.gameTime?.toFloat() ?: Minecraft.getInstance().level?.gameTime?.toFloat() ?: 0f

    context.temporaries.clear()
    context.entity = entity
    context.partialTick = partialTick
    context.gameTime = gameTime + partialTick
    context.time = (entity?.tickCount?.toFloat() ?: gameTime) + partialTick
    context.data = entity?.let { AttachmentRegistry.entityDataOrNull(it)?.numericPaths() }.orEmpty()

    if (entity == null) return

    val velocity = entity.deltaMovement
    val movementYaw = when (entity) {
        is LivingEntity -> Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
        else -> Mth.rotLerp(partialTick, entity.yRotO, entity.yRot)
    }
    context.localForwardSpeed = localForwardSpeed(velocity, movementYaw)
    context.localSideSpeed = localSideSpeed(velocity, movementYaw)
    context.horizontalSpeed = velocity.horizontalDistance().toFloat() * 20f
    context.signedHorizontalSpeed = signedLocomotionSpeed(context.horizontalSpeed, context.localForwardSpeed)

    if (entity !is LivingEntity) return

    context.bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
    context.headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot)
    context.headPitch = Mth.lerp(partialTick, entity.xRotO, entity.xRot)
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

private const val LOCAL_MOVEMENT_EPSILON = 0.001f
