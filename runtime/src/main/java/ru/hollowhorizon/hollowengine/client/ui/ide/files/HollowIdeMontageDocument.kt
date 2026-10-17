package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.history.SnapshotStep
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import ru.hollowhorizon.hollowengine.common.models.Montage
import ru.hollowhorizon.hollowengine.common.models.MontageFormat

/**
 * An open `.montage` file: one [Montage] the editor rewrites whole.
 */
class HollowIdeMontageDocument(bytes: ByteArray) : HollowIdeFileDocument {
    var montage by mutableStateOf(Montage.EMPTY)
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
     * Edits of the montage and of its keys, in the order they were made. Restoring a montage keeps the keys as
     * they are: the timeline's own steps put them back.
     */
    val history = UndoHistory()

    fun edit(mergeKey: String? = null, recorded: Boolean = true, label: UndoLabel? = null, change: (Montage) -> Montage) {
        if (readOnly) return

        val previous = montage
        val next = change(previous)
        if (next == previous) return

        if (recorded) history.record(SnapshotStep(this, label ?: UndoLabel.EDIT, previous, { montage }, ::restore), mergeKey)
        apply(next)
    }

    fun beginGesture() = history.begin()

    fun endGesture() = history.commit()

    private fun restore(snapshot: Montage) = apply(snapshot.copy(tracks = montage.tracks))

    private fun apply(next: Montage) {
        montage = next
        isModified = true
        revision++
    }

    override fun encode(): ByteArray = if (readOnly) original.toByteArray() else MontageFormat.write(montage).toByteArray()

    override fun reload(bytes: ByteArray) {
        if (!readOnly && bytes.toString(Charsets.UTF_8) == MontageFormat.write(montage)) return
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
            montage = MontageFormat.read(original)
            error = null
        } catch (e: Exception) {
            montage = Montage.EMPTY
            error = e.message ?: e::class.simpleName
            HollowEngine.LOGGER.warn("Could not read montage: {}", error)
        }
    }
}
