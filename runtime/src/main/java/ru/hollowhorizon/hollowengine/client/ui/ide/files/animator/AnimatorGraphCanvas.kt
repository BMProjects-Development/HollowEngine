package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.UiLength
import ru.hollowhorizon.hollowengine.client.ui.alignItems
import ru.hollowhorizon.hollowengine.client.ui.gap
import ru.hollowhorizon.hollowengine.client.ui.graph.ARROW_SIZE
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphCanvas
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphCurves
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphEdge
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphLinkPreview
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphMiniMapItem
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphNode
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphPreferences
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphRect
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphViewState
import ru.hollowhorizon.hollowengine.client.ui.graph.graphViewItems
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.onClick
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.position
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.models.ANY_STATE
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerLayerSpec
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerStateSpec
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerTransitionSpec
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.AnimatorStateTypes
import ru.hollowhorizon.hollowengine.common.models.BlendStateSpec
import ru.hollowhorizon.hollowengine.common.models.ClipStateSpec
import ru.hollowhorizon.hollowengine.common.models.GraphPoint
import ru.hollowhorizon.hollowengine.common.models.nodeAt
import ru.hollowhorizon.hollowengine.common.models.nodeLayout
import ru.hollowhorizon.hollowengine.common.models.withAnyStateAt
import ru.hollowhorizon.hollowengine.common.models.withEntryState
import ru.hollowhorizon.hollowengine.common.models.withNodeAt
import ru.hollowhorizon.hollowengine.common.models.withState
import ru.hollowhorizon.hollowengine.common.models.withStateAsAnyState
import ru.hollowhorizon.hollowengine.common.models.withTransition
import ru.hollowhorizon.hollowengine.common.models.withoutAnyState
import ru.hollowhorizon.hollowengine.common.models.withoutState
import ru.hollowhorizon.hollowengine.common.models.withoutTransitionAt

internal const val AnimatorCanvasId = "animator-canvas"
private const val NodeHeight = 46f
private const val NodeMinWidth = 96f
private const val NodeMaxWidth = 260f
private const val ParallelOffset = 14f
private const val PlayIcon = "hollowengine:textures/gui/icons/play.svg"
private const val StateIcon = "hollowengine:textures/gui/icons/state.svg"
private const val AnyStateIcon = "hollowengine:textures/gui/icons/any_state.svg"
private const val ResetIcon = "hollowengine:textures/gui/icons/reload.svg"
internal const val MaximizeIcon = "hollowengine:textures/gui/icons/maximize.svg"
internal const val MinimizeIcon = "hollowengine:textures/gui/icons/minimize.svg"

/** Where a right click landed: on a state, on a transition, or on the empty canvas. */
private sealed interface CanvasMenuTarget {
    data class State(val stateId: String) : CanvasMenuTarget
    data class Transition(val index: Int) : CanvasMenuTarget
    data object Empty : CanvasMenuTarget
}

private data class CanvasMenu(val target: CanvasMenuTarget, val screenX: Float, val screenY: Float, val at: GraphPoint)

/** A link being drawn out of a state with Ctrl held, toward the pointer. */
private data class PendingLink(val from: String, val canvasX: Float, val canvasY: Float)

/**
 * The state machine of a controller layer, as a graph: states are nodes, transitions are arrows, and a
 * Ctrl-drag from one state to another adds a transition.
 */
