package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Path in front of an NPC as a line. From where it stands through the nodes still ahead. [stopShort] is how
 * far short of the end of the line the NPC has to stop, at the end of the path or before a jump.
 */
internal class PathAhead(val points: List<Vec3>, val stopShort: Double?) {
    /** A turn of the line: [distance] along it from the NPC, [degrees] between the stretch before and after. */
    class Turn(val distance: Double, val degrees: Double)

    private val lengths = DoubleArray(points.size).also { lengths ->
        for (index in 1 until points.size) lengths[index] = lengths[index - 1] + horizontal(points[index - 1], points[index])
    }

    val length: Double get() = lengths.last()

    /**
     * The turns of the line, nearest first, at its nodes. The node the NPC is about to reach is left out once it
     * is close: the line to it from wherever the NPC strayed makes a turn there that the path does not.
     */
    val turns: List<Turn> = (1 until points.size - 1).mapNotNull { index ->
        if (index == 1 && lengths[1] < CLOSE_NODE) return@mapNotNull null
        val degrees = turnAt(index)?.takeIf { it >= MIN_TURN } ?: return@mapNotNull null
        Turn(lengths[index], degrees)
    }

    /** The point [distance] along the line, its end past that. */
    fun pointAt(distance: Double): Vec3 {
        if (distance <= 0.0) return points.first()
        for (index in 1 until points.size) {
            if (lengths[index] < distance) continue
            val segment = lengths[index] - lengths[index - 1]
            val t = if (segment <= 0.0) 1.0 else (distance - lengths[index - 1]) / segment
            return points[index - 1].lerp(points[index], t)
        }
        return points.last()
    }

    /** The index of the last node strictly before [distance] along the line. */
    fun nodeBefore(distance: Double): Int {
        for (index in points.indices.reversed()) if (lengths[index] < distance) return index
        return 0
    }

    /** How far the line runs from the NPC before it turns by more than [degrees]: to its end when it never does. */
    fun straightAhead(degrees: Double): Double = turns.firstOrNull { it.degrees > degrees }?.distance ?: length

    private fun turnAt(index: Int): Double? {
        val inX = points[index].x - points[index - 1].x
        val inZ = points[index].z - points[index - 1].z
        val outX = points[index + 1].x - points[index].x
        val outZ = points[index + 1].z - points[index].z
        val inLength = sqrt(inX * inX + inZ * inZ)
        val outLength = sqrt(outX * outX + outZ * outZ)
        if (inLength < MIN_SEGMENT || outLength < MIN_SEGMENT) return null
        val cos = ((inX * outX + inZ * outZ) / (inLength * outLength)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private fun horizontal(from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return sqrt(dx * dx + dz * dz)
    }

    private companion object {
        /** Stretches shorter than this, in blocks, have no direction worth a turn. */
        const val MIN_SEGMENT = 0.05

        /** Turns smaller than this, in degrees, are none. */
        const val MIN_TURN = 1.0

        /** How near, in blocks, the next node is when its turn no longer counts. */
        const val CLOSE_NODE = 1.0
    }
}
