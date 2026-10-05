package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.VfxEditing
import ru.hollowhorizon.hollowengine.client.vfx.played
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.vfx.VfxBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect

/**
 * The effect hung as [attachmentId] on [bone], edited through rig it hangs in.
 */
class AttachedVfxEditing(
    private val parent: RigEditing,
    private val bone: String?,
    private val attachmentId: String,
) : VfxEditing {
    private fun ModelRig.spec(): VfxBoneAttachmentSpec? =
        holder(bone).attachment(attachmentId) as? VfxBoneAttachmentSpec

    private val spec: VfxBoneAttachmentSpec? get() = parent.occupied.spec()

    override val effect: VfxEffect get() = spec?.played() ?: VfxEffect.EMPTY

    /** Whether the attachment plays its own copy of a file, which [reset] can drop. */
    val canReset: Boolean get() = spec?.let { it.ownEffect != null && it.effect.isNotBlank() } == true

    override fun edit(mergeKey: String?, history: Boolean, change: (VfxEffect) -> VfxEffect) =
        rewrite(mergeKey) { current -> current.copy(ownEffect = change(current.played() ?: VfxEffect.EMPTY)) }

    /** Drops the copy, so the attachment plays the file again. */
    fun reset() = rewrite(null) { it.copy(ownEffect = null) }

    private fun rewrite(mergeKey: String?, change: (VfxBoneAttachmentSpec) -> VfxBoneAttachmentSpec) {
        val inherited = parent.occupied.spec()
        parent.edit(mergeKey) { outer ->
            val current = outer.spec() ?: inherited ?: return@edit outer
            val next = change(current)
            if (next == current) outer else outer.withHolder(bone, outer.holder(bone).withAttachment(next))
        }
    }

    override fun beginGesture() = parent.beginGesture()

    override fun endGesture() = parent.endGesture()

    override fun undo(): Boolean = parent.undo()

    override fun redo(): Boolean = parent.redo()
}
