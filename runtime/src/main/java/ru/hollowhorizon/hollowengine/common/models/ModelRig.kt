package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelRig(
    val bones: Map<String, RigBone> = emptyMap(),
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

    /**
     * What hangs on [bone], or on the model itself when [bone] is null, as a bone, so both are edited the
     * same way.
     */
    fun holder(bone: String?): RigBone = if (bone == null) RigBone(attachments = attachments) else bone(bone) ?: RigBone.EMPTY

    fun withHolder(bone: String?, holder: RigBone): ModelRig =
        if (bone == null) copy(attachments = holder.attachments) else withBone(bone, holder)

    /** Every attachment of the rig with the bone it hangs on, null for the model itself. */
    fun allAttachments(): List<Pair<String?, RigAttachmentSpec>> =
        attachments.map { null to it } + bones.flatMap { (name, bone) -> bone.attachments.map { name to it } }

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
 * Something hung on a bone or on the whole model: an item in a hand, a physics body, a collider.
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