@Composable
internal fun AnimatorGraphCanvas(
    document: HollowIdeAnimatorDocument,
    layerId: String,
    controller: AnimationControllerLayerSpec,
    selection: AnimatorSelection,
    view: GraphViewState,
    onSelect: (AnimatorSelection) -> Unit,
    modifier: Modifier,
) {
    val animator = document.animator
    var canvas by remember { mutableStateOf(UiRect.Zero) }
    var menu by remember(layerId) { mutableStateOf<CanvasMenu?>(null) }
    var link by remember(layerId) { mutableStateOf<PendingLink?>(null) }
    val dragOrigin = remember(layerId) { floatArrayOf(0f, 0f) }

    val positions = animator.nodeLayout(layerId) + anyStatePosition(animator, layerId, controller)
    val boxes = positions.mapValues { (stateId, point) ->
        GraphRect(point.x, point.y, nodeWidth(stateId, controller.state(stateId).subtitle()), NodeHeight)
    }
    val onCanvas = boxes.mapValues { (_, box) -> box.toCanvas(view) }
    val transitionCurves = controller.transitionCurves(onCanvas, view.zoom)

    /** Ends a link being drawn: a transition to the state it was dropped on, or nothing. */
    fun finishLink() {
        val drawn = link ?: return
        link = null
        val target = onCanvas.entries.firstOrNull { it.value.contains(drawn.canvasX, drawn.canvasY) }?.key
        if (target != null && target != drawn.from && target != ANY_STATE) {
            document.edit { it.withTransition(layerId, AnimationControllerTransitionSpec(from = drawn.from, to = target)) }
        }
    }

    val pending = link
    GraphCanvas(
        id = AnimatorCanvasId,
        view = view,
        modifier = modifier,
        edges = transitionCurves.map { (index, curve) ->
            GraphEdge(index, curve, arrow = true, selected = selection == AnimatorSelection.Transition(layerId, index))
        },
        link = pending?.let { from ->
            val box = onCanvas[from.from] ?: return@let null
            GraphLinkPreview(box.centerX, box.centerY, from.canvasX, from.canvasY)
        },
        minimap = boxes.map { (stateId, box) ->
            GraphMiniMapItem(
                rect = box,
                color = when {
                    selection == AnimatorSelection.State(layerId, stateId) -> AnimatorColors.NodeSelected
                    stateId == ANY_STATE -> AnimatorColors.AnyState
                    else -> AnimatorColors.Edge
                },
                tooltip = if (stateId == ANY_STATE) animatorText("any_state_node") else stateId,
            )
        },
        onBackgroundClick = { onSelect(AnimatorSelection.None) },
        onRelease = ::finishLink,
        onEdgeClick = { index -> onSelect(AnimatorSelection.Transition(layerId, index as Int)) },
        onContextMenu = { pointer, edge ->
            val target = (edge as? Int)?.let(CanvasMenuTarget::Transition) ?: CanvasMenuTarget.Empty
            menu = CanvasMenu(target, pointer.screenX, pointer.screenY, GraphPoint(pointer.graphX, pointer.graphY))
        },
        onKey = { input ->
            handleKey(document, layerId, controller, selection, onSelect, input.key, input.control) {
                GraphPoint(view.toGraphX(canvas.width / 2f), view.toGraphY(canvas.height / 2f))
            }
        },
        overlay = {
            Row(modifier = Modifier.position(6.px, 6.px).gap(4.px)) {
                AnimatorIconButton(ResetIcon, animatorText("reset_view"), size = 11f) { view.reset() }
            }
        },
    ) {
        transitionCurves.forEach { (index, curve) ->
            // The two links of a pair run side by side in opposite directions, so a label a third of
            // the way along each sits near its own start and the two never land on each other.
            val transition = controller.transitions[index]
            val paired = controller.transitions.any { it.from == transition.to && it.to == transition.from }
            val label = curve.pointAt(if (paired) 0.3f else 0.5f)
            TransitionLabel(
                transition = transition,
                selected = selection == AnimatorSelection.Transition(layerId, index),
                x = view.toGraphX(label[0]),
                y = view.toGraphY(label[1]),
                onSelect = { onSelect(AnimatorSelection.Transition(layerId, index)) },
            )
        }

        boxes.forEach { (stateId, box) ->
            val state = controller.state(stateId)
            val anyState = stateId == ANY_STATE
            GraphNode(
                id = "animator-state-$stateId",
                canvasId = AnimatorCanvasId,
                view = view,
                x = box.x,
                y = box.y,
                width = box.width,
                height = box.height,
                selected = selection == AnimatorSelection.State(layerId, stateId),
                tags = listOfNotNull(
                    "animator-state",
                    "any-state".takeIf { anyState },
                    "entry".takeIf { controller.entryState == stateId },
                ),
                onPress = { gesture ->
                    onSelect(AnimatorSelection.State(layerId, stateId))
                    if (gesture.control) {
                        link = PendingLink(stateId, onCanvas.getValue(stateId).centerX, onCanvas.getValue(stateId).centerY)
                    } else {
                        dragOrigin[0] = box.x
                        dragOrigin[1] = box.y
                    }
                },
                onDrag = { gesture ->
                    if (link != null) {
                        link = link?.copy(canvasX = gesture.canvasX, canvasY = gesture.canvasY)
                    } else {
                        document.edit(mergeKey = "move:$layerId/$stateId") {
                            it.withNodeAt(
                                layerId,
                                stateId,
                                GraphPoint(
                                    GraphPreferences.place(dragOrigin[0] + gesture.graphDeltaX),
                                    GraphPreferences.place(dragOrigin[1] + gesture.graphDeltaY),
                                ),
                            )
                        }
                    }
                },
                onRelease = ::finishLink,
                onContextMenu = { screenX, screenY ->
                    onSelect(AnimatorSelection.State(layerId, stateId))
                    menu = CanvasMenu(CanvasMenuTarget.State(stateId), screenX, screenY, GraphPoint(box.x, box.y))
                },
            ) {
                StateNodeContent(
                    stateId = stateId,
                    subtitle = state.subtitle(),
                    playMode = when (state) {
                        is ClipStateSpec -> state.playMode
                        is BlendStateSpec -> state.playMode
                        else -> null
                    },
                    isEntry = controller.entryState == stateId,
                )
            }
        }
    }

    menu?.let { open ->
        CanvasContextMenu(open, controller, layerId, document, view, onSelect) { menu = null }
    }
}

