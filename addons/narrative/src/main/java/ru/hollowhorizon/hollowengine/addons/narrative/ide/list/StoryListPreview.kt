package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryScriptingAnalyzer
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
import ru.hollowhorizon.hollowengine.client.utils.lang

internal const val StoryListStylesheet = "hollowengine-narrative:ui/styles/story-list.hss"

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
private const val PreviousIcon = "hollowengine:textures/gui/icons/chevron_left.svg"
private const val NextIcon = "hollowengine:textures/gui/icons/chevron_right.svg"
private const val CloseIcon = "hollowengine:textures/gui/icons/cross.svg"

/** A menu that opens upward, for the buttons at the bottom of the list. */
private val AboveStart = UiPopupAlignment(anchorVertical = UiAlign.START, popupVertical = UiAlign.END)

internal fun storyLang(name: String) = "hollowengine.gui.ide.story.$name".lang

/** The `.story` file as a list of cards, beside its text. */
internal val StoryListFilePreview = HollowIdeFilePreview(
    id = "story-list",
    defaultMode = HollowIdeViewMode.TEXT,
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
 * The statements of a story as rows, the way a writer reads it: phrases, pauses, menus of options, conditions. The
 * key parts are typed right in a row; the selected one is also in the inspector. The list composes only the rows
 * in view, so a long story stays light.
 */
@Composable
internal fun StoryListPreview(context: HollowIdePreviewContext) {
    val state = context.state { StoryListState() }
    state.context = context
    val parsed = state.model.read(context.text)
    val model = parsed.getOrNull() ?: return HollowIdePreviewError(parsed.exceptionOrNull())
    val rows = model.rows(state::isFolded)
    state.selectIndex?.let { index ->
        state.selectIndex = null
        state.select(rows.getOrNull(index.coerceAtMost(rows.lastIndex))?.selection, scroll = true)
    }
    val selected =
        state.selected?.takeIf { selection -> rows.any { it.selection == selection } || model.outline.blockAt(selection.line) != null }
    val matches = state.search?.let(model::matches).orEmpty()
    val scope = StoryRowScope(state, model, rows, matches.mapTo(HashSet()) { it.line }, matches.getOrNull(state.match))

    PublishInspector(
        source = "story-list:${context.file.path}",
        key = selected?.let { it to StoryCardKind.of(model.outline.blockAt(it.line)!!) }) {
        selected?.let { storyCardInspector(state, it, StoryCardKind.of(model.outline.blockAt(it.line)!!)) }
    }
    Reveal(state, rows)

    Column(
        tags = listOf("ide-file-panel", "story-list-root"),
        modifier = Modifier.style(InspectorStylesheet).style(StoryListStylesheet).focusScope(),
    ) {
        state.search?.let { SearchBar(state, model, it, matches.size) }
        LazyColumn(
            id = StoryListKeys.ListField,
            tags = listOf("story-list-scroll"),
            state = state.list,
            modifier = Modifier.size(100.percent, 0.px).grow(1f).focus()
                // A press here is a press past the rows, which clears the selection.
                .input(clickable = true).onPress { state.select(null) }
                .onKeyInput { input -> if (onKey(state, model, rows, input)) input.consume() },
        ) {
            items(rows.size, key = { rows[it].key }) { index -> StoryRowView(scope, rows[index], index) }
        }
        AddBar(state, model)
    }
    state.menu?.let { StoryRowContextMenu(state, model, rows, it) }
}

/** Scrolls what [StoryListState.reveal] asks for into view and focuses [StoryListState.focus] once it is drawn. */
@Composable
private fun Reveal(state: StoryListState, rows: List<StoryRow>) {
    val requester = LocalUiFocusRequester.current
    LaunchedEffect(state.focus) {
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
private fun SearchBar(state: StoryListState, model: StoryListModel, query: String, found: Int) {
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
                    GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> state.findNext(model, if (input.shift) -1 else 1)
                    GLFW.GLFW_KEY_ESCAPE -> closeSearch(state)
                    else -> return@onKeyInput
                }
                input.consume()
            },
        )
        Text(
            if (found == 0) storyLang("search.none") else storyLang("search.count")
                .format((state.match + 1).coerceAtLeast(0), found),
            tags = listOf("story-muted"),
        )
        IconButton(
            PreviousIcon,
            storyLang("search.previous"),
            id = "story-search-previous",
            tags = listOf("inline", "story-action")
        ) {
            state.findNext(model, -1)
        }
        IconButton(
            NextIcon,
            storyLang("search.next"),
            id = "story-search-next",
            tags = listOf("inline", "story-action")
        ) {
            state.findNext(model, 1)
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
private fun AddBar(state: StoryListState, model: StoryListModel) {
    var commands by remember { mutableStateOf<UiRect?>(null) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    Row(tags = listOf("story-add-bar"), modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.CENTER)) {
        Button(storyLang("add.phrase"), id = "story-add-phrase", icon = AddIcon, tags = listOf("story-add", "phrase")) {
            state.add(model, state.newPhrase(model))
        }
        Button(storyLang("add.wait"), id = "story-add-wait", icon = AddIcon, tags = listOf("story-add", "wait")) {
            state.add(model, StoryNewStatements.wait())
        }
        Button(storyLang("add.choice"), id = "story-add-choice", icon = AddIcon, tags = listOf("story-add", "choice")) {
            state.add(model, StoryNewStatements.choice())
        }
        Button(
            storyLang("add.command"),
            id = "story-add-command",
            icon = AddIcon,
            tags = listOf("story-add", "command"),
            modifier = Modifier.onPlaced { anchor = it },
        ) { commands = anchor }
    }
    commands?.let { bounds ->
        ContextMenu(
            id = "story-add-commands",
            anchorBounds = bounds,
            items = commandItems { name -> state.add(model, StoryNewStatements.command(name)) },
            alignment = AboveStart,
            onExpandedChange = { if (!it) commands = null },
        )
    }
}

/** A menu item per function the stories know, for adding a command. */
internal fun commandItems(add: (String) -> Unit): List<UiDropdownItem> =
    StoryScriptingAnalyzer.catalog().names.sorted().map { name -> UiDropdownItem("@$name") { add(name) } }

private fun onKey(state: StoryListState, model: StoryListModel, rows: List<StoryRow>, input: UiKeyInput): Boolean {
    if (StoryListKeys.isFind(input)) {
        state.search = state.search.orEmpty()
        state.focus = StoryListKeys.SearchField
        return true
    }
    val selected = state.selected
    when (input.key) {
        GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
            val up = input.key == GLFW.GLFW_KEY_UP
            if (input.alt) state.move(model, up) else state.selectNext(rows, if (up) -1 else 1)
        }

        GLFW.GLFW_KEY_TAB -> state.indent(model, out = input.shift)
        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
            if (selected == null) state.append(model, state.newPhrase(model))
            else state.insertAfter(model, selected, state.newPhrase(model))
        }

        GLFW.GLFW_KEY_DELETE -> selected?.let { state.remove(model, rows, it) }
        GLFW.GLFW_KEY_D -> if (input.command && selected != null) state.duplicate(model, selected) else return false
        GLFW.GLFW_KEY_ESCAPE -> state.select(null)
        else -> return false
    }
    return true
}
