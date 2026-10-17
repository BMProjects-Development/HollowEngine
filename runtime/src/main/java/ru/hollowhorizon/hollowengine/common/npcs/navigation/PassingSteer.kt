package ru.hollowhorizon.hollowengine.common.npcs.navigation

import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.EntityBodies
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class PassingSteer(private val mob: Mob) {
    private var sides: Int2IntMap = Int2IntOpenHashMap()

    private var givingWayTo = NOBODY
    private var waitingAt: Vec3? = null
    private var waitedTicks = 0

    /** Whether the NPC stands and lets another go by this tick, rather than walking. */
    var waits = false
        private set

    fun givesWayTo(id: Int): Boolean = givingWayTo == id

    fun around(heading: Vec3, ahead: PathAhead, canHeadFor: (Vec3, Vec3) -> Boolean): Vec3 {
        waits = false
        val start = mob.position()
        val toX = heading.x - start.x
        val toZ = heading.z - start.z
        val length = Mth.length(toX, toZ)
        val walksOn = mob.onGround() && ahead.length > FINAL_APPROACH && length > MIN_HEADING &&
                abs(heading.y - start.y) <= mob.maxUpStep() && EntityBodies.isTangible(mob)
        if (!walksOn) {
            sides.clear()
            stopGivingWay()
            return heading
        }

        val dirX = toX / length
        val dirZ = toZ / length
        if (givingWayTo != NOBODY) {
            if (stillGivesWay(dirX, dirZ)) return wait(start, heading)
            stopGivingWay()
        }

        val end = ahead.points.last()
        val near = mob.boundingBox.inflate(LOOK_RADIUS, 0.0, LOOK_RADIUS)
        val passers = mob.level().getEntities(mob, near, ::meets).map {
            Passer(it.id, it.x - mob.x, it.z - mob.z, it.x - it.xo, it.z - it.zo, it.bbWidth.toDouble(), shareOf(it))
        }.filter { Mth.length(it.x + mob.x - end.x, it.z + mob.z - end.z) >= clearance(mob.bbWidth.toDouble(), it) }
        val met = Int2IntOpenHashMap()
        val drag = JumpSimulation.groundDrag(NpcNavigationGeometry.frictionUnder(mob.level(), start))
        val speed = max(mob.deltaMovement.horizontalDistance() / drag, MIN_SPEED)
        val meeting = meet(dirX, dirZ, speed, mob.bbWidth.toDouble(), passers, sides, met)
        sides = met
        val threat = meeting.threat
        if (threat == null || abs(meeting.shift) < MIN_SHIFT) return heading

        val sideways = (meeting.shift * SHIFT_GAIN).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        for (scale in SHIFT_TRIES) {
            val point = Vec3(heading.x - dirZ * sideways * scale, heading.y, heading.z + dirX * sideways * scale)
            if (canHeadFor(start, point)) return point
        }

        val other = mob.level().getEntity(threat.id)
        val aside = asideOf(start, dirX, dirZ, if (meeting.shift > 0.0) 1 else -1, clearance(mob.bbWidth.toDouble(), threat), canHeadFor)
        if (other == null || goesFirst(other, aside != null)) return heading
        givingWayTo = other.id
        waitingAt = aside
        waitedTicks = 0
        return wait(start, heading)
    }

    /**
     * Whether the NPC goes on and leaves it to [other] to give way, [stepsAside] saying whether it has room to step
     * aside itself. It goes on when the other gives way already, and when it has no room, as in a narrow passage:
     * then it is the other that has to. Anyone that never gives way, a player or a mob, is waited for.
     */
    private fun goesFirst(other: Entity, stepsAside: Boolean): Boolean {
        val passing = (other as? NpcEntity)?.takeIf { it.npcNavigation.settings.avoid.entities }?.npcNavigation?.passing
            ?: return false
        return passing.givesWayTo(mob.id) || !stepsAside
    }

    /** Whether the NPC still waits for the one it gives way to: until that one is by, gone, or a while has passed. */
    private fun stillGivesWay(dirX: Double, dirZ: Double): Boolean {
        val other = mob.level().getEntity(givingWayTo) ?: return false
        if (++waitedTicks > MAX_WAIT_TICKS || !other.isAlive || mob.distanceTo(other) > LOOK_RADIUS + 1.0) return false
        val mutual = (other as? NpcEntity)?.npcNavigation?.passing?.givesWayTo(mob.id) == true
        if (mutual && mob.id < other.id) return false
        val behind = (other.x - mob.x) * dirX + (other.z - mob.z) * dirZ < 0.0
        return !behind
    }

    /** Steps to where the NPC waits, then stands there. */
    private fun wait(start: Vec3, heading: Vec3): Vec3 {
        val at = waitingAt
        if (at != null && Mth.length(at.x - start.x, at.z - start.z) > WAIT_REACH) return at
        waits = true
        return heading
    }

    /** Walks on: the path was planned from where the NPC was before it stepped aside, so it is planned anew. */
    private fun stopGivingWay() {
        if (givingWayTo == NOBODY) return
        givingWayTo = NOBODY
        waitingAt = null
        mob.navigation.recomputePath()
    }

    /** A point [distance] to the [side] of the NPC, or to the other side, that it can step to; null when neither. */
    private fun asideOf(start: Vec3, dirX: Double, dirZ: Double, side: Int, distance: Double, canHeadFor: (Vec3, Vec3) -> Boolean): Vec3? {
        for (way in intArrayOf(side, -side)) {
            val point = Vec3(start.x - dirZ * distance * way, start.y, start.z + dirX * distance * way)
            if (canHeadFor(start, point)) return point
        }
        return null
    }

    private fun meets(other: Entity): Boolean =
        other is LivingEntity && other.isAlive && !other.isSpectator && other.rootVehicle !== mob.rootVehicle &&
                EntityBodies.isTangible(other)

    /**
     * How much of the way out the NPC makes: half when the other walks and makes way the same, all of it when the
     * other does not, or stands giving way.
     */
    private fun shareOf(other: Entity): Double {
        if (other !is NpcEntity || other.navigation.isDone || !other.npcNavigation.settings.avoid.entities) return 1.0
        return if (other.npcNavigation.passing.waits) 1.0 else 0.5
    }

    /**
     * One the NPC may meet: where it is from the NPC, how far it moves a tick, how wide it is, and the share of the
     * way out the NPC makes.
     */
    class Passer(val id: Int, val x: Double, val z: Double, val motionX: Double, val motionZ: Double, val width: Double, val share: Double)

    /** How far the NPC has to get aside, to its right or, when negative, to its left, and for whom: none when for nobody. */
    class Meeting(val shift: Double, val threat: Passer?)

    companion object {
        private const val NOBODY = Int.MIN_VALUE

        private const val LOOK_RADIUS = 4.0
        private const val HORIZON = 30.0

        private const val MARGIN = 0.4

        private const val HEAD_ON = 0.1

        private const val FAR_EASING = 0.5

        private const val FINAL_APPROACH = 1.5

        private const val MAX_WAIT_TICKS = 100
        private const val WAIT_REACH = 0.15

        private const val MIN_HEADING = 0.1
        private const val MIN_SPEED = 0.05
        private const val MIN_SHIFT = 0.02

        /** How far aside the NPC heads for a block it has to get out of the way, and the most it heads aside. */
        const val SHIFT_GAIN = 2.0
        const val MAX_SHIFT = 1.0

        /** The shares of the way aside tried, when a wall or a ledge stops the whole of it. */
        private val SHIFT_TRIES = doubleArrayOf(1.0, 0.5)

        private fun clearance(width: Double, other: Passer): Double = (width + other.width) * 0.5 + MARGIN

        /**
         * How an NPC [width] wide walking along ([dirX], [dirZ]) at [speed] blocks a tick meets [passers]: how far,
         * in blocks, it has to get aside to pass them all with room, and the one that takes the most. For each it
         * works out where the two will be closest, and when that is too close, heads past on the side the other
         * will not be on, keeping right when they meet head on, so two NPCs doing the same pass each other. It
         * keeps passing each one on the side it took in [sides], and puts the sides it takes now into [met].
         */
        fun meet(
            dirX: Double,
            dirZ: Double,
            speed: Double,
            width: Double,
            passers: List<Passer>,
            sides: Int2IntMap,
            met: Int2IntMap,
        ): Meeting {
            var shift = 0.0
            var threat: Passer? = null
            for (other in passers) {
                val closeX = dirX * speed - other.motionX
                val closeZ = dirZ * speed - other.motionZ
                val closing = other.x * closeX + other.z * closeZ
                if (closing <= 0.0) continue
                val ticks = min(closing / (closeX * closeX + closeZ * closeZ), HORIZON)
                val closestX = other.x - closeX * ticks
                val closestZ = other.z - closeZ * ticks
                val clearance = clearance(width, other)
                val closest = Mth.length(closestX, closestZ)
                if (closest >= clearance) continue

                val offset = closestX * -dirZ + closestZ * dirX
                val side = if (sides.containsKey(other.id)) sides.get(other.id) else if (offset > HEAD_ON) -1 else 1
                met.put(other.id, side)
                val need = (clearance - closest) * (1.0 - ticks / HORIZON * FAR_EASING) * other.share
                if (need > abs(shift)) {
                    shift = side * need
                    threat = other
                }
            }
            return Meeting(shift, threat)
        }
    }
}
