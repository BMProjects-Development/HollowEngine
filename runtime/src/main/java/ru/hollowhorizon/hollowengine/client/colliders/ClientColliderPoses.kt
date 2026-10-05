package ru.hollowhorizon.hollowengine.client.colliders

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.NestedModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstanceOrNull
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ModelNodeEntry
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelNodes
import ru.hollowhorizon.hollowengine.common.colliders.ColliderScope
import ru.hollowhorizon.hollowengine.common.colliders.EntityCollider
import ru.hollowhorizon.hollowengine.common.colliders.EntityRigs
import ru.hollowhorizon.hollowengine.common.colliders.applyOverrides
import ru.hollowhorizon.hollowengine.common.colliders.entityModelMatrix
import ru.hollowhorizon.hollowengine.common.colliders.hasColliders
import ru.hollowhorizon.hollowengine.common.colliders.hostPosition
import ru.hollowhorizon.hollowengine.common.colliders.placeColliders
import ru.hollowhorizon.hollowengine.common.models.MAX_NESTING
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f

/**
 * The colliders of an entity on this client: on the pose the renderer last gave its model and the models
 * hung on it, so they are where the player sees them. A model that was never drawn has none to offer.
 */
object ClientColliderPoses {
    private val rigs = EntityRigs { model -> RigAssets.of(ResourceLocation.tryParse(model)) }

    fun of(entity: Entity): List<EntityCollider> {
        val node = modelNode(entity) ?: return emptyList()
        if (rig(entity)?.hasColliders() != true) return emptyList()

        val instance = entity.modelInstanceOrNull(node.nodeId, node.model.model) ?: return emptyList()
        val partialTick = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)
        val placed = DrawnScope(instance.attachment, null, 0).placeColliders(
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

    /** The rig of [entity]'s model with what it hangs on it, the colliders of nested models gathered in. */
    fun rig(entity: Entity): ModelRig? = modelNode(entity)?.let { rigs.of(entity, it.model) }

    private fun modelNode(entity: Entity): ModelNodeEntry? =
        AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelNodes()?.firstOrNull()

    /** A drawn model as its colliders see it, the models drawn on it included. */
    private class DrawnScope(private val model: ModelAttachment, override val modelMatrix: Mat4f?, depth: Int) : ColliderScope {
        override val roots: List<RuntimeNode> get() = model.nodes
        override val rig: ModelRig get() = model.rig

        override val nested: List<ColliderScope> = if (depth >= MAX_NESTING) emptyList() else model.rig.allAttachments().mapNotNull { (bone, spec) ->
            if (spec !is ModelAttachmentSpec) return@mapNotNull null
            val holder = if (bone == null) model.modelRoot else model.findNode(bone)
            val drawn = holder?.attachments?.filterIsInstance<NestedModelAttachment>()?.firstOrNull { it.spec.id == spec.id } ?: return@mapNotNull null
            DrawnScope(drawn.model, drawn.model.globalMatrix, depth + 1)
        }
    }
}
