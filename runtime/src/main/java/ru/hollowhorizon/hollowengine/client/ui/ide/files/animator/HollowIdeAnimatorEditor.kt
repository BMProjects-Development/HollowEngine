package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphViewState
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.PublishScene
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.controller
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L

/**
 * The editor for a `.animator` file.
 *
 * The stack of layers lives in the scene window, with the states of each controller under it; the
 * editor itself is the graph of the layer being worked on. A clip layer has no states, so it has no
 * graph, and the inspector is all there is to it.
 */
@Composable
internal fun HollowIdeAnimatorEditor(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeAnimatorDocument
    val view = remember(document) { document.editorState { GraphViewState() } }
    var selection by remember(document) { mutableStateOf<AnimatorSelection>(AnimatorSelection.None) }
    var openLayer by remember(document) { mutableStateOf(document.animator.layers.firstOrNull()?.id) }
    val expanded = remember(document) { mutableStateListOf<String>().apply { addAll(document.animator.layers.map { it.id }) } }

    LaunchedEffect(document.revision) {
        file.updateDirty(document.isModified)
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified) file.save()
    }

    val layers = document.animator.layers
    val current = openLayer?.takeIf { id -> layers.any { it.id == id } } ?: layers.firstOrNull()?.id

    fun retarget(next: AnimatorSelection) {
        selection = next
        when (next) {
            is AnimatorSelection.Layer -> openLayer = next.layerId
            is AnimatorSelection.State -> openLayer = next.layerId
            is AnimatorSelection.Transition -> openLayer = next.layerId
            AnimatorSelection.None -> Unit
        }
    }

    val shown = if (selection != AnimatorSelection.None) selection
    else current?.let(AnimatorSelection::Layer) ?: AnimatorSelection.None
    PublishInspector(source = "animator-${file.path}", key = shown) {
        animatorInspectorTarget(document, shown, ::retarget)
    }
    PublishScene(source = "animator-${file.path}", key = SceneKey(document.revision, shown, expanded.toList())) {
        animatorSceneTarget(document, shown, expanded, { layerId -> if (!expanded.remove(layerId)) expanded += layerId }, ::retarget)
    }

    val controller = current?.let { document.animator.controller(it) }
    Column(
        modifier = Modifier.size(100.percent, 100.percent).style(AnimatorStylesheet).focusScope(),
    ) {
        if (current != null && controller != null) {
            AnimatorGraphCanvas(
                document = document,
                layerId = current,
                controller = controller,
                selection = selection,
                view = view,
                onSelect = ::retarget,
                modifier = Modifier.size(100.percent, 100.percent),
            )
        } else {
            Column(tags = listOf("animator-empty"), modifier = Modifier.padding(10.px).gap(6.px)) {
                Text(current ?: animatorText("no_layers"), tags = listOf("animator-empty-title"))
                Text(animatorText(if (current == null) "no_layers_hint" else "no_graph"), tags = listOf("animator-empty-hint"))
            }
        }
    }
}

/** What the scene tree is built from; a new one of these rebuilds it. */
private data class SceneKey(val revision: Int, val selection: AnimatorSelection, val expanded: List<String>)

internal fun freeLayerId(animator: Animator, prefix: String): String {
    val taken = animator.layers.map { it.id }.toSet()
    var index = 1
    while ("${prefix}_$index" in taken) index++
    return "${prefix}_$index"
}
