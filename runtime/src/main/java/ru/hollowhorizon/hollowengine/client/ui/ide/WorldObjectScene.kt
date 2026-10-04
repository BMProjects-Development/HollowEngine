package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.WorldInspector
import ru.hollowhorizon.hollowengine.client.editor.WorldObjectEditing
import ru.hollowhorizon.hollowengine.client.editor.WorldToScreenProjector
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelOrNull
import ru.hollowhorizon.hollowengine.common.attachments.components.vfxComponent
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectFavorite
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjects
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import java.util.*

/** One loaded object as scene window lists it, read off entity on game thread. */
private data class WorldObjectRow(
    val entityId: Int,
    val uuid: UUID,
    val parent: UUID?,
    val label: String,
    val icon: String,
    /** Whether the object itself falls within the chosen [WorldSceneScope]. */
    val inScope: Boolean,
)

/** How much of the level, that scene window lists. Search always looks through every loaded object. */
internal enum class WorldSceneScope(val key: String) {
    NEARBY("scope.nearby"), IN_VIEW("scope.in_view"), LOADED("scope.loaded"),
}

/**
 * Objects of the level the player is in, as the scene window shows them while no open file has a
 * hierarchy of its own.
 */
@ClientOnly
internal object WorldObjectScene {
    const val SCENE_ID = "world-objects"
    private const val ROOT = "world"
    private const val FAVORITES = "favorites"
    private const val FAVORITE_ROW = "fav:"
    private const val LANG = WorldObjectEditing.LANG
    private const val MAX_DEPTH = 64

    /** In blocks from the camera: what "nearby" means, and how far "in view" still looks. */
    private const val NEARBY_DISTANCE = 64.0
    private const val VIEW_DISTANCE = 128.0

    /** Ticks between looks at what the camera can see, while the scope depends on it. */
    private const val SCOPE_REFRESH_TICKS = 10

    private const val MODEL_ICON = "hollowengine:textures/gui/icons/files/model.svg"
    private const val EFFECT_ICON = "hollowengine:textures/gui/icons/files/effect.svg"

    /** Rebuilt on the game thread after a change, read by the scene window's composition. */
    private var rows by mutableStateOf<List<WorldObjectRow>>(emptyList())
    private val collapsed = mutableStateListOf<String>()
    private var dirty = true
    private var ticksToScope = 0

    private var scope by mutableStateOf(WorldSceneScope.NEARBY)
    private var scopeMenuOpen by mutableStateOf(false)

    /** The level favorites were last asked for, while the player may edit it. */
    private var favoritesAskedFor: Level? = null

    init {
        WorldObjects.onChanged { level -> if (level.isClientSide) dirty = true }
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.Client) {
        val level = event.minecraft.level
        val permitted = event.minecraft.player?.hasPermissions(PlayerPermissions.GAMEMASTER) == true
        val asking = level?.takeIf { permitted }
        if (asking !== favoritesAskedFor) {
            favoritesAskedFor = asking
            if (asking != null) WorldObjectEditing.requestFavorites() else WorldObjectEditing.acceptFavorites(emptyList())
        }

        val cameraMoved = scope != WorldSceneScope.LOADED && --ticksToScope <= 0
        if (!dirty && !cameraMoved) return
        dirty = false
        ticksToScope = SCOPE_REFRESH_TICKS
        val updated = level?.let(WorldObjects::all).orEmpty().map(::rowOf)
            .sortedWith(compareBy({ it.label.lowercase() }, { it.entityId }))
        if (updated != rows) rows = updated
    }

    private fun rowOf(entity: WorldObjectEntity): WorldObjectRow {
        val model = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelOrNull()
        val icon = when {
            model != null -> MODEL_ICON
            entity.vfxComponent != null -> EFFECT_ICON
            else -> WorldObjectEditing.EMPTY_ICON
        }
        return WorldObjectRow(entity.id, entity.uuid, entity.parentId, entity.name.string, icon, inScope(entity))
    }

