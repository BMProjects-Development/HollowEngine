package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect

data class UiDropdownSlider(
    val value: Float,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0f,
    val valueLabel: (Float) -> String = { it.toString() },
    val onChange: ((Float) -> Unit)? = null,
    val onCommit: ((Float) -> Unit)? = null,
)

/** A part of a line of a menu, drawn inside its row; [UiMenuParts] has the usual ones. */
typealias UiMenuPart = @Composable () -> Unit

/**
 * One line of a menu.
 *
 * @param label what the line says, and what a search of the menu finds it by.
 * @param mark what stands before the label: an icon, an item or a check.
 * @param body what takes the place of the label, such as a slider; a line with one is left out of a search.
 * @param tail what follows the label, such as a shortcut.
 */
data class UiDropdownItem(
    val label: String,
    val mark: UiMenuPart? = null,
    val body: UiMenuPart? = null,
    val tail: UiMenuPart? = null,
    val enabled: Boolean = true,
    val closeOnClick: Boolean = true,
    val separatorBefore: Boolean = false,
    val children: List<UiDropdownItem>? = null,
    val onClick: () -> Unit = {},
) {
    companion object {
        fun slider(label: String, slider: UiDropdownSlider) =
            UiDropdownItem(label, body = { MenuSlider(label, slider) }, closeOnClick = false)
    }
}

/** The usual parts of a line of a menu; those of a value that may be missing are missing with it. */
object UiMenuParts {
    fun icon(path: String?, tint: UiColor? = null): UiMenuPart? = path?.let {
        { Image(it, tags = listOf("dropdown-item-icon"), modifier = tint?.let { color -> Modifier.tint(color) }) }
    }

    fun item(stack: ItemStack): UiMenuPart = { Box(modifier = Modifier.size(MarkSize.px, MarkSize.px).item(stack)) }

    fun check(checked: Boolean): UiMenuPart = check(checked, UiCheckboxVariant.CHECKBOX)

    fun radio(checked: Boolean): UiMenuPart = check(checked, UiCheckboxVariant.RADIO)

    fun shortcut(text: String?): UiMenuPart? = text?.let { { Text(it, tags = listOf("dropdown-item-shortcut")) } }

    private fun check(checked: Boolean, variant: UiCheckboxVariant): UiMenuPart = {
        Checkbox(checked, variant, tags = listOf("dropdown-item-check"), modifier = Modifier.inputTransparent())
    }
}

val LocalUiMenuItemId = staticCompositionLocalOf { "" }

private const val MarkSize = 16f

@Composable
fun UiDropdown(
    id: String,
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: List<UiDropdownItem>,
    icon: String? = null,
    tags: Iterable<String> = emptyList(),
) {
    var anchorBounds by remember { mutableStateOf(UiRect.Zero) }
    Row(
        id = id,
        tags = listOf("dropdown-button") + tags,
        attributes = mapOf("expanded" to expanded.toString()),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .alignItems(vertical = UiAlign.CENTER)
            .onPlaced { anchorBounds = it }
            .onClick { event ->
                onExpandedChange(!expanded)
                event.consume()
            }
    ) {
        if (icon != null) Image(icon, tags = listOf("dropdown-button-icon"))
        if (label.isNotEmpty()) Text(label, tags = listOf("dropdown-button-label"))
        Box(tags = listOf("dropdown-button-arrow"))
    }

    if (!expanded) return
    ContextMenu(id, anchorBounds, items, onExpandedChange = onExpandedChange)
}

private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"

/** How long the pointer may rest on its way to an open submenu before the item under it takes over. */
private const val SubmenuIntentMillis = 300L

/** How much of the height of the screen a menu may take before it scrolls. */
private const val MenuHeightShare = 0.8f

private val SubmenuAlignment = UiPopupAlignment(
    anchorHorizontal = UiAlign.END,
    anchorVertical = UiAlign.START,
    offsetX = 6f,
    offsetY = -4f,
    flipsHorizontally = true,
)

/**
 * A menu at [anchorBounds].
 *
 * @param search the hint of a field on top that finds items through every submenu as it is typed; null leaves it out.
 * @param onBounds told where the menu lies once it is placed.
 */
