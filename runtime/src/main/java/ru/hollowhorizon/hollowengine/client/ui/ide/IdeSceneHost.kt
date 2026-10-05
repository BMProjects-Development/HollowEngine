package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * A hierarchy, that scene window shows.
 */
class SceneTarget(
    val id: String,
    val items: List<UiTreeItem<Any?>>,
    /** `null` when the selection is dropped. */
    val onSelect: (String?) -> Unit,
    val onToggle: (String) -> Unit,
    /** A line under the tree, for a hierarchy that has nothing in it yet. */
    val hint: String? = null,
    /** What a right click offers on the item with this id, or on the empty space under the rows (null). */
    val menu: ((String?) -> List<UiDropdownItem>)? = null,
    /** Keys pressed while the tree has focus, after the window's own; true when one was handled. */
    val onKey: ((UiKeyInput) -> Boolean)? = null,
    /** Puts the dragged item under the one it was dropped on; null when the hierarchy cannot be rearranged. */
    val onMove: ((dragged: String, target: String) -> Boolean)? = null,
    val canMove: (dragged: String, target: String) -> Boolean = { _, _ -> true },
    val onIconClick: ((String) -> Unit)? = null,
    /** The rows matching a search, ancestors included; null when the hierarchy cannot be searched. */
    val search: ((String) -> List<UiTreeItem<Any?>>)? = null,
    /** Controls over the tree, like what part of the hierarchy it shows. */
    val toolbar: (@Composable () -> Unit)? = null,
    /** Where edits made through this hierarchy go back, for keys and history window. */
    val history: UndoHistory? = null,
)

/**
 * Whose hierarchy the scene window shows. Last publisher wins, like the inspector.
 */
object IdeScenes {
    var current: SceneTarget? by mutableStateOf(null)
        private set

    private var owner: String? = null

    fun publish(source: String, target: SceneTarget?) {
        if (target == null) {
            release(source)
            return
        }
        owner = source
        current = target
    }

    fun release(source: String) {
        if (owner != source) return
        owner = null
        current = null
    }
}

/**
 * Keeps [target] in the scene window for as long as this editor is composed.
 *
 * Unlike the inspector, the tree itself changes as the file is edited, so [key] normally carries the
 * revision of whatever is being shown.
 */
@Composable
fun PublishScene(source: String, key: Any?, target: () -> SceneTarget?) {
    LaunchedEffect(source, key) { IdeScenes.publish(source, target()) }
    DisposableEffect(source) { onDispose { IdeScenes.release(source) } }
}

/** The search of each hierarchy, shared by the window's header, which opens it, and its tree, which shows it. */
private object SceneFilters {
    private val filters = HashMap<String, UiTreeFilterState>()

    fun of(sceneId: String): UiTreeFilterState =
        filters.getOrPut(sceneId) { UiTreeFilterState("scene-filter-$sceneId") }
}

/** What the scene window keeps in its header for the hierarchy it shows: that hierarchy's controls and the search. */
@Composable
internal fun SceneHeaderActions() {
    val published = IdeScenes.current
    val sceneId: String
    val toolbar: (@Composable () -> Unit)?
    val searchable: Boolean
    when {
        published != null -> {
            sceneId = published.id
            toolbar = published.toolbar
            searchable = published.search != null
        }

        WorldObjectScene.isAvailable() -> {
            sceneId = WorldObjectScene.SCENE_ID
            toolbar = WorldObjectScene.toolbar
            searchable = true
        }

        else -> return
    }
    PanelActions {
        toolbar?.invoke()
        if (searchable) {
            val filter = SceneFilters.of(sceneId)
            PanelActionButton(
                id = "scene-search-button",
                icon = SceneSearchIcon,
                tooltip = "hollowengine.gui.ide.windows.scene_search".lang,
                active = filter.expanded,
            ) {
                if (filter.expanded) filter.close() else filter.open()
            }
        }
    }
}

/** An item of a scene tree being dragged: which hierarchy it came from, so another cannot take it. */
private data class SceneDrag(val scene: String, val item: String)

/**
 * The scene window: the hierarchy of whatever is open, looked at and worked with the way the project
 * tree is. A right click opens the menu of the item under the pointer, rows drag onto each other, and
 * the selection answers the usual keys.
 */
