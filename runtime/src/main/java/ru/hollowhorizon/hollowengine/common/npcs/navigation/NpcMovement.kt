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
    val arrivalDistance: Double = 0.0,
    val repathIntervalTicks: Int = 10,
    val targetMoveThreshold: Double = 1.0,
    val stuckTimeoutTicks: Int = 60,
    val unreachableTimeoutTicks: Int = 40,
    val unreachable: UnreachablePolicy = UnreachablePolicy.WAIT_AND_RETRY,
    val unavailableTarget: UnavailableTargetPolicy = UnavailableTargetPolicy.WAIT_AND_RETRY,
    val navigation: (NavigationComponent.() -> NavigationComponent)? = null,
    val facing: Facing = Facing.Path,
    val passDistance: Double = 1.0,
    val avoid: List<Zone> = emptyList(),
) {
    init {
        require(speed > 0.0) { "Movement speed must be greater than zero" }
        require(arrivalDistance >= 0.0) { "Arrival distance cannot be negative" }
        require(repathIntervalTicks > 0) { "Repath interval must be greater than zero" }
        require(targetMoveThreshold >= 0.0) { "Target move threshold cannot be negative" }
        require(stuckTimeoutTicks > 0) { "Stuck timeout must be greater than zero" }
        require(unreachableTimeoutTicks > 0) { "Unreachable timeout must be greater than zero" }
        require(passDistance > 0.0) { "Pass distance must be greater than zero" }
    }
}

sealed interface MoveResult {
    data object Arrived : MoveResult
    data object Unreachable : MoveResult
    data object TargetUnavailable : MoveResult

    /** The NPC stopped as close to the point as it got, [distance] blocks from it, for [reason]. */
    data class Closest(val reason: Shortfall, val distance: Double) : MoveResult
}

/** Why a move stopped short of its point. */
enum class Shortfall {
    /**
     * The point has no room for the NPC: it is in a wall, in the air or too tight for its body. The NPC went to
     * the nearest spot that has.
     */
    NO_ROOM,

    /** No way leads to the point: the NPC went as far as a way does. */
    NO_PATH,

    /** Something it can neither pass nor push aside stood in its way. */
    BLOCKED,
}

internal suspend fun NpcEntity.moveToPosition(target: () -> Vec3?, options: MoveOptions): MoveResult =
    moveThrough(listOf(target), options)

/**
 * Walks through [targets] in turn, stopping only at the last: each one before it counts as passed within
 * [MoveOptions.passDistance], and the NPC plans its pace along the ones still ahead, so it rounds them as turns.
 */
internal suspend fun NpcEntity.moveThrough(targets: List<() -> Vec3?>, options: MoveOptions): MoveResult {
    npcNavigation.settingsOverride = options.navigation?.let { tune -> (navigationComponent ?: NavigationComponent()).tune() }
    npcNavigation.facing.setMove(options.facing)
    npcNavigation.moveZones = options.avoid
    try {
        for ((index, target) in targets.withIndex()) {
            val last = index == targets.lastIndex
            val reach = if (last) maxOf(options.arrivalDistance, MIN_ARRIVAL_DISTANCE) else options.passDistance
            val result = walkLeg(target, targets.subList(index + 1, targets.size), options, reach, last)
            if (result != MoveResult.Arrived) return result
        }
        finishFacing(options.facing)
        return MoveResult.Arrived
    } finally {
        navigation.stop()
        npcNavigation.settingsOverride = null
        npcNavigation.aim(null)
        npcNavigation.moveZones = emptyList()
        npcNavigation.facing.setMove(Facing.Path)
    }
}

/** Walks until [target] is within [reach], the points [after] it still ahead; repaths and gives up as [options] say. */
private suspend fun NpcEntity.walkLeg(
    target: () -> Vec3?,
    after: List<() -> Vec3?>,
    options: MoveOptions,
    reach: Double,
    last: Boolean,
): MoveResult {
    val arrivalDistanceSq = reach * reach
    val targetMoveThresholdSq = options.targetMoveThreshold * options.targetMoveThreshold
    var lastPathTarget: Vec3? = null
    var spotFor: Vec3? = null
    var spot: Vec3? = null
    var lastProgressPosition = position()
    var ticksSinceProgress = 0
    var stuckRepaths = 0
    var ticksSinceRepath = options.repathIntervalTicks
    var ticksWithoutPath = 0
    var found = false
    var reachingPath: Path? = null

    while (true) {
        val currentTarget = target() ?: return MoveResult.TargetUnavailable
        if (distanceToSqr(currentTarget) <= arrivalDistanceSq) return MoveResult.Arrived
        if (spotFor != currentTarget) {
            spotFor = currentTarget
            spot = NpcNavigationGeometry.standingSpot(level(), this, currentTarget)
        }
        val goal = spot ?: currentTarget
        val shortBy = goal.distanceTo(currentTarget)
        npcNavigation.aim(goal, after.mapNotNull { it() }, maxOf(0.0, options.arrivalDistance - shortBy))
        if (shortBy > reach && position().distanceTo(goal) <= SPOT_REACH) {
            return if (last) MoveResult.Closest(Shortfall.NO_ROOM, position().distanceTo(currentTarget)) else MoveResult.Arrived
        }

        if (navigation.isDone || position().distanceToSqr(lastProgressPosition) >= MIN_PROGRESS_DISTANCE_SQ) {
            if (!navigation.isDone) stuckRepaths = 0
            lastProgressPosition = position()
            ticksSinceProgress = 0
        } else {
            ticksSinceProgress++
        }

        val targetMoved = lastPathTarget?.distanceToSqr(currentTarget)?.let { it > targetMoveThresholdSq } ?: true
        val stuck = ticksSinceProgress >= options.stuckTimeoutTicks
        val repathReady = ticksSinceRepath >= options.repathIntervalTicks
        if (stuck && ++stuckRepaths >= STUCK_REPATHS && options.unreachable == UnreachablePolicy.FAIL) {
            return MoveResult.Closest(Shortfall.BLOCKED, position().distanceTo(currentTarget))
        }

        if (!targetMoved && reachingPath?.isDone == true) {
            return if (!last || shortBy <= reach) {
                MoveResult.Arrived
            } else {
                MoveResult.Closest(Shortfall.NO_ROOM, position().distanceTo(currentTarget))
            }
        }

        if (targetMoved || stuck || repathReady && navigation.isDone) {
            val path = navigation.createPath(goal.x, goal.y, goal.z, 0)
            lastPathTarget = currentTarget
            found = path != null && navigation.moveTo(path, options.speed)
            reachingPath = navigation.path?.takeIf { found && it.canReach() }
            if (reachingPath != null) ticksWithoutPath = 0
            ticksSinceRepath = 0
            if (stuck) {
                ticksSinceProgress = 0
                lastProgressPosition = position()
            }
        }

        if (reachingPath == null && navigation.isDone) {
            ticksWithoutPath++
            if (options.unreachable == UnreachablePolicy.FAIL && ticksWithoutPath >= options.unreachableTimeoutTicks) {
                return if (found) MoveResult.Closest(Shortfall.NO_PATH, position().distanceTo(currentTarget)) else MoveResult.Unreachable
            }
        }

        delay(TICK_DURATION)
        ticksSinceRepath++
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

/** How close the NPC has to come to the spot that stands in for a point without room to be there. */
private const val SPOT_REACH = 0.15

/** How many times in a row walking gets the NPC nowhere and it plans anew before it counts as blocked. */
private const val STUCK_REPATHS = 2
