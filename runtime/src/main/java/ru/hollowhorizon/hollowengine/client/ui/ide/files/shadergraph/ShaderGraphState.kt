package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphEditorState

/** What editor of one file keeps while file stays open: view and selection. */
internal class ShaderGraphEditorState {
    val editor = GraphEditorState()

    /** What is wrong with the graph as it last compiled, for the inspector to list. */
    var diagnostics by mutableStateOf(emptyList<ShaderDiagnostic>())
}
