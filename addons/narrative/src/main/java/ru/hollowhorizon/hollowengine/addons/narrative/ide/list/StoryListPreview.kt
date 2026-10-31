package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFlow
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.addStart
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOption
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StorySelection
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryChecks
import ru.hollowhorizon.hollowengine.addons.narrative.ide.PublishStoryProblems
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryEditorStylesheet
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryUndo
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeFilePreview
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewError
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeViewMode
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.*

/** What the padding of `.story-list-scroll` leaves of the width of the list. */
private const val ListPadding = 20f

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
private const val PreviousIcon = "hollowengine:textures/gui/icons/chevron_left.svg"
private const val NextIcon = "hollowengine:textures/gui/icons/chevron_right.svg"
private const val CloseIcon = "hollowengine:textures/gui/icons/cross.svg"

/** A menu that opens upward, for the buttons at the bottom of the list. */
private val AboveStart = UiPopupAlignment(anchorVertical = UiAlign.START, popupVertical = UiAlign.END)

/** The `.story` file as a list of cards. */
internal val StoryListFilePreview = HollowIdeFilePreview(
    id = "story-list",
    defaultMode = HollowIdeViewMode.PREVIEW,
    icon = "hollowengine:textures/gui/icons/files/story.svg",
    title = "hollowengine.gui.ide.story.list",
    matcher = { it.endsWith(".story") },
) { StoryListPreview(it) }

internal object StoryListKeys {
    const val SearchField = "story-search"
    const val ListField = "story-list-scroll"

    fun isFind(input: UiKeyInput) = !input.repeat && HollowKeys.FIND.matches(input)
}

/**
 * A story as rows, the way a writer reads it: lines, pauses, menus of options, conditions. The list composes only the
 * rows in view, so a long story stays light.
 */
@Composable
internal fun StoryListPreview(context: HollowIdePreviewContext) {
    val state = context.state { StoryListState() }
    state.context = context
    val parsed = state.model.read(context.text)
    val graph = parsed.getOrNull() ?: return HollowIdePreviewError(parsed.exceptionOrNull())
    val flow = remember(graph) { StoryFlow(graph) }
    val rows = storyRows(flow, state::isFolded)
    val matches = state.search?.let { storyMatches(flow, it) }.orEmpty()
    val rowWidth = maxOf(state.listWidth - ListPadding, rows.maxOfOrNull(::rowFit) ?: 0f)
    val scope = StoryRowScope(state, graph, rows, matches.mapTo(HashSet()) { it.node }, matches.getOrNull(state.match), rowWidth)

    val checks = remember(graph) { StoryChecks.all(graph) }
    PublishStoryProblems(context, graph, checks) { state.reveal(flow, StorySelection(it)) }
    PublishInspector(source = "story-list:${context.file.path}", key = state.selected) {
        storyInspector(state, state.selected)
    }
    Reveal(state, rows)

    Column(
        tags = listOf("ide-file-panel", "story-list-root"),
        modifier = Modifier.style(InspectorStylesheet).style(StoryEditorStylesheet).focusScope(),
    ) {
        state.search?.let { SearchBar(state, flow, it, matches.size) }
        LazyColumn(
            id = StoryListKeys.ListField,
            tags = listOf("story-list-scroll"),
            state = state.list,
            crossAxisScroll = true,
            modifier = Modifier.size(100.percent, 0.px).grow(1f).focus().onPlaced { state.listWidth = it.width }
                // A press here is a press past the rows, which clears the selection.
                .input(clickable = true).onPress { state.select(null) }
                .onKeyInput { input -> if (onKey(state, graph, rows, input)) input.consume() },
        ) {
            items(rows.size, key = { rows[it].key }) { index -> StoryRowView(scope, rows[index], index) }
        }
        AddBar(state, graph)
    }
    state.menu?.let { StoryRowContextMenu(state, graph, rows, it) }
}

/** Scrolls what [StoryListState.reveal] asks for into view and focuses [StoryListState.focus] once it is drawn. */
@Composable
private fun Reveal(state: StoryListState, rows: List<StoryRow>) {
    val requester = LocalUiFocusRequester.current
    LaunchedEffect(state.focus) {
        withFrameNanos { }
        state.focus?.let(requester::request)
        state.focus = null
    }
    LaunchedEffect(state.settling) {
        if (!state.settling) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        state.settling = false
    }
    LaunchedEffect(state.reveal) {
        val selection = state.reveal ?: return@LaunchedEffect
        state.reveal = null
        withFrameNanos { }
        val index = rows.indexOfFirst { it.selection == selection }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val scroll = state.list.scroll
        val size = state.list.averageItemSize
        val top = index * size
        val viewport = scroll.viewport.height
        if (viewport <= 0f || top >= scroll.offsetY && top + size <= scroll.offsetY + viewport) return@LaunchedEffect
        scroll.scrollTo(y = (top - (viewport - size) / 2f).coerceAtLeast(0f))
    }
}

