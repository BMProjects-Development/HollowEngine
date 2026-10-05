package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.history.SnapshotStep
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.VfxEditing
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxFormat

/**
 * An open `.vfx` file: one [VfxEffect] the editor rewrites whole.
 */
class HollowIdeVfxDocument(bytes: ByteArray) : HollowIdeFileDocument, VfxEditing {
    override var effect by mutableStateOf(VfxEffect.EMPTY)
        private set

    /** What went wrong reading the file, or null when it read fine. */
    var error by mutableStateOf<String?>(null)
        private set

    private var original: String = ""

    override val readOnly: Boolean get() = error != null

    var isModified by mutableStateOf(false)
        private set

    var revision by mutableStateOf(0)
        private set

    private var editorState: Any? = null

    init {
        load(bytes)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> editorState(create: () -> T): T = (editorState as? T) ?: create().also { editorState = it }

    /**
     * Edits of the effect and of its timeline, in the order they were made. Restoring an effect keeps the
     * timeline as it is: the timeline's own steps put it back.
     */
    override val history = UndoHistory()

    /**
     * Told about every edit the author makes, once it is in; edits made without [edit]'s history, such
     * as the timeline writing its tracks back, are not reported. Auto-keying listens here.
     */
    var onEdit: ((before: VfxEffect, after: VfxEffect) -> Unit)? = null

    override fun edit(mergeKey: String?, recorded: Boolean, label: UndoLabel?, change: (VfxEffect) -> VfxEffect) {
        if (readOnly) return

        val previous = effect
        val next = change(previous)
        if (next == previous) return

        if (recorded) history.record(SnapshotStep(this, label ?: UndoLabel.EDIT, previous, { effect }, ::restore), mergeKey)
        apply(next)
        if (recorded) onEdit?.invoke(previous, next)
    }

    override fun beginGesture() = history.begin()

    override fun endGesture() = history.commit()

    private fun restore(snapshot: VfxEffect) = apply(snapshot.copy(timeline = effect.timeline))

    private fun apply(next: VfxEffect) {
        effect = next
        isModified = true
        revision++
    }

    override fun encode(): ByteArray = if (readOnly) original.toByteArray() else VfxFormat.write(effect).toByteArray()

    override fun reload(bytes: ByteArray) {
        if (!readOnly && bytes.toString(Charsets.UTF_8) == VfxFormat.write(effect)) return
        history.clear()
        load(bytes)
        isModified = false
        revision++
    }

    override fun markSaved() {
        isModified = false
    }

    private fun load(bytes: ByteArray) {
        original = bytes.toString(Charsets.UTF_8)
        try {
            effect = VfxFormat.read(original)
            error = null
        } catch (e: Exception) {
            effect = VfxEffect.EMPTY
            error = e.message ?: e::class.simpleName
            HollowEngine.LOGGER.warn("Could not read effect: {}", error)
        }
    }
}
