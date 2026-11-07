package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * Something a [RegistryPicker] offers.
 *
 * @param value what picking it gives, the id unless it says more, such as the components of a stack.
 * @param stack what shows it.
 */
data class UiRegistryEntry(val value: String, val title: String, val stack: ItemStack)

/** A tab of a [RegistryPicker] with the entries it lists. */
class UiRegistryTab(val title: String, val entries: List<UiRegistryEntry>)

/**
 * A window to pick an entry of a registry out of [tabs], found by name or id as it is typed. The list composes only
 * the rows in view, so a registry of a large modpack scrolls as easily as a small one.
 *
 * @param details what is set up on the chosen entry before it is taken, such as the state of a block: it gets the
 * value so far and writes a new one. Without it a click takes an entry at once; with it a button does.
 */
@Composable
fun RegistryPicker(
    id: String,
    title: String,
    tabs: List<UiRegistryTab>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    details: (@Composable (value: String, write: (String) -> Unit) -> Unit)? = null,
) {
    var tab by remember(id) { mutableStateOf(tabs.indexOfFirst { it.entries.isNotEmpty() }.coerceAtLeast(0)) }
    var query by remember(id) { mutableStateOf("") }
    var chosen by remember(id) { mutableStateOf<String?>(null) }
    val entries = tabs.getOrNull(tab)?.entries.orEmpty()
    val shown = remember(entries, query) { filtered(entries, query) }
    val viewport = LocalUiViewport.current

    fun choose(entry: UiRegistryEntry) {
        if (details == null) {
            onPick(entry.value)
            onDismiss()
        } else chosen = entry.value
    }

    Popup(
        anchorBounds = viewport,
        alignment = UiPopupAlignment.Centered,
        id = "$id-popup",
        tags = listOf("registry-picker"),
        layer = PickerLayer,
        modal = true,
        onDismiss = onDismiss,
        modifier = Modifier.size((if (details == null) ListWidth else ListWidth + DetailsWidth).px, (viewport.height * HeightShare).px),
    ) {
        Text(title, tags = listOf("registry-picker-title"))
        Row(tags = listOf("registry-picker-tabs")) {
            tabs.forEachIndexed { index, each ->
                Text(
                    "${each.title} ${each.entries.size}",
                    id = "$id-tab-$index",
                    tags = listOfNotNull("registry-picker-tab", "selected".takeIf { index == tab }),
                    modifier = Modifier.input(hoverable = true, clickable = true).onClick { event ->
                        tab = index
                        event.consume()
                    },
                )
            }
        }
        Row(tags = listOf("dropdown-search"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            Image(SearchIcon, tags = listOf("dropdown-search-icon"))
            TextField(
                value = query,
                placeholder = "$LANG.search".lang,
                onChange = { query = it },
                id = "$id-search",
                tags = listOf("dropdown-search-input"),
                modifier = Modifier.size(0.px, UiLength.Auto).grow(1f).onKeyInput { input ->
                    if (input.key != GLFW.GLFW_KEY_ENTER && input.key != GLFW.GLFW_KEY_KP_ENTER) return@onKeyInput
                    shown.firstOrNull()?.let(::choose)
                    input.consume()
                },
            )
        }
        val focus = LocalUiFocusRequester.current
        LaunchedEffect(Unit) { focus.request("$id-search") }
        Row(tags = listOf("registry-picker-body"), modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
            if (shown.isEmpty()) {
                Text("$LANG.nothing".lang, tags = listOf("registry-picker-empty"))
            } else LazyColumn(
                id = "$id-list",
                tags = listOf("registry-picker-list"),
                modifier = Modifier.size(0.px, 100.percent).grow(1f),
            ) {
                items(shown, key = { it.value }) { entry ->
                    EntryRow("$id-entry-${entry.value}", entry, entry.value == chosen) { choose(entry) }
                }
            }
            val value = chosen
            if (details != null && value != null) {
                Column(tags = listOf("registry-picker-details"), modifier = Modifier.size(DetailsWidth.px, 100.percent)) {
                    details(value) { chosen = it }
                    Box(modifier = Modifier.size(100.percent, 0.px).grow(1f))
                    Button(
                        "$LANG.pick".lang,
                        id = "$id-pick",
                        kind = UiButtonKind.PRIMARY,
                        onClick = {
                            onPick(value)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(id: String, entry: UiRegistryEntry, chosen: Boolean, onChoose: () -> Unit) {
    Row(
        id = id,
        tags = listOfNotNull("dropdown-item", "registry-picker-row", "selected".takeIf { chosen }),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .alignItems(vertical = UiAlign.CENTER)
            .onClick { event ->
                onChoose()
                event.consume()
            },
    ) {
        Box(modifier = Modifier.size(ItemSize.px, ItemSize.px).item(entry.stack))
        Text(entry.title, tags = listOf("dropdown-item-label"), modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS))
        Text(entry.value, tags = listOf("dropdown-item-shortcut"), modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS))
    }
}

/** The entries every word of [query] is found in, by title or by value, those whose title starts with it first. */
internal fun filtered(entries: List<UiRegistryEntry>, query: String): List<UiRegistryEntry> {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return entries
    val whole = query.trim().lowercase()
    return entries.filter { entry ->
        val text = "${entry.title} ${entry.value}".lowercase()
        words.all { it in text }
    }.sortedBy { if (it.title.lowercase().startsWith(whole)) 0 else 1 }
}

private const val LANG = "hollowengine.gui.registry_picker"
private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
private const val PickerLayer = 100
private const val ListWidth = 420f
private const val DetailsWidth = 220f
private const val ItemSize = 16f

/** How much of the height of the screen the window takes. */
private const val HeightShare = 0.7f