/** The name on top with the entry mark beside it, and under it what the state plays and how. */
@Composable
private fun StateNodeContent(stateId: String, subtitle: String?, playMode: AnimationPlayMode?, isEntry: Boolean) {
    val anyState = stateId == ANY_STATE
    Row(tags = listOf("animator-state-row"), modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.CENTER)) {
        Image(if (anyState) AnyStateIcon else StateIcon, tags = listOf("animator-state-icon"))
        Text(
            if (anyState) animatorText("any_state_node") else stateId,
            tags = listOf("graph-node-title"),
            modifier = Modifier.size(0.px, UiLength.Auto).grow(1f),
        )
        if (isEntry) Image(PlayIcon, tags = listOf("animator-entry-badge"))
    }
    if (anyState) return
    Row(tags = listOf("animator-state-row"), modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.CENTER)) {
        Text(subtitle.orEmpty(), tags = listOf("graph-node-subtitle"), modifier = Modifier.size(0.px, UiLength.Auto).grow(1f))
        playMode?.let { Text(it.name.lowercase(), tags = listOf("animator-play-mode")) }
    }
}

@Composable
private fun TransitionLabel(
    transition: AnimationControllerTransitionSpec,
    selected: Boolean,
    x: Float,
    y: Float,
    onSelect: () -> Unit,
) {
    val label = transition.condition.source.ifBlank { animatorText("always") }
    Text(
        if (label.length > 20) label.take(19) + "…" else label,
        tags = listOfNotNull("graph-link-label", "selected".takeIf { selected }),
        modifier = Modifier
            .position((x - 24f).px, (y - 7f).px)
            .input(hoverable = true, clickable = true)
            .onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onSelect()
                event.consume()
            },
    )
}

/** The keys the graph answers besides the canvas's own: delete, drop the selection, entry, a new state. */
private fun handleKey(
    document: HollowIdeAnimatorDocument,
    layerId: String,
    controller: AnimationControllerLayerSpec,
    selection: AnimatorSelection,
    onSelect: (AnimatorSelection) -> Unit,
    key: Int,
    control: Boolean,
    middle: () -> GraphPoint,
): Boolean {
    when {
        key == GLFW.GLFW_KEY_DELETE -> {
            when (selection) {
                is AnimatorSelection.State -> document.edit {
                    if (selection.stateId == ANY_STATE) it.withoutAnyState(layerId) else it.withoutState(layerId, selection.stateId)
                }

                is AnimatorSelection.Transition -> document.edit { it.withoutTransitionAt(layerId, selection.index) }
                else -> return false
            }
            onSelect(AnimatorSelection.Layer(layerId))
        }

        key == GLFW.GLFW_KEY_ESCAPE -> onSelect(AnimatorSelection.None)

        key == GLFW.GLFW_KEY_E -> {
            val target = selection as? AnimatorSelection.State ?: return false
            document.edit { it.withEntryState(layerId, target.stateId) }
        }

        key == GLFW.GLFW_KEY_INSERT || control && key == GLFW.GLFW_KEY_N -> {
            val id = freeStateId(controller)
            document.edit { it.withState(layerId, ClipStateSpec(id = id, animation = id), at = middle()) }
            onSelect(AnimatorSelection.State(layerId, id))
        }

        else -> return false
    }
    return true
}

