package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Flight part of NPC navigation: takeoff, landing, following a path through the air and idling in it.
 *
 * Gravity is off exactly while [isAirborne]. The pilot only toggles gravity it turned off itself, so a script
 * that disables gravity on its own is left alone.
 */
internal class FlightPilot(private val mob: Mob) {
    private val ramp = SpeedRamp()
    private var pacing = false

    /** Point the NPC hovers at or circles around when it has no path. */
    private var anchor: Vec3? = null

    var isAirborne = false
        private set

    /** Debug info: point aimed at this tick and speed as a fraction of full speed. */
    var aim: Vec3? = null
        private set
    var share = 0.0
        private set

    fun takeOff() {
        isAirborne = true
        mob.isNoGravity = true
        pacing = false
        anchor = null
    }

    fun land() {
        isAirborne = false
        mob.isNoGravity = false
        pacing = false
        anchor = null
    }

    /** Restores the flight state saved in NBT. */
    fun restore(airborne: Boolean) {
        isAirborne = airborne && mob.isNoGravity
    }

    /** Applies [FlightSettings.flies]: keeps an [FlightMode.ALWAYS] flier in the air, lands one that may no longer fly. */
    fun update(settings: FlightSettings) {
        if (isAirborne && !mob.isNoGravity) isAirborne = false
        if (settings.flies == FlightMode.ALWAYS && !isAirborne) takeOff()
        if (!settings.enabled && isAirborne) land()
    }

    /**
     * Plans one tick of flight along [path], braking before sharp turns, landings and the end.
     *
     * @param speed full flight speed, in blocks per tick
     * @param end exact end point of the move, if it names one
     * @param stopShort how far before the end to stop
     * @param facing body yaw the move holds, if any
     */
    fun follow(path: NpcPath, settings: FlightSettings, speed: Double, end: Vec3?, stopShort: Double, facing: Float?): FlightCommand {
        anchor = end ?: nodePoint(path, path.nodeCount - 1, settings)
        val ahead = ahead(path, settings, end, stopShort)
        val share = pace(ahead, settings, speed)
        val distance = aimDistance(settings, speed, share)
        var aim = ahead.pointAt(distance)
        if (!canFly(aim)) {
            aim = (ahead.nodeBefore(distance) downTo 1).map { ahead.points[it] }.firstOrNull(::canFly) ?: ahead.points[1]
        }
        aim = wobbled(aim, settings, ahead.stopShort?.let { ahead.length - it })
        this.aim = aim
        this.share = share
        return FlightCommand(aim, share, speed, facing, settings)
    }

    /**
     * Plans one tick of flight without a path. A hovering flier holds its position and a glider circles;
     * with [FlightSettings.landAtGoal] the NPC descends instead.
     *
     * @return the command, or null once the NPC has landed
     */
    fun idle(settings: FlightSettings, speed: Double): FlightCommand? {
        val lands = settings.flies == FlightMode.CAN && settings.landAtGoal
        if (lands && mob.onGround()) {
            land()
            return null
        }
        val anchor = anchor ?: mob.position().also { anchor = it }
        val landing = if (lands) FlightGeometry.landingBelow(mob.level(), mob, mob.position(), LANDING_DEPTH) else null
        val glides = settings.style == FlightStyle.GLIDE
        val aim = when {
            glides -> circleAim(landing?.let { Vec3(anchor.x, it.y - LANDING_SINK, anchor.z) } ?: anchor, speed, settings)
            landing != null -> landing.subtract(0.0, LANDING_SINK, 0.0)
            else -> wobbled(anchor, settings, null)
        }
        val share = if (glides) FlightControl.GLIDE_SHARE else idleShare(aim.distanceTo(mob.position()), settings, speed)
        pacing = false
        this.aim = aim
        this.share = share
        return FlightCommand(aim, share, speed, null, settings)
    }

