package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3

/** Which way an NPC faces while it walks. Facing anything but its path, it walks sideways or backward as it has to. */
sealed interface Facing {
    /** Faces the way it walks. */
    data object Path : Facing

    /** Keeps facing the point [target] gives, an entity's or a fixed one; null lets it face its path for that tick. */
    class Toward(internal val target: () -> Vec3?) : Facing

    /** Keeps facing [yaw], in degrees, as vanilla counts yaw. */
    class Yaw(val yaw: Float) : Facing

    companion object {
        /** Faces [entity] for as long as it is there. */
        fun at(entity: Entity): Facing = Toward { entity.takeUnless(Entity::isRemoved)?.eyePosition }

        /** Faces [position]. */
        fun at(position: Vec3): Facing = Toward { position }
    }
}

/** A way to face that holds the body: the [yaw] to turn to, how fast in [turnSpeed] degrees a tick, and where the head looks. */
internal class HeldFacing(val yaw: Float, val turnSpeed: Float, val look: Vec3)

/**
 * Which way an NPC walking a path faces, by what asked for it last: the facing of its move, a look started
 * while it walks, which lasts as long as it is asked for every tick, or a backstep, when its move faces the path.
 */
internal class WalkFacing(private val mob: Mob) {
    private var move: Facing = Facing.Path
    private var moveSince = Long.MIN_VALUE

    private var look: Vec3? = null
    private var lookSince = Long.MIN_VALUE
    private var lookLast = Long.MIN_VALUE
    private var lookTurn = 0f

    /** The yaw it keeps while it backsteps, or null when it does not. */
    var backstep: Float? = null

    val isBackstepping: Boolean get() = backstep != null

    fun setMove(facing: Facing) {
        move = facing
        moveSince = mob.level().gameTime
    }

    /** Asked every tick a look lasts: faces [target], turning by at most [turnSpeed] degrees a tick. */
    fun look(target: Vec3, turnSpeed: Float) {
        val now = mob.level().gameTime
        if (lookLast < now - 1) lookSince = now
        lookLast = now
        look = target
        lookTurn = turnSpeed
    }

    /** The facing that holds the body this tick, or null when it faces its path. */
    fun held(): HeldFacing? {
        val now = mob.level().gameTime
        val looking = look?.takeIf { lookLast >= now - 1 }
        if (looking != null && lookSince >= moveSince) return HeldFacing(yawTo(looking), lookTurn, looking)
        when (val facing = move) {
            is Facing.Toward -> facing.target()?.let { return HeldFacing(yawTo(it), FACING_TURN, it) }
            is Facing.Yaw -> return HeldFacing(facing.yaw, FACING_TURN, ahead(facing.yaw))
            Facing.Path -> backstep?.let { return HeldFacing(it, FACING_TURN, ahead(it)) }
        }
        return null
    }

    val facesPath: Boolean get() = move == Facing.Path

    private fun yawTo(target: Vec3): Float =
        (Mth.atan2(target.z - mob.z, target.x - mob.x) * Mth.RAD_TO_DEG - 90.0).toFloat()

    /** A point straight ahead of the eyes, the body turned to [yaw]. */
    private fun ahead(yaw: Float): Vec3 {
        val radians = yaw * Mth.DEG_TO_RAD
        return mob.eyePosition.add(-Mth.sin(radians) * LOOK_REACH, 0.0, Mth.cos(radians) * LOOK_REACH)
    }

    private companion object {
        /** How fast the body turns to a facing its move holds, in degrees a tick. */
        const val FACING_TURN = 15f
        const val LOOK_REACH = 4.0
    }
}
