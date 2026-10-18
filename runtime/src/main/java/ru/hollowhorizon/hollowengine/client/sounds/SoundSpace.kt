package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.common.sounds.SoundArea
import ru.hollowhorizon.hollowengine.common.sounds.depth
import ru.hollowhorizon.hollowengine.common.sounds.outline
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.exp

/** Where the listener is and how fast it moves, kept by the frame the sounds are placed in. */
object SoundListener {
    var position: Vec3 = Vec3.ZERO
        private set

    /** Blocks per second, smoothed over a few frames so a step does not wobble the pitch. */
    var velocity: Vec3 = Vec3.ZERO
        private set

    private var measured = false

    /** Follows the camera by [seconds] since the last frame. */
    fun update(seconds: Float) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        if (!camera.isInitialized) return
        val next = camera.position
        if (measured && seconds > 0f) {
            val moved = next.subtract(position).scale(1.0 / seconds)
            val keep = exp(-seconds / SMOOTHING).toDouble()
            velocity = velocity.scale(keep).add(moved.scale(1.0 - keep))
        }
        position = next
        measured = true
    }

    fun reset() {
        measured = false
        velocity = Vec3.ZERO
    }

    private const val SMOOTHING = 0.15f
}

/**
 * The areas of a sound put into the world: [toWorld] takes the space they are written in to the world's. Where they
 * stand and how far the listener is from them are measured in blocks of the world, so an attachment scaled up
 * makes them larger.
 */
class SoundShape(private val toWorld: Matrix4f, private val areas: List<SoundArea>) {
    private val toLocal = Matrix4f(toWorld).invert()
    private val scale = toWorld.getScale(Vector3f()).let { (it.x + it.y + it.z) / 3f }

    /** How far [point] is past the edge of the nearest area, in blocks, negative inside; the nearest point goes to [nearest]. */
    fun depth(point: Vec3, nearest: Vector3f): Float {
        val local = toLocal.transformPosition(Vector3f(point.x.toFloat(), point.y.toFloat(), point.z.toFloat()))
        val closest = MutableVec3f()
        val depth = areas.depth(Vec3f(local.x, local.y, local.z), closest)
        toWorld.transformPosition(closest.x, closest.y, closest.z, nearest)
        return depth * scale
    }

    /** Hands [line] the edges of the areas in the world, for showing where the sound plays. */
    fun outline(line: (Vec3, Vec3) -> Unit) {
        val from = Vector3f()
        val to = Vector3f()
        areas.forEach { area ->
            area.outline { start, end ->
                toWorld.transformPosition(start.x, start.y, start.z, from)
                toWorld.transformPosition(end.x, end.y, end.z, to)
                line(Vec3(from.x.toDouble(), from.y.toDouble(), from.z.toDouble()), Vec3(to.x.toDouble(), to.y.toDouble(), to.z.toDouble()))
            }
        }
    }
}
