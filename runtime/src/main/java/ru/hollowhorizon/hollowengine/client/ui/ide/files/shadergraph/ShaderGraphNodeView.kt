package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderGraph
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphEditorController
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphNodeFrame
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphNodeHeader
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphPinView

internal const val ShaderGraphCanvasId = "shader-graph-canvas"

/** What the fields of a node do, and its arrow. */
internal interface ShaderNodeActions : ShaderFieldActions {
    fun toggleCollapsed(node: String)
}

/**
 * One node on the canvas, laid out as [box] says: the title, a row per output, option and input, the
 * preview, and the pins on its edges.
 */
@Composable
internal fun ShaderNodeView(
    graph: ShaderGraph,
    box: ShaderNodeBox,
    model: ShaderGraphEditorModel,
    controller: GraphEditorController,
    problem: String?,
    previews: ShaderGraphPreviews,
    actions: ShaderNodeActions,
) {
    val node = box.node
    val kind = box.kind
    GraphNodeFrame(
        controller, node.id, box.rect,
        listOfNotNull(
            "gn-node",
            "gn-${kind.category.name.lowercase()}",
            "collapsed".takeIf { node.collapsed },
            "error".takeIf { problem != null },
        ),
    ) {
        GraphNodeHeader(node.id, kind.title(), kind.displayIcon(), box.width, node.collapsed, problem) {
            actions.toggleCollapsed(node.id)
        }
        box.rows.forEach { row -> NodeRow(graph, box, row, actions) }
        if (box.preview > 0f) {
            Box(
                tags = listOf("gn-node-preview"),
                modifier = Modifier.position(ShaderNodeLayout.INSET.px, box.previewTop.px)
                    .size(box.preview.px, box.previewHeight.px)
                    .drawBehind(key = node.id) {
                        drawTexture(bounds, { previews.texture(node.id) }, flipY = true, radius = PreviewRadius)
                    },
            )
        }
        if (!node.collapsed) box.pins.forEach { pin ->
            val connected = graph.links.any {
                if (pin.output) it.from == pin.node && it.output == pin.name else it.to == pin.node && it.input == pin.name
            }
            GraphPinView(controller, pin, node.x, node.y, connected, listOfNotNull(model.typeOf(pin)?.tag()))
        }
    }
}

@Composable
private fun NodeRow(
    graph: ShaderGraph,
    box: ShaderNodeBox,
    row: ShaderNodeRow,
    actions: ShaderNodeActions,
) {
    val node = box.node
    Row(
        tags = listOf("gn-node-row"),
        modifier = Modifier.position(0.px, row.top.px).size(box.width.px, row.height.px)
            .padding(ShaderNodeLayout.INSET.px, 0.px)
            .alignItems(vertical = UiAlign.CENTER),
    ) {
        when (row) {
            is ShaderNodeRow.Output -> {
                Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
                Text(row.name, tags = listOf("gn-pin-label", "output"))
            }

            is ShaderNodeRow.Input -> {
                Text(row.pin.name, tags = listOf("gn-pin-label"))
                val reads = row.pin.fallback?.takeIf { graph.linkInto(node.id, row.pin.name) == null }
                if (reads != null) Text(reads.name.lowercase(), tags = listOf("gn-pin-hint"))
            }

            is ShaderNodeRow.Value -> {
                val values =
                    node.values[row.pin.name]?.takeIf { it.isNotEmpty() } ?: row.pin.default.ifEmpty { listOf(0f) }
                ValueFields(node, row.pin, values, row.components, actions)
            }

            is ShaderNodeRow.Option -> box.kind.option(row.name)
                ?.let { option -> OptionField(graph, node, option, actions) }
        }
    }
}

/** The corners of a preview, the same as of the fields on a node; a frame of a post effect fills it to them. */
private const val PreviewRadius = 5f
