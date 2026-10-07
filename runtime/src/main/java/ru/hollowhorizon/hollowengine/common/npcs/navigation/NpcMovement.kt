package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlinx.coroutines.delay
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.coroutines.Ref
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

enum class UnreachablePolicy {
    WAIT_AND_RETRY,
    FAIL,
}

enum class UnavailableTargetPolicy {
    WAIT_AND_RETRY,
    FAIL,
}

data class MoveOptions(
    val speed: Double = 1.0,
    val arrivalDistance: Double = 1.5,
    val repathIntervalTicks: Int = 10,
    val targetMoveThreshold: Double = 1.0,
    val stuckTimeoutTicks: Int = 60,
    val unreachableTimeoutTicks: Int = 40,
    val unreachable: UnreachablePolicy = UnreachablePolicy.WAIT_AND_RETRY,
    val unavailableTarget: UnavailableTargetPolicy = UnavailableTargetPolicy.WAIT_AND_RETRY,
    val navigation: (NavigationComponent.() -> NavigationComponent)? = null,
) {
    init {
        require(speed > 0.0) { "Movement speed must be greater than zero" }
        require(arrivalDistance >= 0.0) { "Arrival distance cannot be negative" }
        require(repathIntervalTicks > 0) { "Repath interval must be greater than zero" }
        require(targetMoveThreshold >= 0.0) { "Target move threshold cannot be negative" }
        require(stuckTimeoutTicks > 0) { "Stuck timeout must be greater than zero" }
        require(unreachableTimeoutTicks > 0) { "Unreachable timeout must be greater than zero" }
    }
}

sealed interface MoveResult {
    data object Arrived : MoveResult
    data object Unreachable : MoveResult
    data object TargetUnavailable : MoveResult
}

internal suspend fun NpcEntity.moveToPosition(target: () -> Vec3?, options: MoveOptions): MoveResult {
    val arrivalDistanceSq = options.arrivalDistance * options.arrivalDistance
    val targetMoveThresholdSq = options.targetMoveThreshold * options.targetMoveThreshold
    var lastPathTarget: Vec3? = null
    var lastProgressPosition = position()
    var ticksSinceProgress = 0
    var ticksSinceRepath = options.repathIntervalTicks
    var ticksWithoutPath = 0
    var pathCreationFailed = false
    var reachingPath: Path? = null

    npcNavigation.settingsOverride = options.navigation?.let { tune -> (navigationComponent ?: NavigationComponent()).tune() }
    try {
        while (true) {
            val currentTarget = target() ?: return MoveResult.TargetUnavailable
            if (distanceToSqr(currentTarget) <= arrivalDistanceSq) return MoveResult.Arrived

            val progressed = position().distanceToSqr(lastProgressPosition) >= MIN_PROGRESS_DISTANCE_SQ
            if (progressed) {
                lastProgressPosition = position()
                ticksSinceProgress = 0
            } else {
                ticksSinceProgress++
            }

            val targetMoved = lastPathTarget?.distanceToSqr(currentTarget)?.let { it > targetMoveThresholdSq } ?: true
            if (!targetMoved && reachingPath?.isDone == true) return MoveResult.Arrived

            val stuck = ticksSinceProgress >= options.stuckTimeoutTicks
            val repathReady = ticksSinceRepath >= options.repathIntervalTicks
            val shouldRepath = targetMoved || stuck || repathReady && navigation.isDone

            if (shouldRepath) {
                val path = navigation.createPath(currentTarget.x, currentTarget.y, currentTarget.z, 0)
                lastPathTarget = currentTarget
                val moving = path != null && navigation.moveTo(path, options.speed)
                reachingPath = navigation.path?.takeIf { moving && it.canReach() }
                pathCreationFailed = reachingPath == null
                if (!pathCreationFailed) ticksWithoutPath = 0
                ticksSinceRepath = 0
                if (stuck) {
                    ticksSinceProgress = 0
                    lastProgressPosition = position()
                }
            }

            val remainingDistance = sqrt(distanceToSqr(currentTarget))
            val slowdownDistance = max(options.arrivalDistance + 0.5, MIN_SLOWDOWN_DISTANCE)
            val speedScale = (remainingDistance / slowdownDistance).coerceIn(MIN_SPEED_SCALE, 1.0)
            navigation.setSpeedModifier(options.speed * speedScale)

            if (pathCreationFailed && navigation.isDone) {
                ticksWithoutPath++
                if (options.unreachable == UnreachablePolicy.FAIL &&
                    ticksWithoutPath >= options.unreachableTimeoutTicks
                ) {
                    return MoveResult.Unreachable
                }
            }

            delay(TICK_DURATION)
            ticksSinceRepath++
        }
    } finally {
        navigation.stop()
        npcNavigation.settingsOverride = null
    }
}

internal suspend fun NpcEntity.moveToEntity(target: Ref<out Entity>, options: MoveOptions): MoveResult {
    while (true) {
        if (!target.isLinkAlive && options.unavailableTarget == UnavailableTargetPolicy.FAIL) {
            return MoveResult.TargetUnavailable
        }

        val entity = target.resolve()
        while (!entity.isRemoved) {
            if (entity.level() !== level()) {
                if (options.unavailableTarget == UnavailableTargetPolicy.FAIL) return MoveResult.TargetUnavailable
                delay(TICK_DURATION)
                continue
            }

            val result = moveToPosition(
                target = { entity.takeIf { !it.isRemoved && it.level() === level() }?.position() },
                options = options,
            )
            if (result != MoveResult.TargetUnavailable) return result
            break
        }

        if (options.unavailableTarget == UnavailableTargetPolicy.FAIL) return MoveResult.TargetUnavailable
    }
}

private val TICK_DURATION = 50.milliseconds
private const val MIN_PROGRESS_DISTANCE_SQ = 0.0025
private const val MIN_SLOWDOWN_DISTANCE = 2.0
private const val MIN_SPEED_SCALE = 0.35
