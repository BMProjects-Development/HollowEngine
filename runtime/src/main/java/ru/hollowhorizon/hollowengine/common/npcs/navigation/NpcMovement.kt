package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlinx.coroutines.delay
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.coroutines.Ref
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.math.abs
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
    val facing: Facing = Facing.Path,
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
    val arrivalDistance = maxOf(options.arrivalDistance, MIN_ARRIVAL_DISTANCE)
    val arrivalDistanceSq = arrivalDistance * arrivalDistance
    val targetMoveThresholdSq = options.targetMoveThreshold * options.targetMoveThreshold
    var lastPathTarget: Vec3? = null
    var lastProgressPosition = position()
    var ticksSinceProgress = 0
    var ticksSinceRepath = options.repathIntervalTicks
    var ticksWithoutPath = 0
    var pathCreationFailed = false
    var reachingPath: Path? = null

    npcNavigation.settingsOverride = options.navigation?.let { tune -> (navigationComponent ?: NavigationComponent()).tune() }
    npcNavigation.stopDistance = options.arrivalDistance
    npcNavigation.facing.setMove(options.facing)
    try {
        while (true) {
            val currentTarget = target() ?: return MoveResult.TargetUnavailable
            npcNavigation.exactTarget = currentTarget
            if (distanceToSqr(currentTarget) <= arrivalDistanceSq) {
                finishFacing(options.facing)
                return MoveResult.Arrived
            }

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
        npcNavigation.stopDistance = 0.0
        npcNavigation.exactTarget = null
        npcNavigation.facing.setMove(Facing.Path)
    }
}

/**
 * Turns the NPC, stopped, the rest of the way to what its move faced: a jump turns it to the landing, and the
 * last steps may end before it has turned back.
 */
private suspend fun NpcEntity.finishFacing(facing: Facing) {
    if (facing == Facing.Path) return
    navigation.stop()
    repeat(MAX_FINISH_TURN_TICKS) {
        val faced = when (facing) {
            is Facing.Toward -> facing.target()?.let { faceTowards(it) } ?: true
            is Facing.Yaw -> turnBodyTo(facing.yaw)
            Facing.Path -> true
        }
        if (faced) return
        delay(TICK_DURATION)
    }
}

private fun NpcEntity.turnBodyTo(yaw: Float): Boolean {
    val body = Mth.approachDegrees(yRot, yaw, FINISH_TURN)
    yRot = body
    yBodyRot = body
    yHeadRot = body
    return abs(Mth.wrapDegrees(yaw - body)) <= 1f
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

/** How close, in blocks, the NPC has to come to a point when asked to come all the way. */
private const val MIN_ARRIVAL_DISTANCE = 0.2

/** The most ticks the NPC turns at the end of a move to face what it faced on the way, and how fast, in degrees a tick. */
private const val MAX_FINISH_TURN_TICKS = 20
private const val FINISH_TURN = 18f
private const val MIN_PROGRESS_DISTANCE_SQ = 0.0025
