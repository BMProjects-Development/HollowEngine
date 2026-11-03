package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

/** What is being looked for among the nodes, and which of the nodes found is in view. */
class GraphSearch {
    var query by mutableStateOf("")
    var index by mutableStateOf(0)

    /** Grows each time the search is asked for, so that its field takes the keys again while it stays open. */
    var focusRequests by mutableStateOf(0)
}

/** The bar over the corner of the canvas that finds nodes by what they are called and what they hold. */
@Composable
internal fun GraphSearchBar(controller: GraphEditorController, search: GraphSearch) {
    val found = controller.found
    val focus = LocalUiFocusRequester.current
    val field = "${controller.canvasId}-search"

    fun close() {
        controller.closeSearch()
        focus.request(controller.canvasId)
    }

    Column(
        modifier = Modifier.size(100.percent, 100.percent).padding(8.px).inputTransparent().style(GraphStylesheet)
            .alignItems(horizontal = UiAlign.END, vertical = UiAlign.START),
    ) {
        Row(tags = listOf("graph-search"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            Image(SearchIcon, tags = listOf("graph-search-icon"))
            TextField(
                value = search.query,
                placeholder = graphEditorText("search"),
                onChange = controller::searchFor,
                id = field,
                tags = listOf("graph-search-input"),
                modifier = Modifier.size(170.px, UiLength.Auto).onKeyInput { input ->
                    val back = input.modifiers and GLFW.GLFW_MOD_SHIFT != 0
                    when {
                        input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER ->
                            controller.stepSearch(if (back) -1 else 1)

                        HollowKeys.NEXT_FOUND.matches(input) -> controller.stepSearch(1)
                        HollowKeys.PREVIOUS_FOUND.matches(input) -> controller.stepSearch(-1)
                        input.key == GLFW.GLFW_KEY_ESCAPE -> close()
                        else -> return@onKeyInput
                    }
                    input.consume()
                },
            )
            val count = when {
                search.query.isBlank() -> ""
                found.isEmpty() -> graphEditorText("search_none")
                else -> "${search.index.coerceIn(0, found.size - 1) + 1}/${found.size}"
            }
            Text(count, tags = listOfNotNull("graph-search-count", "missing".takeIf { search.query.isNotBlank() && found.isEmpty() }))
            SearchButton("${field}-previous", PreviousIcon, HollowKeys.PREVIOUS_FOUND.hint(graphEditorText("search_previous"))) {
                controller.stepSearch(-1)
            }
            SearchButton("${field}-next", NextIcon, HollowKeys.NEXT_FOUND.hint(graphEditorText("search_next"))) {
                controller.stepSearch(1)
            }
            SearchButton("${field}-close", CloseIcon, graphEditorText("search_close"), ::close)
        }
    }
    LaunchedEffect(search.focusRequests) { focus.request(field) }
}

@Composable
private fun SearchButton(id: String, icon: String, tooltip: String, onPress: () -> Unit) {
    Box(
        id = id,
        tags = listOf("graph-search-button"),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .tooltipOnHover(tooltip)
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onPress()
                event.consume()
            },
    ) {
        Image(icon, tags = listOf("graph-search-button-icon"))
    }
}

private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
private const val PreviousIcon = "hollowengine:textures/gui/icons/chevron_left.svg"
private const val NextIcon = "hollowengine:textures/gui/icons/chevron_right.svg"
private const val CloseIcon = "hollowengine:textures/gui/icons/cross.svg"
