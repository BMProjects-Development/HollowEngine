package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.*
import com.mojang.blaze3d.systems.RenderSystem
import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeShaderGraphDocument
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxGraphMaterials
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L

/**
 * Editor of material file: nodes with pins on a canvas, links drawn from pin to pin, and
 * what each node computes drawn right on it.
 */
@Composable
internal fun ShaderGraphEditor(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeShaderGraphDocument
    val state = remember(document) { document.editorState { ShaderGraphEditorState() } }
    val controller = remember(document) { GraphEditorController(ShaderGraphCanvasId, state.editor) }
    val previews = remember(document) { ShaderGraphPreviews() }
    DisposableEffect(previews) { onDispose { RenderSystem.recordRenderCall(previews::release) } }

    var previewError by remember(document) { mutableStateOf<ShaderDiagnostic?>(null) }

    LaunchedEffect(document.revision) {
        file.updateDirty(document.isModified)
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified) file.save()
    }
    LaunchedEffect(document, document.isModified) {
        if (document.isModified) return@LaunchedEffect
        val location = VfxGraphMaterials.locationOf(file.path)
        RenderSystem.recordRenderCall { VfxGraphMaterials.changed(location) }
    }
    LaunchedEffect(previews) {
        while (true) {
            withFrameNanos { }
            val error = previews.error
            if (error != previewError) previewError = error
        }
    }

    val graph = document.graph
    val kinds = ShaderNodeTypes.revision
    val code = remember(document.revision, kinds) { ShaderGraphCompiler.compile(graph) }
    val diagnostics = code.diagnostics + listOfNotNull(previewError)
    val problems = diagnostics.filter { it.node != null }.groupBy { it.node!! }
    previews.show(graph, document.revision to kinds)

    // A group taken apart, from the inspector or by undo, leaves its nodes selected on their own.
    val selection = state.editor.selection.let { current ->
        if (current.group != null && graph.group(current.group) == null) current.copy(group = null) else current
    }
    SideEffect {
        state.diagnostics = diagnostics
        if (state.editor.selection != selection) state.editor.selection = selection
    }
    PublishInspector(source = "shadergraph-${file.path}", key = selection) {
        shaderGraphInspectorTarget(document, selection, state)
    }

    val all = LinkedHashMap<String, ShaderNodeBox>()
    graph.nodes.forEach { node ->
        ShaderNodeTypes.of(node.type)?.let { all[node.id] = ShaderNodeLayout.of(graph, node, it, code.types) }
    }
    val scene = ShaderGraphScene.of(graph, all)
    val model = ShaderGraphEditorModel(document, code, scene) { controller.select(it) }
    controller.model = model

    val actions = object : ShaderNodeActions {
        override fun toggleCollapsed(node: String) {
            val collapsed = document.graph.node(node)?.collapsed ?: return
            document.edit { it.withCollapsed(node, !collapsed) }
        }

        override fun setValue(node: String, pin: String, values: List<Float>) =
            document.edit { it.withValue(node, pin, values) }

        override fun setOption(node: String, option: String, value: String) =
            document.edit { it.withOption(node, option, value) }

        override fun openMenu(screenX: Float, screenY: Float, items: List<UiDropdownItem>) {
            controller.menu = GraphMenu(screenX, screenY, items)
        }

        override fun beginGesture() = document.beginGesture()
        override fun endGesture() = document.endGesture()
    }

    Box(
        mode = UiBoxMode.STACK,
        modifier = Modifier.size(100.percent, 100.percent).style(GraphNodesStylesheet).focusScope()
            .drawBehind(key = previews) { drawGl { previews.render() } },
    ) {
        GraphEditorCanvas(controller, Modifier.size(100.percent, 100.percent)) {
            ShaderGraphSceneView(graph, model, controller, code.types, problems, previews, actions)
        }
    }
}

internal fun graphText(name: String): String = "hollowengine.gui.shadergraph.$name".lang
