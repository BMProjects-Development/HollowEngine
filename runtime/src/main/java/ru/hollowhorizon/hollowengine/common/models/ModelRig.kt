package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelRig(
    val bones: Map<String, RigBone> = emptyMap(),
    /** Attachments in model space, independent of the imported node hierarchy. */
    val attachments: List<RigAttachmentSpec> = emptyList(),
) {
    val boneByAlias: Map<String, String> by lazy {
        buildMap {
            bones.forEach { (name, bone) ->
                bone.alias?.takeIf { it.isNotBlank() && it != name }?.let { put(it, name) }
            }
        }
    }

    fun bone(name: String): RigBone? = bones[name]

    fun withBone(name: String, bone: RigBone): ModelRig =
        copy(bones = if (bone == RigBone.EMPTY) bones - name else bones + (name to bone))

    /** A null bone selects the model itself. Reuses the same attachment editing operations. */
    fun attachmentTarget(bone: String?): RigBone =
        if (bone == null) RigBone(attachments = attachments) else bone(bone) ?: RigBone.EMPTY

    fun editAttachmentTarget(bone: String?, edit: (RigBone) -> RigBone): ModelRig {
        val updated = edit(attachmentTarget(bone))
        return if (bone == null) copy(attachments = updated.attachments) else withBone(bone, updated)
    }

    companion object {
        val EMPTY = ModelRig()
    }
}

@Serializable
data class RigBone(
    val alias: String? = null,
    val hidden: Boolean = false,

    val attachments: List<RigAttachmentSpec> = emptyList(),
) {
    fun attachment(id: String): RigAttachmentSpec? = attachments.firstOrNull { it.id == id }

    fun withAttachment(attachment: RigAttachmentSpec): RigBone = withAttachment(attachment.id, attachment)

    fun withAttachment(replacing: String, attachment: RigAttachmentSpec): RigBone {
        val index = attachments.indexOfFirst { it.id == replacing }
        if (index < 0) return copy(attachments = attachments + attachment)

        val rest = attachments.toMutableList().also { it[index] = attachment }
        return copy(attachments = rest.filterIndexed { at, other -> at == index || other.id != attachment.id })
    }

    fun withoutAttachment(id: String): RigBone = copy(attachments = attachments.filterNot { it.id == id })

    companion object {
        val EMPTY = RigBone()
    }
}

/**
 * Something attached to a model or bone: a damage hitbox, an item, a physics body, a collider.
 */
@Serializable
abstract class RigAttachmentSpec {
    abstract val id: String

    abstract fun withId(id: String): RigAttachmentSpec
}

/** Draws whatever the entity wears in [slot] at this bone. */
@Serializable
@SerialName("hollowengine:rig/item")
data class ItemSlotAttachmentSpec(
    override val id: String = "item",
    val slot: RigItemSlot = RigItemSlot.MAINHAND,
) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)
}

@Serializable
enum class RigItemSlot {
    MAINHAND,
    OFFHAND,
    HEAD,
    CHEST,
    LEGS,
    FEET,
}