@Composable
fun ContextMenu(
    id: String,
    anchorBounds: UiRect,
    items: List<UiDropdownItem>,
    alignment: UiPopupAlignment = UiPopupAlignment.BelowStart,
    layer: Int = 0,
    search: String? = null,
    onBounds: (UiRect) -> Unit = {},
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val anyLeading = items.any { it.mark != null }
    val maxHeight = LocalUiViewport.current.height * MenuHeightShare
    var openSubmenu by remember { mutableStateOf<Int?>(null) }
    var anchors by remember { mutableStateOf(emptyMap<Int, UiRect>()) }
    val intent = remember { UiSubmenuIntent() }
    var pending by remember { mutableStateOf<Int?>(null) }

    fun submenuOf(index: Int): Int? = index.takeIf { items.getOrNull(it)?.let { item -> item.children != null && item.enabled } == true }

    fun show(index: Int) {
        val submenu = submenuOf(index)
        if (submenu == openSubmenu) return
        openSubmenu = submenu
        intent.submenu = null
    }

    fun hover(index: Int, event: UiEvent) {
        when {
            index == openSubmenu -> {
                intent.track(event.x, event.y)
                pending = null
            }

            openSubmenu != null && intent.aims(event.x, event.y) -> pending = index
            else -> {
                pending = null
                show(index)
            }
        }
    }

    LaunchedEffect(pending) {
        val index = pending ?: return@LaunchedEffect
        delay(SubmenuIntentMillis)
        pending = null
        show(index)
    }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(0) }
    val found = remember(items, query) { if (search == null || query.isBlank()) null else UiMenuSearch.find(items, query) }
    Popup(
        anchorBounds = anchorBounds,
        alignment = alignment,
        layer = layer,
        id = "$id-popup",
        tags = listOf("dropdown-popup"),
        modifier = Modifier.maxSize(height = maxHeight.px).scrollable(horizontal = false).onPlaced(onBounds),
        onDismiss = { onExpandedChange(false) },
    ) {
        if (search != null) {
            Row(tags = listOf("dropdown-search"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
                Image(SearchIcon, tags = listOf("dropdown-search-icon"))
                TextField(
                    value = query,
                    placeholder = search,
                    onChange = {
                        query = it
                        picked = 0
                        openSubmenu = null
                    },
                    id = "$id-search",
                    tags = listOf("dropdown-search-input"),
                    modifier = Modifier.size(0.px, UiLength.Auto).grow(1f).onKeyInput { input ->
                        val shown = found.orEmpty()
                        when (input.key) {
                            GLFW.GLFW_KEY_DOWN -> picked = (picked + 1).coerceAtMost((shown.size - 1).coerceAtLeast(0))
                            GLFW.GLFW_KEY_UP -> picked = (picked - 1).coerceAtLeast(0)
                            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> shown.getOrNull(picked)?.let { match ->
                                dismiss()
                                match.item.onClick()
                            }

                            GLFW.GLFW_KEY_ESCAPE -> dismiss()

                            else -> return@onKeyInput
                        }
                        input.consume()
                    },
                )
            }
            val focus = LocalUiFocusRequester.current
            LaunchedEffect(Unit) { focus.request("$id-search") }
        }
        if (found != null) {
            val leading = found.any { it.item.mark != null }
            found.forEachIndexed { index, match ->
                Row(
                    id = "$id-found-$index",
                    tags = listOfNotNull("dropdown-item", "picked".takeIf { index == picked }),
                    modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                        .alignItems(vertical = UiAlign.CENTER)
                        .onClick { event ->
                            dismiss()
                            match.item.onClick()
                            event.consume()
                        },
                ) {
                    MenuMark(match.item, leading)
                    Text(match.item.label, tags = listOf("dropdown-item-label"))
                    if (match.path.isNotEmpty()) Text(match.path, tags = listOf("dropdown-item-shortcut"))
                }
            }
        } else items.forEachIndexed { index, item ->
            if (item.separatorBefore && index > 0) Box(tags = listOf("dropdown-separator"))
            val children = item.children
            val itemAction: (UiEvent) -> Unit = { event ->
                if (item.enabled) {
                    if (children != null) {
                        openSubmenu = if (openSubmenu == index) null else index
                    } else {
                        if (item.closeOnClick) dismiss()
                        item.onClick()
                    }
                    event.consume()
                }
            }
            Row(
                id = "$id-item-$index",
                tags = listOfNotNull(
                    "dropdown-item",
                    "disabled".takeUnless { item.enabled },
                    "open".takeIf { children != null && openSubmenu == index },
                ),
                modifier = Modifier.input(hoverable = item.enabled, clickable = item.enabled)
                    .cursor(if (item.enabled) UiCursorShape.HAND else UiCursorShape.DEFAULT)
                    .alignItems(vertical = UiAlign.CENTER)
                    .onEnter { hover(index, it) }
                    .onHover { hover(index, it) }
                    .onExit { if (pending == index) pending = null }
                    .let { if (children != null) it.onPlaced { rect -> if (anchors[index] != rect) anchors = anchors + (index to rect) } else it }
                    .onClick(itemAction)
            ) {
                CompositionLocalProvider(LocalUiMenuItemId provides "$id-item-$index") {
                    MenuMark(item, anyLeading)
                    item.body?.invoke() ?: Text(item.label, tags = listOf("dropdown-item-label"))
                    item.tail?.invoke()
                }
                if (children != null) Text("›", tags = listOf("dropdown-item-shortcut", "dropdown-item-submenu-arrow"))
            }
        }
    }

    val open = openSubmenu
    val children = open?.let { items.getOrNull(it)?.children }
    val anchor = open?.let(anchors::get)
    if (children != null && anchor != null) {
        ContextMenu(
            id = "$id-sub-$open",
            anchorBounds = anchor,
            items = children,
            onExpandedChange = onExpandedChange,
            alignment = SubmenuAlignment,
            layer = layer + 1,
            onBounds = { intent.submenu = it },
        )
    }
}

/** The mark of [item], or the room for one when [leading] says other items of the menu have one. */
@Composable
private fun MenuMark(item: UiDropdownItem, leading: Boolean) {
    val mark = item.mark
    when {
        mark != null -> mark()
        leading -> Box(modifier = Modifier.size(MarkSize.px, MarkSize.px))
    }
}

@Composable
private fun MenuSlider(label: String, slider: UiDropdownSlider) {
    val id = LocalUiMenuItemId.current
    val last = remember(id) { floatArrayOf(slider.value) }
    NumberField(
        value = slider.value.toDouble(),
        id = "$id-field",
        label = label,
        min = slider.min.toDouble(),
        max = slider.max.toDouble(),
        step = slider.step.takeIf { it > 0f }?.toDouble() ?: ((slider.max - slider.min) / SliderSteps).toDouble(),
        slider = true,
        format = { slider.valueLabel(it.toFloat()) },
        modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
        onGestureEnd = { slider.onCommit?.invoke(last[0]) },
    ) { next ->
        last[0] = next.toFloat()
        slider.onChange?.invoke(last[0])
    }
}

/** How many steps a slider of a menu without a step of its own goes through, by its arrows or with Ctrl. */
private const val SliderSteps = 20f