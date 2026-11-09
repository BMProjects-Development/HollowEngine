package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFieldForm
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPinType
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphNodeMetrics
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphPin
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor

/** A pin of a row. [kotlin] is the exact type it carries, when [type] alone does not say. */
data class StoryPinSpec(val name: String, val type: StoryPinType, val kotlin: String? = null)

/**
 * The value a row edits in place.
 *
 * @param lines how many lines of text it shows; more than one makes it a multi-line field.
 * @param choices the values it takes, when it is one of a known set.
 * @param picks the values a list it holds can be put together from.
 * @param completions what it is usually given, to complete as it is typed.
 * @param typeName whether it holds the name of a Kotlin type.
 * @param color whether it holds a color, which a button next to it picks.
 * @param form how it shows the Kotlin it holds: a string, a number, a flag, a vector or Kotlin as it is.
 * @param assets the ends of the paths of the files it can be picked from.
 * @param problem what is wrong with what it holds.
 * @param registry the registry it names an entry of, which a button beside it picks from.
 * @param slot whether it holds a slot of the inventory, which a picture of the inventory picks.
 * @param write gets what is typed, as the field shows it.
 */
class StoryRowField(
    val key: String,
    val value: String,
    val placeholder: String = "",
    val lines: Int = 1,
    val choices: List<String> = emptyList(),
    val picks: List<String> = emptyList(),
    val completions: UiCompletionContributor? = null,
    val typeName: Boolean = false,
    val color: Boolean = false,
    val code: Boolean = false,
    val form: StoryFieldForm = if (code) StoryFieldForm.Code else StoryFieldForm.Literal,
    val assets: List<String> = emptyList(),
    val problem: String? = null,
    val registry: StoryRegistry? = null,
    val slot: Boolean = false,
    val write: (String) -> Unit,
) {
    val vector: String? get() = (form as? StoryFieldForm.Vector)?.helper
}

/**
 * A row of a node: a label, the field of what it edits, and the pins on its edges.
 *
 * @param hint what the mark beside the label says.
 * @param optional whether what it holds can be left out.
 * @param addLabel what the button of [onAdd] says.
 * @param nameField the field of the name of what the row holds, which takes the place of [label].
 * @param section the heading that folds the rows under it, when the row is one.
 * @param hidden whether a folded section hides the row: its pins then sit on the heading, and nothing of it is drawn.
 * @param depth how many sections the row is inside.
 */
class StoryNodeRow(
    val top: Float,
    val height: Float,
    val label: String? = null,
    val input: StoryPinSpec? = null,
    val output: StoryPinSpec? = null,
    val field: StoryRowField? = null,
    val onRemove: (() -> Unit)? = null,
    val onAdd: (() -> Unit)? = null,
    val hint: String? = null,
    val optional: Boolean = false,
    val addLabel: String? = null,
    val nameField: StoryRowField? = null,
    val section: StoryRowSection? = null,
    val hidden: Boolean = false,
    val depth: Int = 0,
)

class StoryRowSection(val open: Boolean, val toggle: () -> Unit)
class StoryRowBand(val top: Float, val bottom: Float, val depth: Int)

/** Where everything of one node sits, in graph units from the corner of the node. */
class StoryNodeBox(
    val node: StoryNode,
    val title: String,
    val icon: String?,
    val tag: String,
    val color: UiColor?,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rows: List<StoryNodeRow>,
    val collapsed: Boolean,
    val bands: List<StoryRowBand> = emptyList(),
) {
    val rect: GraphRect get() = GraphRect(x, y, width, height)

    val pins: List<GraphPin>
        get() {
            if (node is StoryNode.Reroute) return rerouteMiddle(node)
            return rows.flatMap(::pinsOf)
        }

    val shownPins: List<GraphPin>
        get() = if (node is StoryNode.Reroute) pins else rows.filterNot { it.hidden }.flatMap(::pinsOf)

    private fun pinsOf(row: StoryNodeRow): List<GraphPin> {
        val at = if (collapsed) y + GraphNodeMetrics.COLLAPSED / 2f else y + row.top + FLOW_ROW / 2f
        return listOfNotNull(
            row.input?.let { GraphPin(node.id, it.name, false, x, at) },
            row.output?.let { GraphPin(node.id, it.name, true, x + width, at) },
        )
    }

    private fun rerouteMiddle(reroute: StoryNode.Reroute): List<GraphPin> {
        val atX = x + width / 2f
        val atY = y + height / 2f
        return listOf(
            GraphPin(node.id, reroute.input, false, atX, atY), GraphPin(node.id, reroute.output, true, atX, atY)
        )
    }

    private fun spec(pin: GraphPin) =
        rows.firstNotNullOfOrNull { row -> (if (pin.output) row.output else row.input)?.takeIf { it.name == pin.name } }

    fun type(pin: GraphPin): StoryPinType? = spec(pin)?.type

    fun kotlin(pin: GraphPin): String? = spec(pin)?.kotlin

    companion object {
        const val FLOW_ROW = GraphNodeMetrics.FIELD_ROW
    }
}

internal fun StoryPinType.tag(): String = "gn-type-$id"
