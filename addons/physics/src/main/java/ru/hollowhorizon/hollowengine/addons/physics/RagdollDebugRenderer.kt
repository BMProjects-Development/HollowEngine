package ru.hollowhorizon.hollowengine.addons.physics

import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorlds
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * The bodies of every live rag-doll as vanilla hitboxes (F3+B).
 */
@ClientOnly
object RagdollDebugRenderer {
    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_ENTITIES) return

        val minecraft = Minecraft.getInstance()
        if (!minecraft.entityRenderDispatcher.shouldRenderHitBoxes()) return
        val level = minecraft.level ?: return
        val world = PhysicsWorlds.find(level) ?: return
        if (world.ragdolls.isEmpty()) return

        val camera = event.camera.position
        val buffers = minecraft.renderBuffers().bufferSource()
        val poseStack = event.poseStack

        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)
        val lines = DebugLines.batch(buffers, poseStack)
        world.ragdolls.forEach { ragdoll ->
            ragdoll.forEachBody { bone, position, rotation ->
                val axis: Vec3f = bone.axis.rotated(rotation)
                val tip = (bone.length - bone.radius).coerceAtLeast(bone.radius)
                lines.capsule(position + axis * bone.radius, position + axis * tip, bone.radius, BODY_COLOR)
            }
        }
        poseStack.popPose()

        buffers.endBatch(DebugLines.OVERLAY)
    }

    private val BODY_COLOR = 0xFF4DFF99.toInt()
}
