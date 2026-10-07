package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

fun LivingEntity.faceTowards(
    target: Vec3,
    maxAngularSpeed: Float = 360f,
    updateIntervalMs: Long = 50L,
): Boolean {
    if (this is NpcEntity && npcNavigation.isInProgress) {
        return npcNavigation.faceWhileWalking(target, maxAngularSpeed * (updateIntervalMs / 1000f))
    }
    val currentPos = position()
    val eyePos = Vec3(currentPos.x, currentPos.y + eyeHeight, currentPos.z)
    val dx = target.x - eyePos.x
    val dy = target.y - eyePos.y
    val dz = target.z - eyePos.z
    if (dx * dx + dy * dy + dz * dz < 1.0e-6) return true

    val maxAnglePerUpdate = maxAngularSpeed * (updateIntervalMs / 1000f)
    val targetYaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
    val horizontalDistance = sqrt(dx * dx + dz * dz)
    val targetPitch = -Math.toDegrees(atan2(dy, horizontalDistance)).toFloat().coerceIn(-90f, 90f)

    val currentYaw = normalizeAngle(yRot)
    val normalizedTargetYaw = normalizeAngle(targetYaw)
    var yawDifference = normalizedTargetYaw - currentYaw
    while (yawDifference < -180f) yawDifference += 360f
    while (yawDifference >= 180f) yawDifference -= 360f

    val pitchDifference = targetPitch - xRot
    val limitedYawDiff = yawDifference.coerceIn(-maxAnglePerUpdate, maxAnglePerUpdate)
    val limitedPitchDiff = pitchDifference.coerceIn(-maxAnglePerUpdate, maxAnglePerUpdate)

    yRot = currentYaw + limitedYawDiff
    xRot = Mth.clamp(xRot + limitedPitchDiff, -90f, 90f)
    yHeadRot = yRot
    if (this is Mob) {
        lookControl.setLookAt(target.x, target.y, target.z, 360f, 360f)
    }

    return abs(yawDifference) <= 1f && abs(pitchDifference) <= 1f
}

fun LivingEntity.faceTowards(
    target: Entity,
    maxAngularSpeed: Float = 360f,
    updateIntervalMs: Long = 50L,
): Boolean = faceTowards(target.lookTargetPosition(), maxAngularSpeed, updateIntervalMs)

suspend fun LivingEntity.rotate(
    targetProvider: suspend () -> Vec3,
    durationMs: Long,
    updateIntervalMs: Long = 50L,
    maxAngularSpeed: Float = 360f,
) {
    val startTime = System.currentTimeMillis()
    val endTime = startTime + durationMs
    while (System.currentTimeMillis() < endTime && currentCoroutineContext().isActive) {
        faceTowards(targetProvider(), maxAngularSpeed, updateIntervalMs)
        delay(updateIntervalMs.milliseconds)
    }
    faceTowards(targetProvider(), maxAngularSpeed, updateIntervalMs)
}

suspend fun LivingEntity.rotate(
    targetEntity: Entity,
    durationMs: Long,
    updateIntervalMs: Long = 50L,
    maxAngularSpeed: Float = 360f,
) = rotate({ targetEntity.lookTargetPosition() }, durationMs, updateIntervalMs, maxAngularSpeed)

private fun Entity.lookTargetPosition(): Vec3 = Vec3(x, eyeY, z)

private fun normalizeAngle(angle: Float): Float {
    var normalized = angle % 360f
    if (normalized < -180f) normalized += 360f
    if (normalized >= 180f) normalized -= 360f
    return normalized
}