@Composable
private fun CanvasContextMenu(
    menu: CanvasMenu,
    controller: AnimationControllerLayerSpec,
    layerId: String,
    document: HollowIdeAnimatorDocument,
    view: GraphViewState,
    onSelect: (AnimatorSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    ContextMenu(
        id = "animator-canvas-context",
        anchorBounds = UiRect(menu.screenX, menu.screenY, 0f, 0f),
        items = when (val target = menu.target) {
            is CanvasMenuTarget.State -> stateMenu(document, layerId, target.stateId, onSelect)
            is CanvasMenuTarget.Transition -> listOf(UiDropdownItem(animatorText("delete_transition")) {
                document.edit { it.withoutTransitionAt(layerId, target.index) }
                onSelect(AnimatorSelection.Layer(layerId))
            })

            CanvasMenuTarget.Empty -> addStateItems(document, layerId, controller, menu.at, onSelect) +
                    UiDropdownItem(animatorText("reset_view")) { view.reset() } + graphViewItems()
        },
        onExpandedChange = { if (!it) onDismiss() },
    )
}

/** What can be done to one state; the scene window offers the same. */
internal fun stateMenu(
    document: HollowIdeAnimatorDocument,
    layerId: String,
    stateId: String,
    onSelect: (AnimatorSelection) -> Unit,
): List<UiDropdownItem> {
    if (stateId == ANY_STATE) {
        return listOf(UiDropdownItem(animatorText("delete_any_state")) {
            document.edit { it.withoutAnyState(layerId) }
            onSelect(AnimatorSelection.Layer(layerId))
        })
    }
    return listOf(
        UiDropdownItem(animatorText("make_entry")) { document.edit { it.withEntryState(layerId, stateId) } },
        UiDropdownItem(animatorText("make_any_state")) {
            document.edit { it.withStateAsAnyState(layerId, stateId) }
            onSelect(AnimatorSelection.State(layerId, ANY_STATE))
        },
        UiDropdownItem(animatorText("delete_state")) {
            document.edit { it.withoutState(layerId, stateId) }
            onSelect(AnimatorSelection.Layer(layerId))
        },
    )
}

/** One item per kind of state that can be made, placed at [at], and the Any State node while there is none. */
internal fun addStateItems(
    document: HollowIdeAnimatorDocument,
    layerId: String,
    controller: AnimationControllerLayerSpec,
    at: GraphPoint,
    onSelect: (AnimatorSelection) -> Unit,
): List<UiDropdownItem> = buildList {
    val kinds = AnimatorStateTypes.all.filter { it.createDefault != null }
    kinds.forEach { type ->
        val create = type.createDefault ?: return@forEach
        val label = if (kinds.size == 1) animatorText("add_state") else "${animatorText("add_state")}: ${type.title()}"
        add(UiDropdownItem(label) {
            val id = freeStateId(controller)
            document.edit { it.withState(layerId, create(id), at = at) }
            onSelect(AnimatorSelection.State(layerId, id))
        })
    }
    if (ANY_STATE !in document.animator.nodeLayout(layerId).keys && controller.transitions.none { it.from == ANY_STATE }) {
        add(UiDropdownItem(animatorText("add_any_state")) {
            document.edit { it.withAnyStateAt(layerId, at) }
            onSelect(AnimatorSelection.State(layerId, ANY_STATE))
        })
    }
}

/** Every transition between two placed states, as a curve on the canvas, keyed by its index. */
private fun AnimationControllerLayerSpec.transitionCurves(boxes: Map<String, GraphRect>, zoom: Float) =
    transitions.mapIndexedNotNull { index, transition ->
        val from = boxes[transition.from] ?: return@mapIndexedNotNull null
        val to = boxes[transition.to] ?: return@mapIndexedNotNull null
        if (transition.from == transition.to) return@mapIndexedNotNull null

        val twoWay = transitions.any { it.from == transition.to && it.to == transition.from }
        val side = if (twoWay && transition.from > transition.to) -1f else 1f
        val offset = if (twoWay) ParallelOffset * zoom * side else 0f
        index to GraphCurves.betweenBoxes(from, to, offset, gap = ARROW_SIZE * zoom * 0.5f)
    }

private fun AnimationControllerLayerSpec.state(stateId: String): AnimationControllerStateSpec? =
    states.firstOrNull { it.id == stateId }

private fun AnimationControllerStateSpec?.subtitle(): String? = when (this) {
    null -> null
    is ClipStateSpec -> animation
    is BlendStateSpec -> motions.joinToString(" · ") { it.animation }.ifEmpty { kindName() }
    else -> kindName()
}

private fun nodeWidth(stateId: String, subtitle: String?): Float {
    val longest = maxOf(stateId.length, subtitle?.length ?: 0)
    return (18f + longest * 5.4f).coerceIn(NodeMinWidth, NodeMaxWidth)
}

private fun anyStatePosition(
    animator: Animator,
    layerId: String,
    controller: AnimationControllerLayerSpec,
): Map<String, GraphPoint> {
    val placed = animator.nodeAt(layerId, ANY_STATE)
    if (placed == null && controller.transitions.none { it.from == ANY_STATE }) return emptyMap()
    return mapOf(ANY_STATE to (placed ?: GraphPoint(-190f, 0f)))
}

internal fun freeStateId(controller: AnimationControllerLayerSpec): String {
    val taken = controller.states.map { it.id }.toSet()
    var index = taken.size + 1
    while ("state_$index" in taken) index++
    return "state_$index"
}
