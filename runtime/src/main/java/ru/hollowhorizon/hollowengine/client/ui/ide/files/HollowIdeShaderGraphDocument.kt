package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.history.*
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderGraph
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderGraphFormat
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderNodeLibrary
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument

/**
 * An open `.material` file: a shader graph. An empty file reads as the default surface graph, so a file made from
 * the project tree opens as something that already draws.
 */
class HollowIdeShaderGraphDocument(bytes: ByteArray) : HollowIdeFileDocument, UndoOwner {
    var graph by mutableStateOf(ShaderGraph())
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

    override val history = UndoHistory()

    /** What the editor keeps while the file stays open, such as where the view was, across tab switches. */
    private var editorState: Any? = null

    init {
        load(bytes)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> editorState(create: () -> T): T = (editorState as? T) ?: create().also { editorState = it }

    /** Replaces the graph with what [change] makes of it, as a step called [label] in the history. */
    fun edit(label: UndoLabel? = null, change: (ShaderGraph) -> ShaderGraph) {
        if (readOnly) return
        val next = change(graph)
        if (next == graph) return
        history.record(SnapshotStep(this, label ?: UndoLabel.EDIT, graph, { graph }, ::apply))
        apply(next)
    }

    /** Starts a gesture, such as dragging a node, that should go back in one step. */
    fun beginGesture() = history.begin()

    fun endGesture() = history.commit()

    private fun apply(next: ShaderGraph) {
        graph = next
        isModified = true
        revision++
    }

    override fun encode(): ByteArray =
        if (readOnly) original.toByteArray() else ShaderGraphFormat.write(graph).toByteArray()

    override fun reload(bytes: ByteArray) {
        if (!readOnly && bytes.toString(Charsets.UTF_8) == ShaderGraphFormat.write(graph)) return
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
            graph = if (original.isBlank()) ShaderNodeLibrary.defaultSurface() else ShaderGraphFormat.read(original)
            error = null
        } catch (e: Exception) {
            graph = ShaderGraph()
            error = e.message ?: e::class.simpleName
            HollowEngine.LOGGER.warn("Could not read shader graph: {}", error)
        }
    }
}
