package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** The world a jump is tried in: whether a box placed in it hits anything. */
internal fun interface JumpSpace {
    fun collides(box: AABB): Boolean
}

/** What a mob jumps with: its size and the numbers vanilla moves it by. */
internal data class JumpBody(
    val halfWidth: Double,
    val height: Double,
    val speed: Double,
    val jumpPower: Double,
    val gravity: Double,
    val friction: Double,
)

/** Where a simulated jump left the body at rest, and how far the body fell from the top of the jump. */
internal class JumpLanding(val position: Vec3, val fall: Double)

/** How a jump taken off at one moment ends. */
internal sealed interface JumpOutcome {
    /** It comes to rest on the landing ground. */
    class Landed(val landing: JumpLanding) : JumpOutcome

    /** It comes down before the landing: taking off later would carry it further. */
    data object Early : JumpOutcome

    /** It hits the side of the landing or slides off its far side: taking off later only makes that worse. */
    data object Late : JumpOutcome
}

/** What a mob running up to a gap does this tick. */
internal enum class JumpDecision {
    /** Jump now: waiting would not land it nearer the middle of the landing. */
    TAKE_OFF,

    /** Keep running: a later jump lands, or lands better. */
    RUN,

    /** No jump from here lands: stop short of the edge. */
    BRAKE,
}

/** A jump that lands: it takes off once its feet would leave the ground within [lead] more ticks of running. */
internal class JumpPlan(val lead: Int, val landing: JumpLanding)

/** Jumps over gaps, tick by tick, the way vanilla moves a mob. */
internal object JumpSimulation {
    private const val INPUT_SCALE = 0.98
    private const val GROUND_ACCELERATION = 0.21600002
    private const val AIR_ACCELERATION = 0.02
    private const val GROUND_DRAG = 0.91
    private const val AIR_DRAG = 0.91
    private const val VERTICAL_DRAG = 0.98

    private const val SPRINT_JUMP_BOOST = 0.2

    const val MAX_LEAD = 3

    private const val EPSILON = 1.0e-3
    private const val SUPPORT_DEPTH = 0.06
    private const val LANDING_OVERLAP = 0.15
    private const val MAX_UNDERSHOOT = 1.0

    const val REST_SPEED = 0.005

    private const val MAX_RUN_TICKS = 40
    private const val MAX_AIR_TICKS = 60
    private const val MAX_SLIDE_TICKS = 20

    /**
     * The jump from [start], where [body] stands at rest, onto [landing], the center of the block it means to
     * land on at the height of its floor.
     */
    fun plan(space: JumpSpace, body: JumpBody, start: Vec3, landing: Vec3): JumpPlan? {
        for (lead in 0..MAX_LEAD) {
            val jump = runUp(space, body, start, landing, lead) ?: continue
            return JumpPlan(lead, jump)
        }
        return null
    }

    /** Runs from rest and takes off once the feet would leave the ground within [lead] more ticks. */
    private fun runUp(space: JumpSpace, body: JumpBody, start: Vec3, landing: Vec3, lead: Int): JumpLanding? {
        val dx = landing.x - start.x
        val dz = landing.z - start.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < EPSILON) return null
        val dirX = dx / distance
        val dirZ = dz / distance

        val acceleration = groundAcceleration(body.speed, body.friction)
        val drag = groundDrag(body.friction)
        var x = start.x
        var z = start.z
        var vx = 0.0
        var vz = 0.0