@Composable
private fun SearchBar(state: StoryListState, flow: StoryFlow, query: String, found: Int) {
    Row(tags = listOf("story-search"), modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.CENTER)) {
        Image(SearchIcon, tags = listOf("story-search-icon"))
        TextField(
            value = query,
            onChange = {
                state.search = it
                state.match = -1
            },
            placeholder = storyLang("search.placeholder"),
            id = StoryListKeys.SearchField,
            tags = listOf("story-field", "search"),
            modifier = Modifier.size(0.px, 20.px).grow(1f).onKeyInput { input ->
                when (input.key) {
                    GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> state.findNext(flow, if (input.shift) -1 else 1)
                    GLFW.GLFW_KEY_ESCAPE -> closeSearch(state)
                    else -> return@onKeyInput
                }
                input.consume()
            },
        )
        Text(
            if (found == 0) storyLang("search.none") else storyLang("search.count").format(
                    (state.match + 1).coerceAtLeast(
                        0
                    ), found
                ),
            tags = listOf("story-muted"),
        )
        IconButton(
            PreviousIcon,
            storyLang("search.previous"),
            id = "story-search-previous",
            tags = listOf("inline", "story-action")
        ) {
            state.findNext(flow, -1)
        }
        IconButton(
            NextIcon,
            storyLang("search.next"),
            id = "story-search-next",
            tags = listOf("inline", "story-action")
        ) {
            state.findNext(flow, 1)
        }
        IconButton(
            CloseIcon,
            storyLang("search.close"),
            id = "story-search-close",
            tags = listOf("inline", "story-action")
        ) {
            closeSearch(state)
        }
    }
}

private fun closeSearch(state: StoryListState) {
    state.search = null
    state.match = 0
    state.focus = StoryListKeys.ListField
}

/** The bar under the list, which adds after what is selected, or at the end when nothing is. */
@Composable
private fun AddBar(state: StoryListState, graph: StoryGraph) {
    var commands by remember { mutableStateOf<UiRect?>(null) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    Row(tags = listOf("story-add-bar"), modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.CENTER)) {
        Button(storyLang("add.phrase"), id = "story-add-phrase", icon = AddIcon, tags = listOf("story-add", "phrase")) {
            state.addPhrase(graph)
        }
        Button(storyLang("add.choice"), id = "story-add-choice", icon = AddIcon, tags = listOf("story-add", "choice")) {
            state.add(StoryNode.Choose(StoryGraph.newId(), listOf(StoryOption(storyLang("new.option")))))
        }
        Button(
            storyLang("add.condition"),
            id = "story-add-condition",
            icon = AddIcon,
            tags = listOf("story-add", "condition")
        ) {
            state.add(StoryNode.Branch(StoryGraph.newId()))
        }
        Button(
            storyLang("add.command"),
            id = "story-add-command",
            icon = AddIcon,
            tags = listOf("story-add", "command"),
            modifier = Modifier.onPlaced { anchor = it },
        ) { commands = anchor }
        Button(storyLang("add.start"), id = "story-add-start", icon = AddIcon, tags = listOf("story-add", "command")) {
            state.change(StoryUndo.ADD) { addStart(storyLang("new.start")).first }
        }
    }
    commands?.let { bounds ->
        ContextMenu(
            id = "story-add-commands",
            anchorBounds = bounds,
            items = StoryCatalog.items(state::add),
            alignment = AboveStart,
            onExpandedChange = { if (!it) commands = null },
        )
    }
}

private fun onKey(state: StoryListState, graph: StoryGraph, rows: List<StoryRow>, input: UiKeyInput): Boolean {
    if (StoryListKeys.isFind(input)) {
        state.search = state.search.orEmpty()
        state.focus = StoryListKeys.SearchField
        return true
    }
    val at = rows.indexOfFirst { it.selection == state.selected }
    val row = rows.getOrNull(at)
    when (input.key) {
        GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
            val up = input.key == GLFW.GLFW_KEY_UP
            when {
                input.alt -> row?.let { state.move(rows, it, up) }
                rows.isNotEmpty() -> {
                    val next = if (at < 0) 0 else (at + if (up) -1 else 1).coerceIn(0, rows.lastIndex)
                    state.select(rows[next].selection, scroll = true)
                }
            }
        }

        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> state.addPhrase(graph)
        GLFW.GLFW_KEY_DELETE -> row?.let(state::remove)
        GLFW.GLFW_KEY_D -> if (input.command && row != null) state.duplicate(row) else return false
        GLFW.GLFW_KEY_ESCAPE -> state.select(null)
        else -> return false
    }
    return true
}

/** The menu of a row: adding after it and the same moves the keys make. */
@Composable
internal fun StoryRowContextMenu(state: StoryListState, graph: StoryGraph, rows: List<StoryRow>, menu: StoryRowMenu) {
    val row = menu.row
    val items = buildList {
        add(UiDropdownItem(storyLang("add.phrase")) { state.addPhrase(graph) })
        add(UiDropdownItem(storyLang("add.command"), children = StoryCatalog.items(state::add)))
        if (row.moves) {
            add(UiDropdownItem(storyLang("duplicate"), shortcut = "Ctrl+D", separatorBefore = true) {
                state.duplicate(
                    row
                )
            })
            add(UiDropdownItem(storyLang("menu.up"), shortcut = "Alt+↑") { state.move(rows, row, up = true) })
            add(UiDropdownItem(storyLang("menu.down"), shortcut = "Alt+↓") { state.move(rows, row, up = false) })
        }
        add(UiDropdownItem(storyLang("remove"), shortcut = "Delete", separatorBefore = true) { state.remove(row) })
    }
    ContextMenu(
        id = "story-row-menu",
        anchorBounds = UiRect(menu.x, menu.y, 0f, 0f),
        items = items,
        onExpandedChange = { if (!it) state.menu = null },
    )
}
