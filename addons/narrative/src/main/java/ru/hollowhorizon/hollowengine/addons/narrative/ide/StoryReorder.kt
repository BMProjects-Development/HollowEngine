package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.TransitionEasing
import ru.hollowhorizon.hollowengine.client.ui.style.UiTransition
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import kotlin.math.roundToInt

/** Visual order while a signature row is held. The graph changes only when the row is dropped. */
internal class StoryReorder {
    var from by mutableIntStateOf(-1)
        private set
    private var target by mutableIntStateOf(-1)
    private var delta by mutableFloatStateOf(0f)

    fun start(index: Int) {
        from = index; target = index; delta = 0f
    }

    fun move(distance: Float, height: Float, count: Int) {
        if (from !in 0 until count || height <= 0f) return
        delta = distance.coerceIn(-from * height, (count - 1 - from) * height)
        target = (from + (delta / height).roundToInt()).coerceIn(0, count - 1)
    }

    fun offset(index: Int, height: Float): Float = when {
        from < 0 -> 0f
        index == from -> delta
        from < target && index in (from + 1)..target -> -height
        target < from && index in target until from -> height
        else -> 0f
    }

    fun finish(reorder: (Int, Int) -> Unit) {
        val start = from
        val end = target
        from = -1
        if (start >= 0 && end >= 0 && start != end) reorder(start, end)
    }
}

/** The held row snaps to the pointer; only rows displaced by it animate. */
internal fun Modifier.reorderOffset(state: StoryReorder, index: Int, height: Float): Modifier {
    val dragging = state.from == index
    val translated = translate(y = state.offset(index, height)).layer(if (dragging) 10 else 0)
    return if (state.from >= 0 && !dragging) {
        translated.transition(UiTransition("translate", 120, TransitionEasing.EASE_OUT))
    } else translated.transition()
}

@Composable
internal fun StoryReorderHandle(
    id: String,
    index: Int,
    count: Int,
    rowHeight: Float,
    scale: Float,
    state: StoryReorder,
    enabled: Boolean,
    anchorId: String,
    reorder: (Int, Int) -> Unit,
) {
    val start = remember(id, anchorId) { floatArrayOf(Float.NaN) }
    val tooltip = if (state.from < 0) Modifier.tooltipOnHover(storyLang("function.reorder")) else Modifier
    Box(
        id = id, mode = UiBoxMode.STACK, tags = listOf("st-reorder-handle"),
        modifier = Modifier.cursor(UiCursorShape.MOVE).then(tooltip)
            .input(hoverable = enabled, clickable = enabled, draggable = enabled).onPress { event ->
                if (enabled && event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    start[0] = event.localYInAncestor(anchorId) ?: Float.NaN
                    state.start(index)
                    event.consume()
                }
            }.onDrag { event ->
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT || state.from != index) return@onDrag
                val localY = event.localYInAncestor(anchorId)
                val distance =
                    if (localY != null && start[0].isFinite()) localY - start[0] else event.dragTotalY / scale
                state.move(distance, rowHeight, count)
                event.consume()
            }.onRelease { event ->
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT || state.from != index) return@onRelease
                state.finish(reorder)
                event.consume()
            },
    ) {
        Image(
            "hollowengine-narrative:textures/gui/icons/reorder.svg",
            tags = listOf("st-reorder-icon"),
            modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER).inputTransparent(),
        )
    }
}