        repeat(MAX_RUN_TICKS) {
            vx += dirX * acceleration
            vz += dirZ * acceleration
            val ahead = lead + 1.0
            if (!hasSupport(space, body.halfWidth, x + vx * ahead, start.y, z + vz * ahead)) {
                return (takeOff(space, body, Vec3(x, start.y, z), vx, vz, dirX, dirZ, landing) as? JumpOutcome.Landed)?.landing
            }
            if (space.collides(bodyBox(body, x + vx, start.y, z + vz))) return null
            x += vx
            z += vz
            vx *= drag
            vz *= drag
            if ((x - start.x) * dirX + (z - start.z) * dirZ > distance) return null
        }
        return null
    }

    /**
     * What [body], on the ground at [position] moving by ([motionX], [motionZ]) and facing ([dirX], [dirZ]), does
     * this tick on its run-up to [landing].
     */
    fun decide(
        space: JumpSpace,
        body: JumpBody,
        position: Vec3,
        motionX: Double,
        motionZ: Double,
        dirX: Double,
        dirZ: Double,
        landing: Vec3,
    ): JumpDecision {
        val acceleration = groundAcceleration(body.speed, body.friction)
        val vx = motionX + dirX * acceleration
        val vz = motionZ + dirZ * acceleration
        val now = takeOff(space, body, position, vx, vz, dirX, dirZ, landing)

        val ahead = position.add(vx, 0.0, vz)
        val canWait = hasSupport(space, body.halfWidth, ahead.x, ahead.y, ahead.z)
        val drag = groundDrag(body.friction)
        val later = if (canWait) {
            takeOff(space, body, ahead, vx * drag + dirX * acceleration, vz * drag + dirZ * acceleration, dirX, dirZ, landing)
        } else {
            null
        }

        val laterIsBetter = later is JumpOutcome.Landed &&
                (now !is JumpOutcome.Landed || miss(later, landing) < miss(now, landing))
        return when {
            now is JumpOutcome.Landed && !laterIsBetter -> JumpDecision.TAKE_OFF
            canWait && later != JumpOutcome.Late && (now == JumpOutcome.Early || laterIsBetter) -> JumpDecision.RUN
            else -> JumpDecision.BRAKE
        }
    }

    private fun miss(outcome: JumpOutcome.Landed, landing: Vec3): Double {
        val rest = outcome.landing.position
        return sqrt((rest.x - landing.x) * (rest.x - landing.x) + (rest.z - landing.z) * (rest.z - landing.z))
    }

    /**
     * The jump [body] makes taking off now from [position], on the ground, facing ([dirX], [dirZ]): ([vx], [vz])
     * is what it moves by this tick, it's walking for the tick already in. [landing] is the center of the block it
     * means to land on, at the height of its floor.
     */
    fun takeOff(
        space: JumpSpace,
        body: JumpBody,
        position: Vec3,
        vx: Double,
        vz: Double,
        dirX: Double,
        dirZ: Double,
        landing: Vec3,
    ): JumpOutcome {
        val air = Body(position.x, position.y, position.z, vx + dirX * SPRINT_JUMP_BOOST, body.jumpPower, vz + dirZ * SPRINT_JUMP_BOOST)
        val acceleration = body.speed * INPUT_SCALE * AIR_ACCELERATION
        var drag = groundDrag(body.friction)
        var apex = position.y

        repeat(MAX_AIR_TICKS) { tick ->
            if (tick > 0) {
                air.vx += dirX * acceleration
                air.vz += dirZ * acceleration
            }
            val touchedDown = air.moveVertically(space, body)
            if (touchedDown && !isLandingFloor(space, body, air, landing)) {
                return missed(air, landing, dirX, dirZ)
            }
            if (touchedDown) air.y = landing.y
            air.moveHorizontally(space, body)
            apex = max(apex, air.y)
            air.vx *= drag
            air.vz *= drag
            drag = AIR_DRAG
            if (touchedDown) return settle(space, body, landing, air, dirX, dirZ, apex)

            if (air.y < landing.y - MAX_UNDERSHOOT) return missed(air, landing, dirX, dirZ)
            air.vy = (air.vy - body.gravity) * VERTICAL_DRAG
        }
        return JumpOutcome.Early
    }

    private fun missed(air: Body, landing: Vec3, dirX: Double, dirZ: Double): JumpOutcome {
        if (air.blockedRising) return JumpOutcome.Late
        if (air.blockedFalling) return JumpOutcome.Early
        val along = (air.x - landing.x) * dirX + (air.z - landing.z) * dirZ
        return if (along > 0.0) JumpOutcome.Late else JumpOutcome.Early
    }

    /** Lets the body that touched down slide to a stop with no input, as the NPC brakes once it lands. */
    private fun settle(
        space: JumpSpace,
        body: JumpBody,
        landing: Vec3,
        air: Body,
        dirX: Double,
        dirZ: Double,
        apex: Double,
    ): JumpOutcome {
        val drag = groundDrag(body.friction)
        repeat(MAX_SLIDE_TICKS) {
            if (!hasSupport(space, body.halfWidth, air.x, air.y, air.z)) return JumpOutcome.Late
            if (air.vx * air.vx + air.vz * air.vz < REST_SPEED * REST_SPEED) return rested(body, landing, air, dirX, dirZ, apex)
            air.moveHorizontally(space, body)
            air.vx *= drag
            air.vz *= drag
        }
        return rested(body, landing, air, dirX, dirZ, apex)
    }

    private fun rested(body: JumpBody, landing: Vec3, air: Body, dirX: Double, dirZ: Double, apex: Double): JumpOutcome {
        val spread = abs(dirX) + abs(dirZ)
        val nearEdge = (0.5 + body.halfWidth) * spread - LANDING_OVERLAP
        val along = (air.x - landing.x) * dirX + (air.z - landing.z) * dirZ
        if (along < -nearEdge) return JumpOutcome.Early
        return JumpOutcome.Landed(JumpLanding(Vec3(air.x, air.y, air.z), apex - air.y))
    }

    /** A body moved one axis at a time as vanilla moves it, each axis stopped by what it runs into. */
    private class Body(var x: Double, var y: Double, var z: Double, var vx: Double, var vy: Double, var vz: Double) {
        /** Whether something stopped it sideways while it still rose: it took off too close to what it hit. */
        var blockedRising = false

        /** Whether something stopped it sideways on the way down: it took off too far from what it hit. */
        var blockedFalling = false

        /** Moves along Y; true when it came down onto something. */
        fun moveVertically(space: JumpSpace, body: JumpBody): Boolean {
            if (!space.collides(bodyBox(body, x, y + vy, z))) {
                y += vy
                return false
            }
            if (vy < 0.0) return true
            vy = 0.0
            return false
        }

        /** Moves along X and Z, the faster first, as vanilla does. */
        fun moveHorizontally(space: JumpSpace, body: JumpBody) {
            if (abs(vx) >= abs(vz)) {
                moveX(space, body)
                moveZ(space, body)
            } else {
                moveZ(space, body)
                moveX(space, body)
            }
        }

        private fun moveX(space: JumpSpace, body: JumpBody) {
            if (!space.collides(bodyBox(body, x + vx, y, z))) {
                x += vx
            } else if (vx != 0.0) {
                vx = 0.0
                markBlocked()
            }
        }

        private fun moveZ(space: JumpSpace, body: JumpBody) {
            if (!space.collides(bodyBox(body, x, y, z + vz))) {
                z += vz
            } else if (vz != 0.0) {
                vz = 0.0
                markBlocked()
            }
        }

        private fun markBlocked() {
            if (vy > 0.0) blockedRising = true else blockedFalling = true
        }
    }

    /** Whether what [air] came down onto is ground at the height of the landing's floor. */
    private fun isLandingFloor(space: JumpSpace, body: JumpBody, air: Body, landing: Vec3): Boolean =
        air.y >= landing.y - EPSILON &&
                !space.collides(bodyBox(body, air.x, landing.y, air.z)) &&
                hasSupport(space, body.halfWidth, air.x, landing.y, air.z)

    /** How much faster a mob moving with [speed] walks every tick on ground of [friction], before drag. */
    fun groundAcceleration(speed: Double, friction: Double): Double =
        speed * INPUT_SCALE * speed * GROUND_ACCELERATION / (friction * friction * friction)

    /** What is left of a body's speed after a tick on ground of [friction]. */
    fun groundDrag(friction: Double): Double = friction * GROUND_DRAG

    /** How far a body that moves by [speed] this tick on ground of [friction] slides, this tick included, with no input. */
    fun slideDistance(speed: Double, friction: Double): Double = speed / (1.0 - groundDrag(friction))

    /** Whether anything is under the feet of a body [halfWidth] wide each way standing at the point; any part of its footprint will do. */
    fun hasSupport(space: JumpSpace, halfWidth: Double, x: Double, y: Double, z: Double): Boolean {
        val half = halfWidth - EPSILON
        return space.collides(AABB(x - half, y - SUPPORT_DEPTH, z - half, x + half, y - EPSILON, z + half))
    }

    private fun bodyBox(body: JumpBody, x: Double, y: Double, z: Double): AABB {
        val half = body.halfWidth - EPSILON
        return AABB(x - half, y + EPSILON, z - half, x + half, y + body.height - EPSILON, z + half)
    }
}
