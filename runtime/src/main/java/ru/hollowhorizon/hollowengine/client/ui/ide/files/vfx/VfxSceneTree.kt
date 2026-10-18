package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.history.UndoKeys
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.ide.SceneTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.SegmentLane
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.playbackText
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeTypes

/** The id of the row that stands for the effect itself, above its nodes. */
internal const val VfxRootId = "vfx-root"

/**
 * The node tree as the scene window shows it: the effect as the root, its nodes under it.
 */
internal fun vfxSceneTarget(document: HollowIdeVfxDocument, state: VfxEditorState, title: String) = SceneTarget(
    id = "vfx-nodes",
    history = document.history,
    items = buildList {
        val nodes = document.effect.nodes
        val timeline = document.effect.timeline
        add(laneItem(VfxEventsId, "${playbackText("events")}  ●${timeline.events.size}", EventsIcon, state))
        add(laneItem(VfxSectionsId, "${playbackText("sections")}  ●${timeline.sections.size}", SectionsIcon, state))
        add(
            UiTreeItem(
                id = VfxRootId,
                label = title,
                depth = 0,
                payload = null,
                icon = VfxIcons.EFFECT,
                hasChildren = nodes.isNotEmpty(),
                expanded = state.rootExpanded,
                selected = state.selected == null && state.selectedLane == null,
            )
        )
        if (state.rootExpanded) appendNodes(nodes, state.expanded, state.selected, depth = 1)
    },
    onSelect = { id ->
        when (id) {
            VfxEventsId -> state.selectLane(id, state.session.playback.eventLane)
            VfxSectionsId -> state.selectLane(id, state.session.playback.sectionLane)
            else -> state.select(id.takeUnless { it == VfxRootId })
        }
    },
    onToggle = { id ->
        when (id) {
            VfxRootId -> state.rootExpanded = !state.rootExpanded
            in state.expanded -> state.expanded.remove(id)
            else -> state.expanded.add(id)
        }
    },
    hint = vfxText("no_nodes").takeIf { document.effect.nodes.isEmpty() },
    menu = { id ->
        val playback = state.session.playback
        val time = state.session.timeline.currentTime
        when (id) {
            VfxEventsId -> listOf(UiDropdownItem(playbackText("add_event")) { playback.addEvent(time) })
            VfxSectionsId -> listOf(UiDropdownItem(playbackText("add_section")) { playback.addSection(time) })
            else -> vfxNodeMenu(document, state, id.takeUnless { it == VfxRootId })
        }
    },
    onKey = { input -> handleVfxNodeKey(document, state, input.key, input.modifiers, input.repeat) },
    onMove = { dragged, target -> move(document, state, dragged, target.takeUnless { it == VfxRootId }) },
    canMove = { dragged, target ->
        dragged !in LaneItems && target !in LaneItems && (target == VfxRootId || !document.effect.isWithin(target, dragged))
    },
)

/** The events and the sections of the timeline as objects of the scene, picked like nodes, which open their lanes. */
private const val VfxEventsId = "vfx-events"
private const val VfxSectionsId = "vfx-sections"
private val LaneItems = setOf(VfxEventsId, VfxSectionsId)
private const val EventsIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"
private const val SectionsIcon = "hollowengine:textures/gui/icons/loops.svg"

private fun laneItem(id: String, label: String, icon: String, state: VfxEditorState) = UiTreeItem<Any?>(
    id = id,
    label = label,
    depth = 0,
    payload = null,
    icon = icon,
    hasChildren = false,
    expanded = false,
    selected = state.selectedLane == id,
)

/** Picks the lane row [id] of the scene, which stands for [lane], in place of a node. */
private fun VfxEditorState.selectLane(id: String, lane: SegmentLane) {
    select(null)
    selectedLane = id
    session.selectLane(lane)
}

private fun MutableList<UiTreeItem<Any?>>.appendNodes(
    nodes: List<VfxNodeSpec>,
    expanded: List<String>,
    selected: String?,
    depth: Int,
) {
    nodes.forEach { node ->
        add(
            UiTreeItem(
                id = node.id,
                label = node.treeLabel(),
                depth = depth,
                payload = node,
                icon = VfxNodeTypes.of(node)?.icon,
                hasChildren = node.children.isNotEmpty(),
                expanded = node.id in expanded,
                selected = node.id == selected,
            )
        )
        if (node.children.isNotEmpty() && node.id in expanded) {
            appendNodes(node.children, expanded, selected, depth + 1)
        }
    }
}

/** How a node is named in a tree, with a mark when it is switched off. */
internal fun VfxNodeSpec.treeLabel(): String = if (enabled) name else "$name (${vfxText("off")})"

