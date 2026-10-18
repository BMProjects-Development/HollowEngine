package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import org.joml.Quaternionf
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix3f
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat3f

class ItemNode(val entity: () -> LivingEntity?, val slot: EquipmentSlot, parent: Attachment?): Attachment(parent) {
    override fun collectCommands(pipeline: RenderPipeline) {
        super.collectCommands(pipeline)
        pipeline.addBatchedRenderable {
            val entity = entity() ?: return@addBatchedRenderable
            stack.pushPose()

            stack.mulPose(globalMatrix.asMatrix4f())
            stack.last().normal().mul(globalMatrix.getUpperLeft(MutableMat3f()).asMatrix3f())

            stack.mulPose(Quaternionf().rotateX(-90 * Mth.DEG_TO_RAD))

            Minecraft.getInstance().itemRenderer.renderStatic(
                entity,
                entity.getItemBySlot(slot),
                when (slot) {
                    EquipmentSlot.MAINHAND -> ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    EquipmentSlot.OFFHAND -> ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    EquipmentSlot.HEAD -> ItemDisplayContext.HEAD
                    else -> ItemDisplayContext.FIXED
                },
                slot == EquipmentSlot.OFFHAND,
                stack,
                source,
                entity.level(),
                light, overlay, 0
            )

            stack.popPose()
        }
    }
}