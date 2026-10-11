package ru.hollowhorizon.hollowengine.client.ui.ide.files.montage

import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeMontageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.BONE_POSE_PART
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.RigEditing
import ru.hollowhorizon.hollowengine.common.models.*

/**
 * What a montage hangs on its preview model, edited as a rig, so the scene, the inspector and the gizmo of the
 * rig editor work on it unchanged.
 */
internal class MontageRigEditing(
    private val document: HollowIdeMontageDocument,
    private val modelRig: () -> ModelRig,
) : RigEditing {
    /**
     * Where the montage moves a bone at the playhead, as a rig pose, so the bone gizmo starts from it; null for a
     * bone the montage does not move.
     */
    var bonePose: (String) -> RigPose? = { null }

    /** Told when an edit moves a bone, as the bone gizmo does: the montage keys the bone there. */
    var onBonePose: ((String, RigPose) -> Unit)? = null

    /** Where the playhead is, which the fields are shown at. */
    var time: () -> Float = { 0f }

    /** Whether the timeline records keys: edits of what it can animate go in as keys at the playhead. */
    var recording: () -> Boolean = { false }

    /** Whether [field] of [id] on [bone] has keys, so it holds what the timeline says unless keys are recorded. */
    var animated: (bone: String?, id: String, field: String) -> Boolean = { _, _, _ -> false }

    override fun isLocked(bone: String?, part: String): Boolean {
        if (recording()) return false
        if (part == BONE_POSE_PART) return true
        return PLACEMENT.any { animated(bone, part, it) }
    }

    /** Told to key [field] of [id] on [bone] at the playhead, with what it showed before the edit. */
    var onFieldKey: ((bone: String?, id: String, field: String, value: FloatArray, shown: FloatArray) -> Unit)? = null

    override val rig: ModelRig get() = shown(authored())

    override val history get() = document.history

    override fun edit(mergeKey: String?, label: UndoLabel?, change: (ModelRig) -> ModelRig) {
        val base = modelRig()
        val authored = authored()
        val before = shown(authored)
        val after = change(before)
        if (recording()) after.bones.forEach { (name, bone) ->
            if (bone.pose != before.bone(name)?.pose) onBonePose?.invoke(name, bone.pose ?: RigPose.IDENTITY)
        }
        val resolved = resolve(authored, before, after)
        document.edit(mergeKey, label = label) { montage -> montage.withAttachments(base, resolved) }
    }

    private fun authored(): ModelRig = modelRig().overlay(document.montage.ownRig())

    /** [authored] as it shows at the playhead. */
    private fun shown(authored: ModelRig): ModelRig = posed(authored.withTracks(document.montage.tracks, time()))

    /**
     * [after] as it is authored. While keys are recorded, what an edit changed in a field the timeline can
     * animate is keyed and kept out; otherwise a field with keys takes no edit, as the timeline writes it. What
     * an edit left alone is what is authored, not what the tracks showed.
     */
    private fun resolve(authored: ModelRig, before: ModelRig, after: ModelRig): ModelRig =
        after.allAttachments().fold(after) { rig, (bone, spec) ->
            val shown = before.holder(bone).attachment(spec.id) ?: return@fold rig
            val own = authored.holder(bone).attachment(spec.id) ?: return@fold rig
            val changed = if (spec == shown) emptyList() else RigFields.changed(shown, spec)
            val animatable = RigFields.animatable(shown).mapTo(HashSet()) { it.name }
            val timed = changed.filter { it in animatable && (recording() || animated(bone, spec.id, it)) }
            if (recording()) timed.forEach { field ->
                val value = RigFields.read(spec, field) ?: return@forEach
                val was = RigFields.read(shown, field) ?: return@forEach
                onFieldKey?.invoke(bone, spec.id, field, value, was)
            }
            val result = RigFields.copy(spec, own, changed - timed.toSet()) ?: own
            rig.withHolder(bone, rig.holder(bone).withAttachment(result))
        }

    /** [rig] with the bones the montage moves posed where it has them now. */
    private fun posed(rig: ModelRig): ModelRig =
        document.montage.bones.map { it.bone }.distinct().fold(rig) { posed, bone ->
            val pose = bonePose(bone) ?: return@fold posed
            posed.withBone(bone, (posed.bone(bone) ?: RigBone.EMPTY).copy(pose = pose))
        }

    override fun beginGesture() = document.beginGesture()

    override fun endGesture() = document.endGesture()

    /** Whether the montage itself brings [id] on [bone], rather than the model's rig. */
    fun owns(bone: String?, id: String): Boolean =
        document.montage.attachments.any { it.bone == bone && it.spec.id == id }
}

/** What the montage hangs on the model, as a rig. */
internal fun Montage.ownRig(): ModelRig = ModelRig(
    bones = attachments.filter { it.bone != null }.groupBy { it.bone!! }
        .mapValues { (_, hung) -> RigBone(attachments = hung.map { it.spec }) },
    attachments = attachments.filter { it.bone == null }.map { it.spec },
)

private fun Montage.withAttachments(base: ModelRig, edited: ModelRig): Montage {
    val kept = edited.allAttachments().filter { (bone, spec) -> base.holder(bone).attachment(spec.id) != spec }
    val next = kept.map { (bone, spec) ->
        attachments.firstOrNull { it.bone == bone && it.spec.id == spec.id }?.copy(spec = spec) ?: MontageAttachment(
            bone = bone,
            spec = spec
        )
    }
    val ordered =
        attachments.mapNotNull { old -> next.firstOrNull { it.bone == old.bone && it.spec.id == old.spec.id } } + next.filter { added -> attachments.none { it.bone == added.bone && it.spec.id == added.spec.id } }
    val gone = attachments.filter { old -> ordered.none { it.bone == old.bone && it.spec.id == old.spec.id } }
        .filter { old -> base.holder(old.bone).attachment(old.spec.id) == null }.map { it.bone to it.spec.id }
    return copy(attachments = ordered, tracks = tracks.filterNot { (it.bone to it.attachment) in gone })
}

/** The fields that place an attachment, which the gizmo moves. */
internal val PLACEMENT = setOf("offset", "rotation", "scale")