    /**
     * Whether the NPC has reached node [index] of [path]: it is close to it, or past it with a clear line to the
     * next node. A landing node is reached once the NPC touches the ground near it.
     */
    fun passes(path: NpcPath, index: Int, settings: FlightSettings, end: Vec3?): Boolean {
        val point = if (index == path.nodeCount - 1) end ?: nodePoint(path, index, settings) else nodePoint(path, index, settings)
        val glides = settings.style == FlightStyle.GLIDE
        if (isLanding(path, index, settings)) {
            val reach = if (glides) GLIDE_LANDING_REACH else LANDING_REACH
            return mob.onGround() && Mth.length(point.x - mob.x, point.z - mob.z) <= reach
        }
        val reach = max(if (glides) GLIDE_REACH else NODE_REACH, mob.bbWidth * 0.5)
        if (mob.position().distanceTo(point) <= reach) return true
        if (index + 1 >= path.nodeCount) return false
        val next = nodePoint(path, index + 1, settings)
        val past = mob.position().subtract(point).dot(next.subtract(point)) > 0.0
        return past && canFly(next)
    }

    fun isLanding(path: NpcPath, index: Int, settings: FlightSettings): Boolean =
        settings.flies != FlightMode.ALWAYS && !path.isAloft(index)

    /** Body position at node [index]: the cell center for an air node, the floor for a ground node. */
    fun nodePoint(path: NpcPath, index: Int, settings: FlightSettings): Vec3 {
        val node = path.getNode(index)
        if (path.isAloft(index) || settings.flies == FlightMode.ALWAYS && !hasFloor(node.x, node.y, node.z)) {
            return FlightGeometry.nodeCenter(mob, node.x, node.y, node.z)
        }
        return NpcNavigationGeometry.nodeCenter(mob, node.x, WalkNodeEvaluator.getFloorLevel(mob.level(), node.asBlockPos()), node.z)
    }

    private fun hasFloor(x: Int, y: Int, z: Int): Boolean =
        WalkNodeEvaluator.getFloorLevel(mob.level(), BlockPos(x, y, z)) > y - 1.0 + FLOOR_EPSILON

    /** Path ahead up to its end, the first landing or [PLANNING_REACH]. */
    private fun ahead(path: NpcPath, settings: FlightSettings, end: Vec3?, stopShort: Double): PathAhead {
        val points = arrayListOf(mob.position())
        var length = 0.0
        for (index in path.nextNodeIndex until path.nodeCount) {
            val last = index == path.nodeCount - 1
            val landing = isLanding(path, index, settings)
            var point = if (last) end ?: nodePoint(path, index, settings) else nodePoint(path, index, settings)
            if (landing) point = point.subtract(0.0, LANDING_SINK, 0.0)
            length += point.distanceTo(points.last())
            points += point
            if (landing) return PathAhead(points, stopShort = 0.0, inSpace = true)
            if (last) return PathAhead(points, stopShort = if (settings.style == FlightStyle.GLIDE) null else stopShort, inSpace = true)
            if (length > PLANNING_REACH) break
        }
        return PathAhead(points, stopShort = null, inSpace = true)
    }

    private fun pace(ahead: PathAhead, settings: FlightSettings, speed: Double): Double {
        if (!pacing) {
            ramp.reset(if (speed > 0.0) mob.deltaMovement.length() / speed else 0.0)
            pacing = true
        }
        if (speed <= 0.0) return 0.0
        val ticks = settings.flightAcceleration * TICKS_PER_SECOND.toDouble()
        val deceleration = deceleration(settings, speed)
        var limit = 1.0
        for (turn in ahead.turns) {
            val corner = SpeedPlan.cornerShare(turn.degrees) * speed
            limit = min(limit, FlightSteering.brakingSpeed(turn.distance, corner, deceleration) / speed)
        }
        ahead.stopShort?.let { limit = min(limit, FlightSteering.brakingSpeed(ahead.length - it, 0.0, deceleration) / speed) }
        if (settings.style == FlightStyle.GLIDE) limit = max(limit, FlightControl.GLIDE_SHARE)
        return ramp.next(limit, 1.0, ticks)
    }