@Composable
internal fun SceneDock(onFilterOpened: (String) -> Unit) {
    val target = IdeScenes.current ?: WorldObjectScene.target()
    var menu by remember { mutableStateOf<SceneMenu?>(null) }

    Column(tags = listOf("ide-panel", "scene-panel"), modifier = Modifier.size(100.percent, 100.percent)) {
        if (target == null) {
            SceneEmptyState("hollowengine.gui.ide.windows.scene_empty".lang, addHint = false)
            return@Column
        }

        val openMenu = { item: String?, event: UiEvent ->
            val items = target.menu?.invoke(item).orEmpty()
            menu = if (items.isEmpty()) null else SceneMenu(event.x, event.y, items)
        }
        val move = target.onMove
        val search = target.search
        val filter = SceneFilters.of(target.id)
        val query = filter.query.trim()
        val items = if (search != null && query.isNotEmpty()) search(query) else target.items
        val empty = target.items.isEmpty()

        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
            UiTreeView(
                items = items,
                filterState = filter.takeIf { search != null },
                filterPlaceholder = "hollowengine.message.filter".lang,
                onFilterOpened = onFilterOpened,
                onToggle = { item -> target.onToggle(item.id) },
                onSelect = { item, event ->
                    if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                        if (!item.selected) target.onSelect(item.id)
                        openMenu(item.id, event)
                        return@UiTreeView
                    }
                    menu = null
                    target.onSelect(item.id.takeUnless { item.selected && event.isCtrlDown() })
                },
                onBackgroundClick = {
                    menu = null
                    target.onSelect(null)
                },
                onBackgroundContextMenu = { event -> openMenu(null, event) },
                onIconClick = target.onIconClick?.let { click -> { item -> click(item.id) } },
                fillRowWidth = true,
                dragItem = if (move == null) null else { item ->
                    UiDragItem(payload = SceneDrag(target.id, item.id), icon = item.icon, label = item.label)
                },
                onDrop = if (move == null) null else { item, dragged ->
                    val drag = dragged.payload as? SceneDrag
                    drag != null && move(drag.item, item.id)
                },
                canDrop = { item, dragged ->
                    val drag = dragged.payload as? SceneDrag
                    drag != null && drag.scene == target.id && drag.item != item.id && target.canMove(
                        drag.item, item.id
                    )
                },
                modifier = Modifier.size(100.percent, 100.percent).onKeyInput { input ->
                    if (input.key == GLFW.GLFW_KEY_ESCAPE && target.items.any { it.selected }) {
                        target.onSelect(null)
                        input.consume()
                        return@onKeyInput
                    }
                    if (target.onKey?.invoke(input) == true) input.consume()
                },
            )
            if (empty) SceneEmptyState(target.hint.orEmpty(), addHint = target.menu != null)
        }

        if (!empty) target.hint?.let { Text(it, tags = listOf("scene-hint")) }

        menu?.let { open ->
            ContextMenu(
                id = "scene-context-menu",
                anchorBounds = UiRect(open.x, open.y, 0f, 0f),
                alignment = UiPopupAlignment.Cursor,
                items = open.items,
                onExpandedChange = { if (!it) menu = null },
            )
        }
    }
}

private class SceneMenu(val x: Float, val y: Float, val items: List<UiDropdownItem>)

/** What the scene window says when there is nothing in it, framed like the inspector says it. */
@Composable
private fun SceneEmptyState(message: String, addHint: Boolean) {
    Column(tags = listOf("scene-empty"), modifier = Modifier.inputTransparent()) {
        Column(tags = listOf("scene-empty-state")) {
            Image(SceneEmptyIcon, tags = listOf("scene-empty-icon"))
            if (message.isNotEmpty()) Text(message, tags = listOf("scene-empty-text"))
            if (addHint) Text("hollowengine.gui.ide.windows.scene_add_hint".lang, tags = listOf("scene-empty-hint"))
        }
    }
}

private const val SceneEmptyIcon = "hollowengine:textures/gui/icons/layers.svg"
private const val SceneSearchIcon = "hollowengine:textures/gui/icons/search.svg"
