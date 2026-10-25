package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderGraph
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderGraphNode
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderOptionKind
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderOptionSpec
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeInsight
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark

/** What the fields of a node change, and the menus they open; the editor answers them. */
internal interface ShaderFieldActions {
    fun setValue(node: String, pin: String, values: List<Float>)
    fun setOption(node: String, option: String, value: String)
    fun openMenu(screenX: Float, screenY: Float, items: List<UiDropdownItem>)
    fun beginGesture()
    fun endGesture()
}

/** One choice of a node, edited in place: a menu of values, a box to tick, or text. */
@Composable
internal fun OptionField(
    graph: ShaderGraph,
    node: ShaderGraphNode,
    option: ShaderOptionSpec,
    actions: ShaderFieldActions,
) {
    val current = node.options[option.name] ?: option.default
    val id = "sg-option-${node.id}-${option.name}"
    when (option.kind) {
        ShaderOptionKind.TOGGLE -> Row(
            tags = listOf("sg-toggle"),
            modifier = Modifier.size(0.px, UiLength.Auto).grow(1f).alignItems(vertical = UiAlign.CENTER)
                .input(hoverable = true, clickable = true).onClick { event ->
                    if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) actions.setOption(
                        node.id,
                        option.name,
                        (current != "true").toString()
                    )
                    event.consume()
                },
        ) {
            Checkbox(
                checked = current == "true",
                id = "$id-box",
                tags = listOf("sg-checkbox"),
                onCheckedChange = { checked -> actions.setOption(node.id, option.name, checked.toString()) },
            )
            Text(optionTitle(option), tags = listOf("sg-pin-label"))
        }

        ShaderOptionKind.CHOICE -> {
            Text(
                optionTitle(option),
                tags = listOf("sg-pin-label"),
                modifier = Modifier.size(0.px, UiLength.Auto).grow(1f)
            )
            Chip(id, optionValueTitle(option, current)) { x, y ->
                actions.openMenu(x, y, choiceItems(option, current) { actions.setOption(node.id, option.name, it) })
            }
        }

        ShaderOptionKind.PROPERTY -> {
            val names = graph.properties.map { it.name }
            Chip(id, current.ifEmpty { graphText("pick_property") }) { x, y ->
                val items = if (names.isEmpty()) {
                    listOf(UiDropdownItem(graphText("no_properties"), enabled = false))
                } else {
                    names.map { name ->
                        UiDropdownItem(name, mark = UiDropdownMark.RADIO, checked = name == current) {
                            actions.setOption(node.id, option.name, name)
                        }
                    }
                }
                actions.openMenu(x, y, items)
            }
        }

        ShaderOptionKind.TEXT -> {
            Text(
                optionTitle(option),
                tags = listOf("sg-pin-label"),
                modifier = Modifier.size(0.px, UiLength.Auto).grow(1f)
            )
            TextField(
                value = current,
                id = id,
                fontSize = 9f,
                tags = listOf("sg-field"),
                modifier = Modifier.size(FieldsWidth.px, FieldHeight.px),
                onChange = { actions.setOption(node.id, option.name, it) },
            )
        }

        ShaderOptionKind.EXPRESSION -> TextField(
            value = current,
            id = id,
            fontSize = 9f,
            insight = UiCodeInsight(
                highlighter = ShaderExpressionEditing.highlighter,
                completions = ShaderExpressionEditing.completions,
                diagnostics = ShaderExpressionEditing.diagnostics(current),
            ),
            tags = listOf("sg-field", "sg-expression"),
            modifier = Modifier.size(0.px, ExpressionHeight.px).grow(1f),
            onChange = { actions.setOption(node.id, option.name, it) },
        )
    }
}

/** What a choice is set to, which opens its menu where it was pressed. */
@Composable
private fun Chip(id: String, text: String, onOpen: (Float, Float) -> Unit) {
    Text(
        text,
        id = id,
        tags = listOf("sg-chip"),
        modifier = Modifier.size(FieldsWidth.px, UiLength.Auto).input(hoverable = true, clickable = true)
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onOpen(event.x, event.y)
                event.consume()
            },
    )
}

/** How wide the fields of a row are together; the name takes what is left. */
internal const val FieldsWidth = 112f
internal const val FieldHeight = 20f
private const val ExpressionHeight = FieldHeight
