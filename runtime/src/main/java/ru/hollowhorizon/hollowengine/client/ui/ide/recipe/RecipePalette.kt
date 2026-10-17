package ru.hollowhorizon.hollowengine.client.ui.ide.recipe

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.itemTooltip
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.time.Duration.Companion.milliseconds

private const val CellSize = 20f
private const val CellGap = 2f
private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
private const val SearchDelayMillis = 120L
private const val InventoryRefreshMillis = 500L

enum class RecipePaletteTab { ITEMS, INVENTORY }

/** What the palette shows; the recipe editor keeps it while its tab is switched away. */
class RecipePaletteState {
    var query by mutableStateOf("")
    var tab by mutableStateOf(RecipePaletteTab.ITEMS)
    internal val list = LazyListState()
    internal var results by mutableStateOf<List<PaletteEntry>?>(null)
    internal var inventory by mutableStateOf<List<PaletteEntry>>(emptyList())
}

/** One cell: what it puts into a slot, and how it is found and named. */
internal class PaletteEntry(
    val pick: RecipePick,
    val key: String,
    val name: String,
    val search: String = "",
)

/**
 * Every item in the game, built once off the frame thread and shared by all open recipe editors.
 * Names are the current language's, so a change of language builds it again.
 */
internal object RecipeItemIndex {
    var entries by mutableStateOf<List<PaletteEntry>?>(null)
        private set
    private var language: String? = null

    suspend fun ensure() {
        val current = Minecraft.getInstance().languageManager.selected
        if (entries != null && language == current) return
        val built = withContext(Dispatchers.Default) { build() }
        language = current
        entries = built
    }

    private fun build(): List<PaletteEntry> = BuiltInRegistries.ITEM.keySet().sorted().mapNotNull { id ->
        val stack = ItemStack(BuiltInRegistries.ITEM.get(id)).takeUnless(ItemStack::isEmpty) ?: return@mapNotNull null
        val name = stack.hoverName.string
        // One lowercase string per item, so a search is one pass of `contains` with nothing allocated.
        PaletteEntry(RecipePick(stack), id.toString(), name, "$id\n${name.lowercase()}")
    }

    /** Items whose id or name contain [query], or with a leading `#`, the item tags matching it. */
    fun search(entries: List<PaletteEntry>, query: String): List<PaletteEntry> {
        val text = query.trim().lowercase()
        return when {
            text.startsWith("#") -> tags(text.removePrefix("#"))
            text.isEmpty() -> entries
            else -> entries.filter { text in it.search }
        }
    }

    /** Tags only need their first item for an icon; the rest of a big tag is never walked. */
    private fun tags(text: String): List<PaletteEntry> =
        BuiltInRegistries.ITEM.getTagNames().map { it.location().toString() }.filter { text in it }.sorted().toList()
            .mapNotNull { id ->
                val first = BuiltInRegistries.ITEM.getTag(
                    TagKey.create(
                        Registries.ITEM, ResourceLocation.tryParse(id) ?: return@mapNotNull null
                    )
                ).flatMap { set -> set.stream().findFirst() }.orElse(null) ?: return@mapNotNull null
                PaletteEntry(RecipePick(ItemStack(first.value()), tag = id), "#$id", "#$id")
            }
}


