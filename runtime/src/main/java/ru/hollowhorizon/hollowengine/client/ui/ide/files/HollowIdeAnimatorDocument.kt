package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.nbt.NbtIo
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.history.SnapshotStep
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

/**
 * An open `.animator` file: one [Animator] the editor rewrites whole. A file that could not be read stays as it is
 * on the disk and cannot be edited.
 */
class HollowIdeAnimatorDocument(bytes: ByteArray) : HollowIdeFileDocument, UndoOwner {
    var animator by mutableStateOf(Animator())
        private set

    /** What went wrong reading the file, or null when it read fine. */
    var error by mutableStateOf<String?>(null)
        private set

    private var original: ByteArray = bytes

    override val readOnly: Boolean get() = error != null

    override var isModified by mutableStateOf(false)
        private set

    /** Bumped on every accepted edit, for anything that wants to react without diffing the animator. */
    var revision by mutableStateOf(0)
        private set

    /** What the editor keeps while the file stays open, such as where the view was, across tab switches. */
    private var editorState: Any? = null

    init {
        load(bytes)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> editorState(create: () -> T): T = (editorState as? T) ?: create().also { editorState = it }

    override val history = UndoHistory()

    /**
     * Replaces the animator with what [change] makes of it, as a step called [label] in the history; edits
     * sharing [mergeKey] in quick succession, such as the frames of a drag, undo together.
     */
    fun edit(mergeKey: String? = null, label: UndoLabel? = null, change: (Animator) -> Animator) {
        if (readOnly) return
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
        if (readOnly) return original
        val tag = NBTFormat.serialize(Animator.serializer(), animator)
        return ByteArrayOutputStream().also { tag.save(it) }.toByteArray()
    }

    override fun reload(bytes: ByteArray) {
        val before = animator
        val broken = readOnly
        load(bytes)
        if (!broken && !readOnly && animator == before) return
        history.clear()
        isModified = false
        revision++
    }

    override fun markSaved() {
        isModified = false
    }

    private fun load(bytes: ByteArray) {
        original = bytes
        try {
            animator = if (bytes.isEmpty()) Animator() else read(bytes)
            error = null
        } catch (e: Exception) {
            animator = Animator()
            error = e.message ?: e::class.simpleName
            HollowEngine.LOGGER.warn("Could not read animator: {}", error)
        }
    }

    private companion object {
        /** Reads with [NbtIo] itself, since `loadAsNBT` takes a broken file for an empty tag. */
        fun read(bytes: ByteArray): Animator {
            val tag = DataInputStream(ByteArrayInputStream(bytes)).use { NbtIo.read(it) }
            return NBTFormat.deserialize(Animator.serializer(), tag)
        }
    }
}