    private fun inScope(entity: WorldObjectEntity): Boolean {
        val camera = WorldToScreenProjector.cameraPosition
        val position = entity.pose(1f).position
        val distance = camera.distanceToSqr(position.x, position.y, position.z)
        return when (scope) {
            WorldSceneScope.LOADED -> true
            WorldSceneScope.NEARBY -> distance <= NEARBY_DISTANCE * NEARBY_DISTANCE
            WorldSceneScope.IN_VIEW -> {
                if (distance > VIEW_DISTANCE * VIEW_DISTANCE) return false
                val screen = WorldToScreenProjector.project(position.x, position.y + entity.bbHeight * 0.5, position.z)
                screen.onScreen && screen.x in 0f..WorldToScreenProjector.width && screen.y in 0f..WorldToScreenProjector.height
            }
        }
    }

    /** Whether the player may edit the world, which is when the scene window lists its objects. */
    fun isAvailable(): Boolean = Minecraft.getInstance().player?.hasPermissions(PlayerPermissions.GAMEMASTER) == true

    /** The choice of what part of the level to list, for the window's header. */
    val toolbar: @Composable () -> Unit = { ScopeToolbar() }

    /** The hierarchy for the scene window, or null for a player who may not edit the world. */
    fun target(): SceneTarget? {
        if (!isAvailable()) return null
        val all = rows
        val favorites = WorldObjectEditing.favorites
        val selected = WorldInspector.selectedEntityId
        val byId = all.associateBy { it.uuid }

        return SceneTarget(
            id = SCENE_ID,
            items = tree(withAncestors(all.filter { it.inScope }, byId), favorites, byId, selected, expandAll = false),
            onSelect = ::select,
            onToggle = { id -> if (!collapsed.remove(id)) collapsed += id },
            hint = "$LANG.empty".lang.takeIf { all.isEmpty() },
            menu = { id -> menu(id, byId) },
            onKey = ::handleKey,
            onMove = ::move,
            canMove = { dragged, target -> canMove(dragged, target, byId) },
            search = { query ->
                val matches = all.filter { it.label.contains(query, ignoreCase = true) }
                val favoriteMatches = favorites.filter { it.name.contains(query, ignoreCase = true) }
                tree(withAncestors(matches, byId), favoriteMatches, byId, selected, expandAll = true)
            },
            toolbar = toolbar,
        )
    }

    /** [rows] together with every row above them, so a match never loses its place in the hierarchy. */
    private fun withAncestors(rows: List<WorldObjectRow>, byId: Map<UUID, WorldObjectRow>): List<WorldObjectRow> {
        val kept = HashSet<UUID>()
        rows.forEach { row ->
            var current: WorldObjectRow? = row
            var depth = 0
            while (current != null && depth++ < MAX_DEPTH && kept.add(current.uuid)) {
                current = current.parent?.let(byId::get)
            }
        }
        return byId.values.filter { it.uuid in kept }
    }

    private fun tree(
        shown: List<WorldObjectRow>,
        favorites: List<WorldObjectFavorite>,
        byId: Map<UUID, WorldObjectRow>,
        selected: Int?,
        expandAll: Boolean,
    ): List<UiTreeItem<Any?>> {
        val items = ArrayList<UiTreeItem<Any?>>()
        fun open(id: String) = expandAll || id !in collapsed

        if (favorites.isNotEmpty()) {
            items += UiTreeItem(
                id = FAVORITES,
                label = "$LANG.favorites".lang,
                depth = 0,
                payload = null,
                icon = WorldObjectEditing.FAVORITE_ICON,
                hasChildren = true,
                expanded = open(FAVORITES),
            )
            if (open(FAVORITES)) favorites.forEach { favorite ->
                val loaded = byId[favorite.uuid]
                items += UiTreeItem(
                    id = FAVORITE_ROW + favorite.uuid,
                    label = loaded?.label ?: "$LANG.not_loaded".lang(favorite.name),
                    depth = 1,
                    payload = loaded?.entityId,
                    icon = loaded?.icon ?: WorldObjectEditing.FAVORITE_ICON,
                    selected = loaded != null && loaded.entityId == selected,
                )
            }
        }

        val visible = shown.associateBy { it.uuid }
        val children = shown.groupBy { row -> row.parent?.takeIf(visible::containsKey) }
        items += UiTreeItem(
            id = ROOT,
            label = "$LANG.world".lang,
            depth = 0,
            payload = null,
            icon = WorldObjectEditing.WORLD_ICON,
            hasChildren = shown.isNotEmpty(),
            expanded = open(ROOT),
        )

        fun addChildren(parent: UUID?, depth: Int) {
            if (depth > MAX_DEPTH) return
            children[parent].orEmpty().forEach { row ->
                val id = row.uuid.toString()
                items += UiTreeItem(
                    id = id,
                    label = row.label,
                    depth = depth,
                    payload = row.entityId,
                    icon = row.icon,
                    hasChildren = !children[row.uuid].isNullOrEmpty(),
                    expanded = open(id),
                    selected = row.entityId == selected,
                )
                if (open(id)) addChildren(row.uuid, depth + 1)
            }
        }
        if (open(ROOT)) addChildren(null, 1)
        return items
    }

