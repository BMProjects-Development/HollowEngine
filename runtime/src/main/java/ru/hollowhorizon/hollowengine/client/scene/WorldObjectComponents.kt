package ru.hollowhorizon.hollowengine.client.scene

import net.minecraft.client.Minecraft
import org.joml.Matrix4d
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjects
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent

internal object WorldObjectComponents {
    private val placement = Matrix4f()
    private val sceneToWorld = Matrix4d()
    private val animation = AnimatorEvaluationContext()

    fun update(event: RenderLevelStageEvent) {
        val level = Minecraft.getInstance().level ?: return
        for (entity in WorldObjects.all(level)) {
            val root = entity.sceneObjectOrNull ?: continue
            val context = root.scene.context as? ClientSceneContext ?: continue
            if (!context.hasUpdates) continue
            val pose = entity.pose(event.partialTick)
            val position = pose.position
            sceneToWorld.translation(position.x, position.y, position.z).rotate(pose.rotation)
                .scale(pose.scale.x.toDouble(), pose.scale.y.toDouble(), pose.scale.z.toDouble())
            val variables = if (context.needsAnimationVariables) {
                animation.also { fillAnimationVariables(it, entity, event.partialTick) }
            } else null
            context.updateWorld(sceneToWorld, entity, "object", event.camera.position, variables)
        }
    }

    fun collect(event: RenderLevelStageEvent) {
        val level = Minecraft.getInstance().level ?: return
        val camera = event.camera.position
        val view = event.poseStack.last().pose()
        for (entity in WorldObjects.all(level)) {
            val context = entity.sceneObjectOrNull?.scene?.context as? ClientSceneContext ?: continue
            if (!context.hasDraws) continue
            val pose = entity.pose(event.partialTick)
            val position = pose.position
            placement.translation(
                (position.x - camera.x).toFloat(),
                (position.y - camera.y).toFloat(),
                (position.z - camera.z).toFloat(),
            ).rotate(pose.rotation).scale(pose.scale).mulLocal(view)
            context.collect(placement)
        }
    }
}
