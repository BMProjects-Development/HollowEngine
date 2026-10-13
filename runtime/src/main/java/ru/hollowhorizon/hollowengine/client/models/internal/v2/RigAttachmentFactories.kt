package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.sounds.SoundBoneAttachment
import ru.hollowhorizon.hollowengine.common.sounds.SoundBoneAttachmentSpec
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
    /** The entity that carries the model, living or not; see [ModelAttachment.carrier]. */
    val carrier: () -> Entity? = { null },
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

/** An attachment that works only while a condition of its entity holds; see [ConditionalAttachmentSpec]. */
interface ConditionalAttachment {
    /** Asks the condition of [context]'s entity, once a frame after the animator. */
    fun evaluate(context: AnimatorEvaluationContext)
}

object RigAttachmentFactories {
    val point = ExtensionPoints.create<RigAttachmentFactory>("hollowengine:rig/attachment_factories".rl)

    init {
        register("hollowengine:rig/sound") { spec, context ->
            SoundBoneAttachment(spec as SoundBoneAttachmentSpec, context.node, context.owner)
        }
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
