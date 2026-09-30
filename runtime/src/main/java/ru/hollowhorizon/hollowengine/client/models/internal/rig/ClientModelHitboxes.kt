package ru.hollowhorizon.hollowengine.client.models.internal.rig

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstance
import ru.hollowhorizon.hollowengine.client.render.resolveNodeWorldTransform
import ru.hollowhorizon.hollowengine.common.attachments.binding.NodeRuntimeState
import ru.hollowhorizon.hollowengine.common.models.HitboxGeometry
import ru.hollowhorizon.hollowengine.common.models.hitboxes

object ClientModelHitboxes {
    fun boxes(entity: Entity): List<HitboxGeometry> = buildList {
        val partialTick = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)
        NodeRuntimeState.service(entity.level()).forEachModelNodeOf(entity) { _, entry ->
            val instance = entity.modelInstance(entry.nodeId, entry.model.model)
            val attachment = instance.attachment
            attachment.entity = entity as? LivingEntity
            instance.configure(entry.animations, entry.materials)
            val world = resolveNodeWorldTransform(entity, entry.transform, partialTick)
            instance.update(AnimatorEvaluationContext().also {
                fillAnimationVariables(it, entity, partialTick)
                it.modelToWorld = world
            })
            addAll(attachment.rig.hitboxes(attachment.nodes, world.matrixF).map { it.geometry })
        }
    }
}
