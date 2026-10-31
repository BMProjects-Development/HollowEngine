package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.graph.*

/**
 * Everything on the canvas of a graph, back to front: the title bars of open groups, the nodes, the
 * selected ones last so they lie on top, and the collapsed groups.
 */
@Composable
internal fun ShaderGraphSceneView(
    graph: ShaderGraph,
    model: ShaderGraphEditorModel,
    controller: GraphEditorController,
    types: ShaderGraphTypes,
    problems: Map<String, List<ShaderDiagnostic>>,
    previews: ShaderGraphPreviews,
    actions: ShaderNodeActions,
) {
    val scene = model.nodes
    GraphFrameHeaders(controller)
    scene.boxes.values.sortedBy { it.node.id in controller.selection }.forEach { box ->
        key(box.node.id) {
            if (box.isReroute) {
                GraphRerouteView(
                    controller, box.node.id, box.rect, box.pins.first { it.output },
                    listOfNotNull(types.output(box.node.id, ShaderNodeLibrary.REROUTE_OUTPUT)?.tag()),
                )
            } else {
                ShaderNodeView(
                    graph = graph,
                    box = box,
                    model = model,
                    controller = controller,
                    problem = problems[box.node.id]?.joinToString("\n") { problemText(it) },
                    previews = previews,
                    actions = actions,
                )
            }
        }
    }
    model.scene.cards.forEach { card ->
        key("card-${card.id}") {
            GraphGroupCard(
                controller, card,
                pinName = { it.name },
                ownerName = { pin -> graph.node(pin.node)?.let { ShaderNodeTypes.of(it.type) }?.title() ?: pin.node },
                pinTags = { pin -> listOfNotNull(model.typeOf(pin)?.tag()) },
            )
        }
    }
}
