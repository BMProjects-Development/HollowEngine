package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import kotlin.math.ceil

/**
 * Item-registration DSL for [LazyColumn]/[LazyRow]. Unlike a plain container, the item content lambdas
 * registered here are only INVOKED (composed) for the currently visible window, so a list of thousands
 * of rows composes and measures only the handful on screen.
 */
interface LazyListScope {
    /** A single item. [key] preserves its state/identity across scroll (defaults to its position). */
    fun item(key: Any? = null, content: @Composable () -> Unit)

    /** [count] items addressed by index. */
    fun items(count: Int, key: ((index: Int) -> Any)? = null, itemContent: @Composable (index: Int) -> Unit)
}

/** Items backed by a list. */
fun <T> LazyListScope.items(
    items: List<T>,
    key: ((item: T) -> Any)? = null,
    itemContent: @Composable (item: T) -> Unit,
) = items(items.size, key?.let { k -> { index -> k(items[index]) } }) { index -> itemContent(items[index]) }

private class LazyInterval(
    val count: Int,
    val key: ((Int) -> Any)?,
    val content: @Composable (Int) -> Unit,
)

private class LazyListScopeImpl : LazyListScope {
    private val intervals = ArrayList<LazyInterval>()
    var totalCount = 0
        private set

    override fun item(key: Any?, content: @Composable () -> Unit) =
        items(1, key?.let { { _ -> it } }) { content() }

    override fun items(count: Int, key: ((Int) -> Any)?, itemContent: @Composable (Int) -> Unit) {
        if (count <= 0) return
        intervals += LazyInterval(count, key, itemContent)
        totalCount += count
    }

    private inline fun <R> locate(globalIndex: Int, block: (LazyInterval, localIndex: Int) -> R): R {
        var remaining = globalIndex
        for (interval in intervals) {
            if (remaining < interval.count) return block(interval, remaining)
            remaining -= interval.count
        }
        error("Lazy item index $globalIndex out of bounds ($totalCount)")
    }

    fun keyOf(globalIndex: Int): Any = locate(globalIndex) { interval, local -> interval.key?.invoke(local) ?: globalIndex }

    @Composable
    fun Item(globalIndex: Int) = locate(globalIndex) { interval, local -> interval.content(local) }
}

/**
 * Scroll + virtualization state for a lazy list. Holds the [scroll] handle (offset/viewport, observed to
 * recompute the window) and a running estimate of item size, refined from the measured window each frame.
 */
class LazyListState {
    val scroll = UiScrollHandle()

    private var measuredItem by mutableStateOf(0f)
    private var hasMeasured by mutableStateOf(false)

    /** Best current estimate of one item's main-axis size (gap excluded); drives spacers and the window. */
    val averageItemSize: Float
        get() = if (hasMeasured) measuredItem.coerceAtLeast(1f) else DefaultItemEstimate

    /** [extent] is the measured window (including its internal gaps); back out the per-item size. */
    internal fun recordWindow(extent: Float, count: Int, gap: Float) {
        if (count <= 0 || extent <= 0f) return
        val item = ((extent - (count - 1).coerceAtLeast(0) * gap) / count).coerceAtLeast(1f)
        if (!hasMeasured || measuredItem != item) {
            measuredItem = item
            hasMeasured = true
        }
    }

    companion object {
        /** Used until the first window is measured (and for empty viewports on the first frame). */
        const val DefaultItemEstimate = 28f
    }
}

@Composable
fun rememberLazyListState(): LazyListState = remember { LazyListState() }

@Composable
fun LazyColumn(
    modifier: Modifier? = null,
    state: LazyListState = rememberLazyListState(),
    gap: Float = 0f,
    overscan: Int = 4,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    crossAxisScroll: Boolean = false,
    content: LazyListScope.() -> Unit,
) = LazyList(horizontal = false, modifier, state, gap, overscan, id, tags, crossAxisScroll, content)

@Composable
fun LazyRow(
    modifier: Modifier? = null,
    state: LazyListState = rememberLazyListState(),
    gap: Float = 0f,
    overscan: Int = 4,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    crossAxisScroll: Boolean = false,
    content: LazyListScope.() -> Unit,
) = LazyList(horizontal = true, modifier, state, gap, overscan, id, tags, crossAxisScroll, content)

@Composable
private fun LazyList(
    horizontal: Boolean,
    modifier: Modifier?,
    state: LazyListState,
    gap: Float,
    overscan: Int,
    id: String?,
    tags: Iterable<String>,
    crossAxisScroll: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val scope = LazyListScopeImpl().apply(content)
    val count = scope.totalCount

    val slot = state.averageItemSize + gap
    val offset = if (horizontal) state.scroll.offsetX else state.scroll.offsetY
    val viewportSize = (if (horizontal) state.scroll.viewport.width else state.scroll.viewport.height)
        .let { if (it > 0f) it else DefaultViewport }

    val firstOnScreen = (offset / slot).toInt()
    val onScreenCount = ceil(viewportSize / slot).toInt() + 1
    val first = (firstOnScreen - overscan).coerceIn(0, maxOf(0, count))
    val last = (firstOnScreen + onScreenCount + overscan).coerceIn(0, count) // exclusive
    val leading = first * slot
    val trailing = (count - last).coerceAtLeast(0) * slot

    val listModifier = (modifier ?: Modifier).gap(0.px)
        .scrollable(
            vertical = !horizontal || crossAxisScroll,
            horizontal = horizontal || crossAxisScroll,
            state = state.scroll,
        )
    val body: HollowUiContent = {
        if (leading > 0f) Spacer(horizontal, leading)
        val across = when {
            crossAxisScroll -> Modifier
            horizontal -> Modifier.size(height = 100.percent)
            else -> Modifier.size(100.percent)
        }
        val windowModifier = across.gap(gap.px).onPlaced { rect ->
            state.recordWindow(if (horizontal) rect.width else rect.height, last - first, gap)
        }
        if (horizontal) {
            Row(modifier = windowModifier) { LazyWindow(scope, first, last) }
        } else {
            Column(modifier = windowModifier) { LazyWindow(scope, first, last) }
        }
        if (trailing > 0f) Spacer(horizontal, trailing)
    }
    if (horizontal) {
        Row(id = id, tags = tags, modifier = listModifier, content = body)
    } else {
        Column(id = id, tags = tags, modifier = listModifier, content = body)
    }
}

@Composable
private fun LazyWindow(scope: LazyListScopeImpl, first: Int, last: Int) {
    for (index in first until last) {
        key(scope.keyOf(index)) { scope.Item(index) }
    }
}

@Composable
private fun Spacer(horizontal: Boolean, size: Float) {
    val modifier = if (horizontal) Modifier.size(size.px, 0.px) else Modifier.size(0.px, size.px)
    Box(tags = listOf("lazy-spacer"), modifier = modifier)
}

private const val DefaultViewport = 512f
