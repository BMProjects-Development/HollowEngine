package ru.hollowhorizon.hollowengine.client.particles.collision

import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

class WorldCollisionProvider(private val world: Level) : CollisionProvider {
    override fun query(pos: Vec3f, size: Float, offset: Vec3f): Pair<Vec3f, Vec3f>? = query(
        AABB(
            (pos.x - size).toDouble(), (pos.y - size).toDouble(), (pos.z - size).toDouble(),
            (pos.x + size).toDouble(), (pos.y + size).toDouble(), (pos.z + size).toDouble(),
        ), offset
    )

    private fun query(inputBB: AABB, offset: Vec3f): Pair<Vec3f, Vec3f>? {
        val expandedBB = inputBB.expandTowards(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
        val collisions = world.getCollisions(null, expandedBB)
        if (collisions.any()) return null

        var iteration = 0
        var remaining = offset
        var primaryAxis = selectPrimaryAxis(offset)
        var currentBB = inputBB

        while (iteration < 1000) {
            var x = remaining.x.toDouble()
            var y = remaining.y.toDouble()
            var z = remaining.z.toDouble()

            var testBB = currentBB

            fun checkXAxis() {
                if (x == 0.0) return
                for (bb in collisions) x = bb.collide(Direction.Axis.X, testBB, x)
                if (x == 0.0) return
                testBB = testBB.expandTowards(x, 0.0, 0.0)
            }

            fun checkYAxis() {
                if (y == 0.0) return
                for (bb in collisions) y = bb.collide(Direction.Axis.Y, testBB, y)
                if (y == 0.0) return
                testBB = testBB.expandTowards(0.0, y, 0.0)
            }

            fun checkZAxis() {
                if (z == 0.0) return
                for (bb in collisions) z = bb.collide(Direction.Axis.Z, testBB, z)
                if (z == 0.0) return
                testBB = testBB.expandTowards(0.0, 0.0, z)
            }

            when (primaryAxis) {
                Axis.X -> {
                    checkXAxis()
                    checkYAxis()
                    checkZAxis()
                }

                Axis.Y -> {
                    checkYAxis()
                    checkXAxis()
                    checkZAxis()
                }

                Axis.Z -> {
                    checkZAxis()
                    checkYAxis()
                    checkXAxis()
                }
            }


            if (x == remaining.x.toDouble() && y == remaining.y.toDouble() && z == remaining.z.toDouble()) {
                return null
            }

            val (minFraction, minAxis) = calculateSafeFraction(x, y, z, remaining)

            if (minFraction <= 0.001 || iteration > 3) {
                val axisVec = minAxis.vec
                val normal = if (minAxis.get(offset) > 0) axisVec.times(-1f) else axisVec
                return offset.minus(remaining) to normal
            }

            val safeOffset = remaining.times(minFraction - 0.0001f)
            remaining = remaining.minus(safeOffset)
            currentBB = currentBB.move(safeOffset.x.toDouble(), safeOffset.y.toDouble(), safeOffset.z.toDouble())
            primaryAxis = minAxis
            iteration++
        }
        return null
    }

    private fun selectPrimaryAxis(offset: Vec3f): Axis {
        return when {
            abs(offset.x) > abs(offset.y) && abs(offset.x) > abs(offset.z) -> Axis.X
            abs(offset.y) > abs(offset.z) -> Axis.Y
            else -> Axis.Z
        }
    }

    private fun calculateSafeFraction(x: Double, y: Double, z: Double, offset: Vec3f): Pair<Float, Axis> {
        val xFraction = x.toFloat() / offset.x.safe()
        val yFraction = y.toFloat() / offset.y.safe()
        val zFraction = z.toFloat() / offset.z.safe()

        return when {
            xFraction < yFraction && xFraction < zFraction -> xFraction to Axis.X
            yFraction < zFraction -> yFraction to Axis.Y
            else -> zFraction to Axis.Z
        }
    }

    private fun Float.safe() = if (this == 0f) 0.00001f else this

    private enum class Axis {
        X, Y, Z;

        val vec: Vec3f
            get() = when (this) {
                X -> Vec3f(1f, 0f, 0f)
                Y -> Vec3f(0f, 1f, 0f)
                Z -> Vec3f(0f, 0f, 1f)
            }

        fun get(vec: Vec3f): Float = get(vec.x, vec.y, vec.z)

        fun get(x: Float, y: Float, z: Float): Float =
            when (this) {
                X -> x
                Y -> y
                Z -> z
            }
    }
}