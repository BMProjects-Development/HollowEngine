package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*

/** The colors a group can be, by the name a graph file keeps; the first one is the default. */
object GraphGroupColors {
    val all: Map<String, UiColor> = linkedMapOf(
        "gray" to UiColor(0.62f, 0.65f, 0.72f),
        "red" to UiColor(0.86f, 0.40f, 0.38f),
        "orange" to UiColor(0.88f, 0.58f, 0.30f),
        "yellow" to UiColor(0.88f, 0.78f, 0.36f),
        "green" to UiColor(0.40f, 0.74f, 0.46f),
        "teal" to UiColor(0.32f, 0.72f, 0.74f),
        "blue" to UiColor(0.43f, 0.61f, 0.86f),
        "purple" to UiColor(0.64f, 0.52f, 0.88f),
    )

    fun of(name: String): UiColor = all[name] ?: all.values.first()
}

/**
 * The title bar of a group's frame, over the top of [frame]: a press on it takes the group, a drag
 * moves the group, and its arrow collapses the group into one node. The frame itself is a
 * [GraphFrame] drawn by the canvas, which leaves the rest of it to the canvas and the nodes on it.
 */
@Composable
fun GraphGroupHeader(
    id: String,
    canvasId: String,
    view: GraphViewState,
    frame: GraphRect,
    title: String,
    color: UiColor,
    selected: Boolean,
    onPress: (GraphNodeGesture) -> Unit,
    onDrag: (GraphNodeGesture) -> Unit,
    onRelease: () -> Unit,
    onContextMenu: (screenX: Float, screenY: Float) -> Unit,
    onToggle: () -> Unit,
) {
    GraphNode(
        id = id,
        canvasId = canvasId,
        view = view,
        x = frame.x,
        y = frame.y,
        width = frame.width,
        height = GROUP_HEADER,
        selected = selected,
        tags = listOf("graph-group-head"),
        onPress = onPress,
        onDrag = onDrag,
        onRelease = onRelease,
        onContextMenu = onContextMenu,
    ) {
        Row(
            modifier = Modifier.size(100.percent, 100.percent).background(color.copy(alpha = color.alpha * HEAD_TINT))
                .alignItems(vertical = UiAlign.CENTER),
            tags = listOf("graph-group-bar"),
        ) {
            GraphGroupToggle("$id-toggle", collapsed = false, onToggle = onToggle)
            Text(title, tags = listOf("graph-group-title"), modifier = Modifier.size(0.px, UiLength.Auto).grow(1f))
        }
    }
}

/** The arrow that collapses a group, or opens it again from its collapsed node. */
@Composable
fun GraphGroupToggle(id: String, collapsed: Boolean, onToggle: () -> Unit) {
    Box(
        id = id,
        tags = if (collapsed) listOf("graph-group-toggle", "collapsed") else listOf("graph-group-toggle"),
        modifier = Modifier.input(hoverable = true, clickable = true).onPress { event ->
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onToggle()
            event.consume()
        },
    ) {
        Image(GroupArrowIcon, tags = listOf("graph-group-arrow"))
    }
}

/** How tall the title bar of a group is, in graph units, and how far its frame reaches around its nodes. */
const val GROUP_HEADER = 26f
const val GROUP_PADDING = 16f

private const val HEAD_TINT = 0.35f
private const val GroupArrowIcon = "hollowengine:textures/gui/icons/graph/arrow.svg"
