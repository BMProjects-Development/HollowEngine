@file:UseSerializers(RigComponentSerializer::class)

package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForQuatF

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ModelRigSerializer::class)
data class ModelRig(
    val bones: Map<String, RigBone> = emptyMap(),
    val attachments: List<Any> = emptyList(),
    /** What the named materials of the model look like here, over how the model authored them. */
    val materials: Map<String, MaterialSource> = emptyMap(),
    @EncodeDefault
    private val formatVersion: Int = 1,
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
    fun allAttachments(): List<Pair<String?, Any>> =
        attachments.map { null to it } + bones.flatMap { (name, bone) -> bone.attachments.map { name to it } }

    /**
     * This rig with [instance] laid over it: what one entity hangs on its model on top of what the model's
     * own rig file does. Bones match by name and attachments by id, and the instance's win.
     */
    fun overlay(instance: ModelRig): ModelRig {
        if (instance == EMPTY) return this
        val merged = LinkedHashMap(bones)
        instance.bones.forEach { (name, bone) -> merged[name] = bones[name]?.overlay(bone) ?: bone }
        return ModelRig(merged, attachments.overlay(instance.attachments), materials + instance.materials)
    }

    companion object {
        val EMPTY = ModelRig()
    }
}

/** These attachments with [instance]'s laid over them: one with a taken id takes its place, a new one goes last. */
private fun List<Any>.overlay(instance: List<Any>): List<Any> {
    if (instance.isEmpty()) return this
    val byId = instance.associateBy { it.rigId }
    return map { byId[it.rigId] ?: it } + instance.filter { added -> none { it.rigId == added.rigId } }
}

/**
 * How a bone is moved on top of whatever animates it: shifted in its parent's space, turned and scaled in its
 * own. A model that stands still is posed with this alone.
 */
@Serializable
data class RigPose(
    val position: Vec3f = Vec3f.ZERO,
    val rotation: @Serializable(ForQuatF::class) QuatF = QuatF.IDENTITY,
    val scale: Vec3f = Vec3f.ONES,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        val IDENTITY = RigPose()
    }
}

@Serializable
data class RigBone(
    val alias: String? = null,
    val hidden: Boolean = false,

    val attachments: List<Any> = emptyList(),
    val material: String? = null,
    val pose: RigPose? = null,
    val origin: RigBoneOrigin? = null,
) {
    fun attachment(id: String): Any? = attachments.firstOrNull { it.rigId == id }

    fun withAttachment(attachment: Any): RigBone = withAttachment(attachment.rigId, attachment)

    fun withAttachment(replacing: String, attachment: Any): RigBone {
        val index = attachments.indexOfFirst { it.rigId == replacing }
        if (index < 0) return copy(attachments = attachments + attachment)

        val rest = attachments.toMutableList().also { it[index] = attachment }
        return copy(attachments = rest.filterIndexed { at, other -> at == index || other.rigId != attachment.rigId })
    }

    fun withoutAttachment(id: String): RigBone = copy(attachments = attachments.filterNot { it.rigId == id })

    /** This bone with [instance] laid over it; a bone hidden by either stays hidden. */
    fun overlay(instance: RigBone): RigBone = RigBone(
        alias = instance.alias ?: alias,
        hidden = hidden || instance.hidden,
        attachments = attachments.overlay(instance.attachments),
        material = instance.material ?: material,
        pose = instance.pose ?: pose,
        origin = instance.origin ?: origin,
    )

    companion object {
        val EMPTY = RigBone()
    }
}

/**
 * How many models deep nesting goes. Rig files can hang a model on itself, or two models on each other, so
 * whatever follows nested models stops here: drawing, colliders, the scene window.
 */
const val MAX_NESTING = 8

/** Draws whatever the entity wears in [slot] at this bone. */
@Serializable
@SerialName("hollowengine:rig/item")
data class ItemSlotAttachmentSpec(
    val id: String = "item",
    val slot: RigItemSlot = RigItemSlot.MAINHAND,
)

/**
 * Another model hung on a bone, or on the model itself: a sword in a hand, a lamp on a post. It brings its
 * own rig file and can be dressed further with [rig], so models nest as deep as needed.
 */
@Serializable
@SerialName("hollowengine:rig/model")
data class ModelAttachmentSpec(
    @EditorHidden
    val id: String = "model",
    @EditorName("hollowengine.gui.rig_editor.kind_model")
    @EditorAsset("gltf", "glb", "geo.json", "fbx", "obj", "bbmodel")
    val model: String = "",
    @EditorHidden
    val rig: ModelRig = ModelRig.EMPTY,
)

@Serializable
enum class RigItemSlot {
    MAINHAND,
    OFFHAND,
    HEAD,
    CHEST,
    LEGS,
    FEET,
}
