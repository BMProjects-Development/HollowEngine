package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.models.ItemSlotAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.models.RigItemSlot
import ru.hollowhorizon.hollowengine.common.utils.rl

/** What one bone of the model this attachment is being built for is, and whose model it is. */
class RigAttachmentContext(
    val node: RuntimeNode,
    val entity: () -> LivingEntity?,
    val owner: ModelAttachment? = null,
)

/**
 * Turns one kind of rig attachment spec into the thing that hangs on the bone.
 */
fun interface RigAttachmentFactory {
    fun create(spec: RigAttachmentSpec, context: RigAttachmentContext): Attachment?
}

/**
 * An attachment that takes a changed spec while it keeps running, as long as [RigAttachmentSpec.structure]
 * stays the same; the model it hangs on is then not built again.
 */
interface RespecAttachment {
    val spec: RigAttachmentSpec

    fun respec(spec: RigAttachmentSpec)
}

object RigAttachmentFactories {
    val point = ExtensionPoints.create<RigAttachmentFactory>("hollowengine:rig/attachment_factories".rl)

    init {
        register("hollowengine:rig/item") { spec, context ->
            ItemNode(context.entity, (spec as ItemSlotAttachmentSpec).slot.vanilla(), context.node)
        }
    }

    fun register(typeId: String, factory: RigAttachmentFactory): ExtensionHandle =
        point.register(typeId.rl, factory)

    fun create(spec: RigAttachmentSpec, context: RigAttachmentContext): Attachment? {
        val type = RigAttachmentTypes.of(spec) ?: return null
        return point.find(type.key)?.create(spec, context)
    }
}

private fun RigItemSlot.vanilla(): EquipmentSlot = when (this) {
    RigItemSlot.MAINHAND -> EquipmentSlot.MAINHAND
    RigItemSlot.OFFHAND -> EquipmentSlot.OFFHAND
    RigItemSlot.HEAD -> EquipmentSlot.HEAD
    RigItemSlot.CHEST -> EquipmentSlot.CHEST
    RigItemSlot.LEGS -> EquipmentSlot.LEGS
    RigItemSlot.FEET -> EquipmentSlot.FEET
}