    @Composable
    private fun ScopeToolbar() {
        val current = scope
        UiDropdown(
            id = "world-scene-scope",
            label = "$LANG.${current.key}".lang,
            expanded = scopeMenuOpen,
            onExpandedChange = { scopeMenuOpen = it },
            tags = listOf("panel-dropdown"),
            items = WorldSceneScope.entries.map { option ->
                UiDropdownItem("$LANG.${option.key}".lang, checked = option == current) {
                    scope = option
                    dirty = true
                    scopeMenuOpen = false
                }
            },
        )
    }

    private fun select(id: String?) {
        val target = id?.let(::objectOf)
        if (target != null) WorldObjectEditing.select(target.id) else WorldInspector.close()
    }

    private fun menu(id: String?, byId: Map<UUID, WorldObjectRow>): List<UiDropdownItem> {
        if (id == null || id == ROOT) {
            return listOf(
                UiDropdownItem(
                    "$LANG.create_empty".lang,
                    icon = WorldObjectEditing.EMPTY_ICON
                ) { WorldObjectEditing.spawnEmpty(null) },
            )
        }
        objectOf(id)?.let { return WorldObjectEditing.menu(it, inScene = true) }
        val bookmarked = favoriteUuid(id)?.takeIf { it !in byId } ?: return emptyList()
        return listOf(
            UiDropdownItem("$LANG.go_to".lang, icon = WorldObjectEditing.WORLD_ICON) {
                WorldObjectEditing.goTo(
                    bookmarked
                )
            },
            UiDropdownItem("$LANG.favorite_remove".lang, icon = WorldObjectEditing.FAVORITE_ICON) {
                WorldObjectEditing.setFavorite(bookmarked, false)
            },
        )
    }

    private fun handleKey(input: UiKeyInput): Boolean {
        if (input.repeat) return false
        if (input.key == GLFW.GLFW_KEY_DELETE || input.key == GLFW.GLFW_KEY_D && input.command) {
            return WorldObjectEditing.handleShortcut(input.key, input.modifiers)
        }
        return false
    }

    private fun move(dragged: String, target: String): Boolean {
        val moved = objectOf(dragged) ?: return false
        if (target == FAVORITES) {
            WorldObjectEditing.setFavorite(moved.uuid, true)
            return true
        }
        WorldObjectEditing.setParent(moved, objectOf(target))
        return true
    }

    /** Whether [dragged] may go under [target]: not under itself or anything below it, and only loaded objects. */
    private fun canMove(dragged: String, target: String, byId: Map<UUID, WorldObjectRow>): Boolean {
        val uuid = parseUuid(dragged)?.takeIf(byId::containsKey) ?: return false
        if (target == FAVORITES || target == ROOT) return true
        var current = byId[parseUuid(target) ?: favoriteUuid(target) ?: return false] ?: return false
        var depth = 0
        while (depth++ < MAX_DEPTH) {
            if (current.uuid == uuid) return false
            current = current.parent?.let(byId::get) ?: return true
        }
        return true
    }

    /** The loaded object a row stands for, whether it is listed in the hierarchy or among the bookmarks. */
    private fun objectOf(id: String): WorldObjectEntity? {
        val uuid = parseUuid(id) ?: favoriteUuid(id) ?: return null
        val level = Minecraft.getInstance().level ?: return null
        return WorldObjects.find(level, uuid)
    }

    private fun favoriteUuid(id: String): UUID? =
        id.takeIf { it.startsWith(FAVORITE_ROW) }?.removePrefix(FAVORITE_ROW)?.let(::parseUuid)

    private fun parseUuid(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()
}
