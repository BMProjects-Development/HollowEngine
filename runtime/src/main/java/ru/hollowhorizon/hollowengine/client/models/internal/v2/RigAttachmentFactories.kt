package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.client.models.internal.extrusion.MeshExtrusionAttachment
import ru.hollowhorizon.hollowengine.common.models.ItemSlotAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.MeshExtrusionSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.models.RigItemSlot
import ru.hollowhorizon.hollowengine.common.utils.rl

/** What one bone of the model this attachment is being built for is, and whose model it is. */
class RigAttachmentContext(
    val node: RenderModelNode,
    val entity: () -> LivingEntity?,
    val owner: ModelAttachment? = null,
    /** The entity that carries the model, living or not; see [ModelAttachment.carrier]. */
    val carrier: () -> Entity? = { null },
)

/**
 * Turns one kind of rig attachment spec into the thing that hangs on the bone.
 */
fun interface RigAttachmentFactory {
    fun create(spec: Any, context: RigAttachmentContext): Attachment?
}

/**
 * A running attachment that can take a changed component without being recreated.
 */
interface RespecAttachment {
    val spec: Any

    fun canRespec(spec: Any): Boolean = this.spec::class == spec::class

    fun respec(spec: Any)
}

object RigAttachmentFactories {
    val point = ExtensionPoints.create<RigAttachmentFactory>("hollowengine:rig/attachment_factories".rl)

    init {
        register("hollowengine:rig/item") { spec, context ->
            ItemNode(context.entity, (spec as ItemSlotAttachmentSpec).slot.vanilla(), context.node)
        }
        register(MeshExtrusionSpec.TYPE.id) { spec, context ->
            MeshExtrusionAttachment.create(spec as MeshExtrusionSpec, context)
        }
    }

    fun register(typeId: String, factory: RigAttachmentFactory): ExtensionHandle = point.register(typeId.rl, factory)

    internal fun canCreate(spec: Any): Boolean = factory(spec) != null

    fun create(spec: Any, context: RigAttachmentContext): Attachment? = factory(spec)?.create(spec, context)

    private fun factory(spec: Any): RigAttachmentFactory? =
        RigAttachmentTypes.of(spec)?.let { point.find(it.key) }
}

private fun RigItemSlot.vanilla(): EquipmentSlot = when (this) {
    RigItemSlot.MAINHAND -> EquipmentSlot.MAINHAND
    RigItemSlot.OFFHAND -> EquipmentSlot.OFFHAND
    RigItemSlot.HEAD -> EquipmentSlot.HEAD
    RigItemSlot.CHEST -> EquipmentSlot.CHEST
    RigItemSlot.LEGS -> EquipmentSlot.LEGS
    RigItemSlot.FEET -> EquipmentSlot.FEET
}
