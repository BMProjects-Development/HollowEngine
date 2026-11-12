package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** The pivot is a point in the world, including when it is off the center of the viewport. */
internal data class CutsceneCameraNavigation(val pose: CameraPose, val pivot: Vec3f) {
    val distance: Float get() = pose.position.distance(pivot)

    fun orbit(dx: Float, dy: Float): CutsceneCameraNavigation {
        if (dx == 0f && dy == 0f) return this
        val turned = pose.look(dx, dy)
        val offset = pose.position - pivot
        val rotated =
            Vector3f(offset.x, offset.y, offset.z).rotate(pose.orientation().conjugate()).rotate(turned.orientation())
        return copy(pose = turned.copy(position = pivot + Vec3f(rotated.x, rotated.y, rotated.z)))
    }

    fun pan(dx: Float, dy: Float, viewportHeight: Float): CutsceneCameraNavigation {
        if (viewportHeight <= 0f || (dx == 0f && dy == 0f)) return this
        val unitsPerPixel = 2f * distance * pose.halfVerticalExtent / viewportHeight
        val shift = (pose.direction(1f, 0f, 0f) * -dx + pose.direction(0f, 1f, 0f) * dy) * unitsPerPixel
        return CutsceneCameraNavigation(pose.copy(position = pose.position + shift), pivot + shift)
    }

    fun dolly(direction: Vec3f, displacement: Float): CutsceneCameraNavigation =
        copy(pose = pose.copy(position = pose.position + direction * displacement))

    companion object {
        fun aroundPointer(
            pose: CameraPose,
            ray: Vec3f,
            fallback: Vec3f?,
            minecraft: Minecraft,
        ): CutsceneCameraNavigation {
            val level = minecraft.level
            val player = minecraft.player
            val picked = if (level == null || player == null) null else {
                val reach = minecraft.gameRenderer.depthFar
                val hit = level.clip(
                    ClipContext(
                        pose.position.toVec3(),
                        (pose.position + ray * reach).toVec3(),
                        ClipContext.Block.VISUAL,
                        ClipContext.Fluid.NONE,
                        player
                    )
                )
                hit.location.takeUnless { hit.type == HitResult.Type.MISS }?.let {
                    Vec3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat())
                }
            }
            return CutsceneCameraNavigation(pose, picked ?: fallback ?: (pose.position + ray * DEFAULT_DISTANCE))
        }

        private fun Vec3f.toVec3() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

        private const val DEFAULT_DISTANCE = 8f
    }
}

internal fun CameraPose.look(dx: Float, dy: Float): CameraPose = copy(
    rotation = Vec3f((pitch + dy * LOOK_SPEED).coerceIn(-90f, 90f), yaw + dx * LOOK_SPEED, roll),
)

private const val LOOK_SPEED = 0.25f
