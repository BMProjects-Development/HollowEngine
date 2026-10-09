package ru.hollowhorizon.hollowengine.client.colliders

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.colliders.ColliderModes
import ru.hollowhorizon.hollowengine.common.colliders.EntityColliders
import ru.hollowhorizon.hollowengine.common.colliders.hostPosition
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** Draws an entity's colliders where the server places them, between ticks, in the hitbox view (F3+B). */
object ColliderDebugRenderer {
    /**
     * Draws the colliders of [entity] into [lines], with [poseStack] at the entity's position as the
     * dispatcher leaves it. True when the colliders stand in for the entity's box, which then is not drawn.
     */
    fun renderHitbox(entity: Entity, partialTick: Float, poseStack: PoseStack, lines: VertexConsumer): Boolean {
        val colliders = ClientColliderTickPoses.at(entity, partialTick)
        if (colliders.isNotEmpty()) {
            val origin = hostPosition(entity, partialTick)
            val batch = DebugLines.Batch(lines, poseStack.last())
            colliders.forEach { collider ->
                val color = colorOf(collider.spec.modes)
                collider.volume.outline { start, end ->
                    batch.line(start.subtract(origin).toVec3f(), end.subtract(origin).toVec3f(), color)
                }
            }
        }
        return EntityColliders.hasTargets(entity)
    }

    /** Clickable colliders draw blue, hit-taking ones green, solid ones orange, ones that only push gray. */
    fun colorOf(modes: ColliderModes): Int = when {
        modes.interact -> INTERACT_COLOR
        modes.hit -> HIT_COLOR
        modes.solid -> SOLID_COLOR
        else -> PUSH_COLOR
    }

    private fun Vec3.toVec3f() = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())

    private val HIT_COLOR = 0xCC4DFF99.toInt()
    private val INTERACT_COLOR = 0xCC59B8FF.toInt()
    private val PUSH_COLOR = 0xCCE0E0E0.toInt()
    private val SOLID_COLOR = 0xCCFFB347.toInt()
}
