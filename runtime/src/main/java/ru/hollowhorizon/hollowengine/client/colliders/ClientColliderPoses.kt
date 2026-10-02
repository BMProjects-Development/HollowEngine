package ru.hollowhorizon.hollowengine.client.colliders

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstanceOrNull
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ModelNodeEntry
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelNodes
import ru.hollowhorizon.hollowengine.common.colliders.EntityCollider
import ru.hollowhorizon.hollowengine.common.colliders.applyOverrides
import ru.hollowhorizon.hollowengine.common.colliders.entityModelMatrix
import ru.hollowhorizon.hollowengine.common.colliders.hasColliders
import ru.hollowhorizon.hollowengine.common.colliders.hostPosition
import ru.hollowhorizon.hollowengine.common.colliders.placeColliders
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/**
 * The colliders of an entity on this client: on the pose the renderer last gave its model, so they are
 * where the player sees the model. A model that was never drawn has none to offer.
 */
object ClientColliderPoses {
    fun of(entity: Entity): List<EntityCollider> {
        val node = modelNode(entity) ?: return emptyList()
        val rig = rigOf(node)
        if (!rig.hasColliders()) return emptyList()

        val instance = entity.modelInstanceOrNull(node.nodeId, node.model.model) ?: return emptyList()
        val partialTick = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)
        val placed = rig.placeColliders(
            instance.attachment.nodes,
            entityModelMatrix(entity, node.transform, partialTick),
            hostPosition(entity, partialTick),
        )
        return applyOverrides(entity, placed)
    }

    /**
     * Where the name over [entity] hangs relative to its feet: above the top of its colliders as drawn, where
     * vanilla puts it above the top of its box, so it follows the model as it stands, crouches or lies.
     */
    fun nameplateAttachment(entity: Entity, vanilla: Vec3): Vec3 {
        val colliders = of(entity)
        if (colliders.isEmpty()) return vanilla
        val partialTick = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)
        val top = colliders.maxOf { it.box.bounds.maxY } - hostPosition(entity, partialTick).y - 0.2
        return Vec3(vanilla.x, top, vanilla.z)
    }

    fun rig(entity: Entity): ModelRig? = modelNode(entity)?.let(::rigOf)

    private fun modelNode(entity: Entity): ModelNodeEntry? =
        AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelNodes()?.firstOrNull()

    private fun rigOf(node: ModelNodeEntry): ModelRig = RigAssets.of(ResourceLocation.tryParse(node.model.model))
}
