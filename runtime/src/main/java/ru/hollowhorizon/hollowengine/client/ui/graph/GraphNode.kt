package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*

/**
 * A press, drag or release on a node: how far the pointer has gone since the press, in graph units,
 * and where it is on the canvas and on the screen, for handlers that follow it, such as a link being
 * drawn out and the menu it opens when it is dropped on nothing.
 */
data class GraphNodeGesture(
    val graphDeltaX: Float,
    val graphDeltaY: Float,
    val canvasX: Float,
    val canvasY: Float,
    val modifiers: Int,
    val screenX: Float = 0f,
    val screenY: Float = 0f,
) {
    val control: Boolean get() = modifiers and GLFW.GLFW_MOD_CONTROL != 0
    val shift: Boolean get() = modifiers and GLFW.GLFW_MOD_SHIFT != 0
    val alt: Boolean get() = modifiers and GLFW.GLFW_MOD_ALT != 0
}

/**
 * One node of a graph, placed at ([x], [y]) in graph units inside [GraphCanvas]. Its look comes from
 * the `graph-node` tag and whatever [tags] the owner adds; `selected` is added for [selected].
 */
@Composable
fun GraphNode(
    id: String,
    canvasId: String,
    view: GraphViewState,
    x: Float,
    y: Float,
    width: Float,
    height: Float? = null,
    selected: Boolean = false,
    tags: List<String> = emptyList(),
    onPress: (GraphNodeGesture) -> Unit = {},
    onDrag: (GraphNodeGesture) -> Unit = {},
    onRelease: () -> Unit = {},
    onContextMenu: (screenX: Float, screenY: Float) -> Unit = { _, _ -> },
    content: HollowUiContent,
) {
    fun gesture(event: UiEvent): GraphNodeGesture {
        val canvas = event.ancestorLocalPositions[canvasId]
        return GraphNodeGesture(
            graphDeltaX = event.dragTotalX / view.zoom,
            graphDeltaY = event.dragTotalY / view.zoom,
            canvasX = canvas?.x ?: 0f,
            canvasY = canvas?.y ?: 0f,
            modifiers = event.modifiers,
            screenX = event.x,
            screenY = event.y,
        )
    }

    val held = remember { booleanArrayOf(false) }
    Column(
        id = id,
        tags = listOf("graph-node") + tags + if (selected) listOf("selected") else emptyList(),
        modifier = Modifier
            .position(x.px, y.px)
            .size(width.px, height?.px ?: UiLength.Auto)
            .input(hoverable = true, clickable = true, draggable = true)
            .onPress { event ->
                when (event.button) {
                    GLFW.GLFW_MOUSE_BUTTON_RIGHT -> onContextMenu(event.x, event.y)
                    GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                        held[0] = true
                        onPress(gesture(event))
                    }

                    else -> return@onPress
                }
                event.consume()
            }
            .onDrag { event ->
                if (!held[0] || event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onDrag
                onDrag(gesture(event))
                event.consume()
            }
            .onRelease { event ->
                if (!held[0]) return@onRelease
                held[0] = false
                onRelease()
                event.consume()
            },
        content = content,
    )
}
