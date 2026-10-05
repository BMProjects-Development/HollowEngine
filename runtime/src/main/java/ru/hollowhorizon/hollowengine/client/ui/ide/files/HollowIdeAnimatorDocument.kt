package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.history.SnapshotStep
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * An open `.animator` file: one [Animator] the editor rewrites whole.
 */
class HollowIdeAnimatorDocument(bytes: ByteArray) : HollowIdeFileDocument, UndoOwner {
    override val readOnly: Boolean = false

    var animator by mutableStateOf(decode(bytes))
        private set

    var isModified by mutableStateOf(false)
        private set

    /** Bumped on every accepted edit, for anything that wants to react without diffing the animator. */
    var revision by mutableStateOf(0)
        private set

    /** What the editor keeps while the file stays open, such as where the view was, across tab switches. */
    private var editorState: Any? = null

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> editorState(create: () -> T): T = (editorState as? T) ?: create().also { editorState = it }

    override val history = UndoHistory()

    /**
     * Replaces the animator with what [change] makes of it, as a step called [label] in the history; edits
     * sharing [mergeKey] in quick succession, such as the frames of a drag, undo together.
     */
    fun edit(mergeKey: String? = null, label: UndoLabel? = null, change: (Animator) -> Animator) {
        val next = change(animator)
        if (next == animator) return

        history.record(SnapshotStep(this, label ?: UndoLabel.EDIT, animator, { animator }, ::apply), mergeKey)
        apply(next)
    }

    private fun apply(next: Animator) {
        animator = next
        isModified = true
        revision++
    }

    override fun encode(): ByteArray {
        val tag = NBTFormat.serialize(Animator.serializer(), animator)
        return ByteArrayOutputStream().also { tag.save(it) }.toByteArray()
    }

    override fun reload(bytes: ByteArray) {
        if (decode(bytes) == animator) return
        history.clear()
        animator = decode(bytes)
        isModified = false
        revision++
    }

    override fun markSaved() {
        isModified = false
    }

    private companion object {
        fun decode(bytes: ByteArray): Animator {
            if (bytes.isEmpty()) return Animator()
            return try {
                val tag = ByteArrayInputStream(bytes).use { it.loadAsNBT() }
                NBTFormat.deserialize(Animator.serializer(), tag as? CompoundTag ?: return Animator())
            } catch (e: Exception) {
                HollowEngine.LOGGER.warn("Could not read animator: {}", e.message)
                Animator()
            }
        }
    }
}
