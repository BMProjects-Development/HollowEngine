package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/**
 * A rig being edited, wherever it lives: a `.rig` file, what one entity hangs on its model, or what that
 * entity hangs on a model nested in it. The inspector, the scene and the gizmo edit all of them the same way.
 */
interface RigEditing : UndoOwner {
    val rig: ModelRig

    /** Every attachment that already hangs on the model, whose ids a new attachment must not take. */
    val occupied: ModelRig get() = rig

    /** Replaces the rig with what [change] makes of it, as a step called [label] in the history. */
    fun edit(mergeKey: String? = null, label: UndoLabel? = null, change: (ModelRig) -> ModelRig)

    /** Starts a gesture, such as dragging a handle, that should go back in one step. */
    fun beginGesture()

    fun endGesture()
}

/** The rig of the model hung as [attachmentId] on [bone] of [parent]'s model, edited through [parent]. */
class NestedRigEditing(
    private val parent: RigEditing,
    private val bone: String?,
    private val attachmentId: String,
) : RigEditing {
    private fun ModelRig.spec(): ModelAttachmentSpec? = holder(bone).attachment(attachmentId) as? ModelAttachmentSpec

    private val spec: ModelAttachmentSpec? get() = parent.rig.spec() ?: parent.occupied.spec()

    override val rig: ModelRig get() = spec?.rig ?: ModelRig.EMPTY

    override val occupied: ModelRig
        get() = spec?.let { RigAssets.of(ResourceLocation.tryParse(it.model)).overlay(it.rig) } ?: ModelRig.EMPTY

    override val history get() = parent.history

    override fun edit(mergeKey: String?, label: UndoLabel?, change: (ModelRig) -> ModelRig) {
        val inherited = parent.occupied.spec()
        parent.edit(mergeKey, label) { outer ->
            val current = outer.spec() ?: inherited ?: return@edit outer
            outer.withHolder(bone, outer.holder(bone).withAttachment(current.copy(rig = change(current.rig))))
        }
    }

    override fun beginGesture() = parent.beginGesture()

    override fun endGesture() = parent.endGesture()
}