    private fun idleShare(distance: Double, settings: FlightSettings, speed: Double): Double =
        if (speed <= 0.0) 0.0 else min(1.0, FlightSteering.brakingSpeed(distance, 0.0, deceleration(settings, speed)) / speed)

    private fun deceleration(settings: FlightSettings, speed: Double): Double {
        val ticks = settings.flightAcceleration * TICKS_PER_SECOND.toDouble()
        return if (ticks > 0.0) speed / ticks else Double.POSITIVE_INFINITY
    }

    private fun aimDistance(settings: FlightSettings, speed: Double, share: Double): Double {
        val lead = Mth.lerp(settings.smoothing.toDouble(), MIN_LEAD_TICKS, MAX_LEAD_TICKS)
        return (speed * share * lead).coerceIn(MIN_AIM, MAX_AIM)
    }

    /**
     * Offsets [aim] by the wobble, halving or dropping it where it would hit something.
     *
     * @param remaining distance left to the end; the wobble fades out near it so the NPC still arrives
     */
    private fun wobbled(aim: Vec3, settings: FlightSettings, remaining: Double?): Vec3 {
        if (settings.wobble <= 0f) return aim
        val fade = remaining?.let { (it / WOBBLE_FADE).coerceIn(0.0, 1.0) } ?: 1.0
        if (fade <= 0.0) return aim
        val time = mob.level().gameTime / TICKS_PER_SECOND.toDouble() * settings.wobbleRate
        val offset = FlightWobble.offset(mob.id, time).scale(settings.wobble * fade)
        for (scale in WOBBLE_SCALES) {
            val strayed = aim.add(offset.scale(scale))
            if (FlightGeometry.fits(mob.level(), mob, strayed) && canFly(strayed)) return strayed
        }
        return aim
    }

    /** Point slightly ahead of the NPC on a circle around [center], sized for the glider's turning radius. */
    private fun circleAim(center: Vec3, speed: Double, settings: FlightSettings): Vec3 {
        val turn = Math.toRadians(settings.turnRate.toDouble()) / TICKS_PER_SECOND
        val radius = max(MIN_CIRCLE, speed * FlightControl.GLIDE_SHARE / turn)
        val angle = atan2(mob.z - center.z, mob.x - center.x) + CIRCLE_LEAD
        val aim = Vec3(center.x + cos(angle) * radius, center.y, center.z + sin(angle) * radius)
        return if (canFly(aim)) aim else center
    }

    private fun canFly(point: Vec3): Boolean = FlightGeometry.canFlyDirectly(mob.level(), mob, mob.position(), point)

    private companion object {
        const val TICKS_PER_SECOND = 20f

        /** In blocks. */
        const val PLANNING_REACH = 16.0

        /** Aim distance in ticks of flight at smoothing 0 and 1, clamped to [MIN_AIM]..[MAX_AIM] blocks. */
        const val MIN_LEAD_TICKS = 3.0
        const val MAX_LEAD_TICKS = 24.0
        const val MIN_AIM = 0.5
        const val MAX_AIM = 8.0

        const val NODE_REACH = 0.6
        const val GLIDE_REACH = 1.5
        const val LANDING_REACH = 0.6
        const val GLIDE_LANDING_REACH = 2.0
        const val LANDING_DEPTH = 64

        /** Landings aim this far below the ground: onGround is only set when the body actually hits it. */
        const val LANDING_SINK = 0.25

        const val WOBBLE_FADE = 2.0
        val WOBBLE_SCALES = doubleArrayOf(1.0, 0.5)

        const val MIN_CIRCLE = 2.0

        /** In radians. */
        const val CIRCLE_LEAD = 0.6

        const val FLOOR_EPSILON = 1.0e-3
    }
}