/** What a right click offers: adding under the node (or the effect), and what can be done to the node. */
internal fun vfxNodeMenu(document: VfxEditing, state: VfxNodeSelection, id: String?): List<UiDropdownItem> {
    val add = UiDropdownItem(
        vfxText(if (id == null) "add_node" else "add_child"),
        icon = VfxIcons.ADD,
        children = VfxNodeTypes.all.mapNotNull { type ->
            val create = type.createDefault ?: return@mapNotNull null
            UiDropdownItem(type.titleKey.lang, icon = type.icon) { add(document, state, id, create()) }
        },
    )
    val node = id?.let(document.effect::node) ?: return listOf(add)

    val siblings = document.effect.parentOf(node.id)?.children ?: document.effect.nodes
    val index = siblings.indexOfFirst { it.id == node.id }
    return listOf(
        add,
        UiDropdownItem(vfxText("duplicate"), icon = VfxIcons.DUPLICATE, shortcut = HollowKeys.DUPLICATE.shortcut, separatorBefore = true) {
            duplicate(document, state, node.id)
        },
        UiDropdownItem(vfxText("rename"), icon = VfxIcons.RENAME, shortcut = HollowKeys.RENAME.shortcut) { rename(state, node.id) },
        UiDropdownItem(vfxText(if (node.enabled) "disable" else "enable"), icon = VfxIcons.TOGGLE) {
            document.replace(node.withCommon(enabled = !node.enabled))
        },
        UiDropdownItem(vfxText("move_up"), shortcut = HollowKeys.MOVE_UP.shortcut, enabled = index > 0, separatorBefore = true) {
            document.edit { it.withShifted(node.id, -1) }
        },
        UiDropdownItem(vfxText("move_down"), shortcut = HollowKeys.MOVE_DOWN.shortcut, enabled = index < siblings.lastIndex) {
            document.edit { it.withShifted(node.id, 1) }
        },
        UiDropdownItem(vfxText("move_out"), enabled = document.effect.parentOf(node.id) != null) {
            val parent = document.effect.parentOf(node.id) ?: return@UiDropdownItem
            val grand = document.effect.parentOf(parent.id)
            val at = (grand?.children ?: document.effect.nodes).indexOfFirst { it.id == parent.id } + 1
            document.edit { it.withMoved(node.id, grand?.id, at) }
        },
        UiDropdownItem(vfxText("remove_node"), icon = VfxIcons.REMOVE, shortcut = HollowKeys.DELETE.shortcut, separatorBefore = true) {
            remove(document, state, node.id)
        },
    )
}

/** Undo and redo, and what the menu of the selected node offers by key. */
internal fun handleVfxNodeKey(document: VfxEditing, state: VfxNodeSelection, key: Int, modifiers: Int, repeat: Boolean): Boolean {
    if (!repeat && UndoKeys.handle(document.history, key, modifiers)) return true
    val id = state.selected ?: return false
    return when {
        HollowKeys.DELETE.matches(key, modifiers) -> remove(document, state, id).let { true }
        HollowKeys.RENAME.matches(key, modifiers) -> rename(state, id).let { true }
        HollowKeys.DUPLICATE.matches(key, modifiers) && !repeat -> duplicate(document, state, id).let { true }
        HollowKeys.MOVE_UP.matches(key, modifiers) -> document.edit { it.withShifted(id, -1) }.let { true }
        HollowKeys.MOVE_DOWN.matches(key, modifiers) -> document.edit { it.withShifted(id, 1) }.let { true }
        else -> false
    }
}

private fun add(document: VfxEditing, state: VfxNodeSelection, parent: String?, node: VfxNodeSpec) {
    document.edit { it.withChild(parent, node) }
    state.reveal(parent)
    state.select(node.id)
}

private fun duplicate(document: VfxEditing, state: VfxNodeSelection, id: String) {
    var created: String? = null
    document.edit { effect ->
        val (next, copy) = effect.withDuplicate(id) ?: return@edit effect
        created = copy
        next
    }
    created?.let(state::select)
}

private fun remove(document: VfxEditing, state: VfxNodeSelection, id: String) {
    val parent = document.effect.parentOf(id)?.id
    document.edit { it.withoutNode(id) }
    state.select(parent)
}

private fun move(document: HollowIdeVfxDocument, state: VfxEditorState, dragged: String, target: String?): Boolean {
    if (target != null && document.effect.isWithin(target, dragged)) return false
    if (document.effect.parentOf(dragged)?.id == target && target != null) return false
    document.edit { it.withMoved(dragged, target) }
    state.reveal(target)
    state.select(dragged)
    return true
}

/** Selects the node and puts the caret into its name in the inspector. */
private fun rename(state: VfxNodeSelection, id: String) {
    state.select(id)
    Minecraft.getInstance().execute { HollowIdeOverlay.focusSurface("vfx-node-name-$id") }
}
