package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.HollowEngine
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

    /** Effects to go back to; the timeline part of them is not restored, it has its own history. */
    private val history = DocumentHistory<VfxEffect>()

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /**
     * Told about every edit the author makes, once it is in; edits made without [edit]'s history, such
     * as the timeline writing its tracks back, are not reported. Auto-keying listens here.
     */
    var onEdit: ((before: VfxEffect, after: VfxEffect) -> Unit)? = null

    override fun edit(mergeKey: String?, history: Boolean, change: (VfxEffect) -> VfxEffect) {
        if (readOnly) return

        val previous = effect
        val next = change(previous)
        if (next == previous) return

        if (history) this.history.beforeEdit(previous, mergeKey)
        apply(next)
        if (history) onEdit?.invoke(previous, next)
    }

    override fun beginGesture() = history.beginGesture(effect)

    override fun endGesture() {
        history.endGesture(effect)
    }

    override fun undo(): Boolean = history.undo(effect)?.also { apply(it.copy(timeline = effect.timeline)) } != null

    override fun redo(): Boolean = history.redo(effect)?.also { apply(it.copy(timeline = effect.timeline)) } != null

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
