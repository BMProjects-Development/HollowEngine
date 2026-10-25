package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import java.io.File
import kotlin.math.abs

/**
 * Something being dragged across the interface. [payload] is whatever the source wants to hand over
 * - a file path, an entity, a timeline key - and the rest is only how it looks under the cursor, so
 * a new kind of drag needs no changes here.
 */
data class UiDragItem(
    val payload: Any,
    val icon: String? = null,
    val label: String? = null,
    val externalFiles: List<File> = emptyList(),
)

/**
 * One drag in flight, shared by whoever starts it and whoever might catch it.
 *
 * Drag events are delivered to the node the gesture started on, so a drop cannot be found by hit
 * testing the event: targets register their bounds here instead, and the drop goes to the topmost
 * registered target under the pointer. A rejecting target blocks drops into areas behind it.
 */
class UiDragAndDropState {
    var item: UiDragItem? by mutableStateOf(null)
        private set

    var pointerX: Float by mutableStateOf(0f)
        private set

    var pointerY: Float by mutableStateOf(0f)
        private set

    val isDragging: Boolean get() = item != null

    var hoveredTargetId: String? by mutableStateOf(null)
        private set
    var canDrop: Boolean by mutableStateOf(false)
        private set

    private val targets = mutableListOf<UiDropTarget>()
    private var activeTarget: UiDropTarget? = null

    fun begin(item: UiDragItem, x: Float, y: Float) {
        this.item = item
        move(x, y)
    }

    /** Follows the pointer and lets the target under it react, e.g. by moving a caret. */
    fun move(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        val dragged = item ?: return
        val target = targetAt(x, y)
        hoveredTargetId = target?.id
        canDrop = target?.accepts?.invoke(dragged) == true
        val acceptedTarget = target.takeIf { canDrop }
        if (activeTarget !== acceptedTarget) {
            val previous = activeTarget
            activeTarget = acceptedTarget
            previous?.onDragExit?.invoke()
        }
        if (item === dragged && canDrop && activeTarget === acceptedTarget) target?.onDragOver?.invoke(dragged, x, y)
    }

    /** Hands the item to the target under the pointer; the drag always ends. */
    fun drop(): Boolean {
        val dragged = item ?: return false
        val target = targetAt(pointerX, pointerY)?.takeIf { it.accepts(dragged) }
        cancel()
        return target?.onDrop?.invoke(dragged, pointerX, pointerY) == true
    }

    fun cancel() {
        val previous = activeTarget
        activeTarget = null
        item = null
        hoveredTargetId = null
        canDrop = false
        previous?.onDragExit?.invoke()
    }

    internal fun register(target: UiDropTarget): () -> Unit {
        targets += target
        return {
            targets -= target
            if (isDragging) move(pointerX, pointerY)
        }
    }

    private fun targetAt(x: Float, y: Float): UiDropTarget? = targets.lastOrNull { it.contains(x, y) }
}

/** A registered drop area. Bounds and the ancestor clip are updated by the layout pass. */
internal class UiDropTarget(
    val id: String? = null,
    var bounds: UiRect = UiRect.Zero,
    var clip: UiRect? = null,
    var accepts: (UiDragItem) -> Boolean = { true },
    var onDragOver: (UiDragItem, Float, Float) -> Unit = { _, _, _ -> },
    var onDragExit: () -> Unit = {},
    var onDrop: (UiDragItem, Float, Float) -> Boolean = { _, _, _ -> false },
) {
    fun contains(x: Float, y: Float): Boolean =
        bounds.width > 0f && bounds.height > 0f && bounds.contains(x, y) && clip?.contains(x, y) != false
}

internal data class UiDropTargetModifier(val target: UiDropTarget) : Modifier

val LocalDragAndDrop = staticCompositionLocalOf<UiDragAndDropState?> { null }

/**
 * Makes this node draggable: [item] is asked once per gesture and a null answer means this node has
 * nothing to offer, so an ordinary drag (a selection, a scroll) is left alone.
 */
fun Modifier.dragSource(state: UiDragAndDropState?, item: () -> UiDragItem?): Modifier {
    if (state == null) return this
    return input(draggable = true).onDrag { event ->
            if (!event.isLeftClick()) return@onDrag
            if (state.isDragging) {
                state.move(event.rootLocalX, event.rootLocalY)
                event.consume()
                return@onDrag
            }
            if (abs(event.dragTotalX) < DragThreshold && abs(event.dragTotalY) < DragThreshold) return@onDrag
            val dragged = item() ?: return@onDrag
            state.begin(dragged, event.rootLocalX, event.rootLocalY)
            event.consume()
        }.onRelease {
            if (state.isDragging) state.drop()
        }
}

private const val DragThreshold = 3f

/**
 * Makes this node a drop area for as long as it is composed. [onDragOver] runs while the pointer
 * travels across it, which is what lets an editor put its caret where the item would land.
 */
@Composable
fun Modifier.dropTarget(
    id: String? = null,
    accepts: (UiDragItem) -> Boolean = { true },
    onDragOver: (UiDragItem, Float, Float) -> Unit = { _, _, _ -> },
    onDrop: (UiDragItem, Float, Float) -> Boolean,
): Modifier = dropTarget(id, accepts, onDragOver, onDragExit = {}, onDrop = onDrop)

@Composable
fun Modifier.dropTarget(
    id: String? = null,
    accepts: (UiDragItem) -> Boolean = { true },
    onDragOver: (UiDragItem, Float, Float) -> Unit = { _, _, _ -> },
    onDragExit: () -> Unit,
    onDrop: (UiDragItem, Float, Float) -> Boolean,
): Modifier {
    val state = LocalDragAndDrop.current ?: return this
    val target = remember(id) { UiDropTarget(id) }
    SideEffect {
        target.accepts = accepts
        target.onDragOver = onDragOver
        target.onDragExit = onDragExit
        target.onDrop = onDrop
    }
    DisposableEffect(state, target) {
        val unregister = state.register(target)
        onDispose { unregister() }
    }
    return this then UiDropTargetModifier(target)
}

/** Draws the dragged item next to the cursor; place it once, above everything else. */
@Composable
fun UiDragGhost(state: UiDragAndDropState, layer: Int = 500) {
    val item = state.item ?: return
    if (item.icon == null && item.label == null) return
    Box(
        tags = listOf("drag-ghost"),
        modifier = Modifier.position((state.pointerX + DragGhostOffset).px, (state.pointerY + DragGhostOffset).px)
            .layer(layer),
    ) {
        Row(tags = listOf("drag-ghost-content"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            item.icon?.let { icon -> Image(icon, tags = listOf("drag-ghost-icon")) }
            item.label?.let { label -> Text(label, tags = listOf("drag-ghost-label")) }
        }
    }
}

private const val DragGhostOffset = 10f