@Composable
internal fun RecipePalette(session: RecipeEditorSession, readOnly: Boolean) {
    val state = session.palette
    val index = RecipeItemIndex.entries

    LaunchedEffect(Unit) { RecipeItemIndex.ensure() }
    LaunchedEffect(state.query, index) {
        val entries = index ?: return@LaunchedEffect
        if (state.results != null) delay(SearchDelayMillis.milliseconds)
        val query = state.query
        state.results = withContext(Dispatchers.Default) { RecipeItemIndex.search(entries, query) }
    }
    LaunchedEffect(state.tab) {
        while (state.tab == RecipePaletteTab.INVENTORY) {
            state.inventory = inventoryEntries()
            delay(InventoryRefreshMillis.milliseconds)
        }
    }

    Column(tags = listOf("recipe-palette"), modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
        Row(tags = listOf("recipe-palette-header"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            PaletteTab(state, RecipePaletteTab.ITEMS, "palette.items")
            PaletteTab(state, RecipePaletteTab.INVENTORY, "palette.inventory")
            if (state.tab == RecipePaletteTab.ITEMS) SearchField(state)
        }
        val hint = when {
            readOnly -> "palette.read_only"
            session.selected == null -> "palette.pick_slot"
            else -> "palette.shift_hint"
        }
        Text(recipeLang(hint).lang, tags = listOf("recipe-palette-hint"))

        val entries = when (state.tab) {
            RecipePaletteTab.ITEMS -> state.results
            RecipePaletteTab.INVENTORY -> state.inventory
        }
        when {
            entries == null -> Text(recipeLang("palette.loading").lang, tags = listOf("recipe-palette-hint"))
            entries.isEmpty() -> Text(recipeLang("palette.empty").lang, tags = listOf("recipe-palette-hint"))
            else -> PaletteGrid(session, entries)
        }
    }
}

@Composable
private fun PaletteTab(state: RecipePaletteState, tab: RecipePaletteTab, label: String) {
    Box(
        id = "recipe-palette-tab-${tab.name.lowercase()}",
        tags = listOfNotNull("recipe-palette-tab", "selected".takeIf { state.tab == tab }),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick { event ->
            if (event.isLeftClick()) state.tab = tab
            event.consume()
        },
    ) {
        Text(recipeLang(label).lang, tags = listOf("recipe-palette-tab-label"))
    }
}

/** A field that looks like one: a frame, a magnifier, and the hint inside until something is typed. */
@Composable
private fun SearchField(state: RecipePaletteState) {
    Row(
        tags = listOf("recipe-search"),
        modifier = Modifier.size(0.px, 22.px).grow(1f).alignItems(vertical = UiAlign.CENTER),
    ) {
        Image(SearchIcon, tags = listOf("recipe-search-icon"))
        TextField(
            value = state.query,
            onChange = { state.query = it },
            placeholder = recipeLang("palette.search").lang,
            id = "recipe-palette-search",
            tags = listOf("recipe-search-input"),
            modifier = Modifier.size(0.px, 100.percent).grow(1f),
        )
    }
}

@Composable
private fun PaletteGrid(session: RecipeEditorSession, entries: List<PaletteEntry>) {
    val list = session.palette.list
    val columns = ((list.scroll.viewport.width + CellGap) / (CellSize + CellGap)).toInt().coerceAtLeast(1)
    val rows = (entries.size - 1) / columns + 1
    LazyColumn(
        tags = listOf("recipe-palette-grid"),
        modifier = Modifier.size(100.percent, 0.px).grow(1f),
        state = list,
        gap = CellGap,
    ) {
        items(rows, key = { row -> entries[row * columns].key }) { row ->
            Row(modifier = Modifier.size(100.percent, CellSize.px).gap(CellGap.px)) {
                for (index in row * columns until minOf(row * columns + columns, entries.size)) {
                    val entry = entries[index]
                    key(entry.key) { PaletteCell(session, entry) }
                }
            }
        }
    }
}

/**
 * A click puts the entry into the picked slot, Shift adds it as an alternative, and a drag drops it
 * on any slot. A draggable node never gets a click, so the click is read off the release instead,
 * unless the press turned into a drag.
 */
@Composable
private fun PaletteCell(session: RecipeEditorSession, entry: PaletteEntry) {
    val dragged = remember { booleanArrayOf(false) }
    val base =
        Modifier.size(CellSize.px, CellSize.px).input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .onPress { dragged[0] = false }.dragSource(LocalDragAndDrop.current) {
                dragged[0] = true
                UiDragItem(payload = entry.pick, label = entry.name)
            }.onRelease { event ->
                if (!dragged[0] && event.isLeftClick()) session.pick(entry.pick, add = event.isShiftDown())
                event.consume()
            }
    InlineWidget(
        id = "recipe-palette-${entry.key}",
        tags = listOfNotNull("recipe-palette-cell", "tag".takeIf { entry.pick.tag != null }),
        modifier = if (entry.pick.tag != null) base.tooltipOnHover(entry.name) else base.itemTooltip { entry.pick.stack },
    ) {
        Item(
            entry.pick.stack,
            tags = listOf("recipe-palette-item"),
            modifier = Modifier.size(16.px, 16.px).inputTransparent()
        )
    }
}

/** The player's stacks as they are, components and all, for items the registry alone cannot give. */
private fun inventoryEntries(): List<PaletteEntry> {
    val inventory = Minecraft.getInstance().player?.inventory ?: return emptyList()
    return (inventory.items + inventory.armor + inventory.offhand).withIndex().filterNot { it.value.isEmpty }
        .map { (slot, stack) -> PaletteEntry(RecipePick(stack.copy()), "inventory-$slot", stack.hoverName.string) }
}

internal fun recipeLang(name: String) = "hollowengine.gui.recipe_editor.$name"
