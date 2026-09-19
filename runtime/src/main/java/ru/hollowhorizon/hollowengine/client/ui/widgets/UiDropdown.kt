package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect

enum class UiDropdownMark {
    CHECKBOX,
    RADIO
}

data class UiDropdownSlider(
    val value: Float,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0f,
    val valueLabel: (Float) -> String = { it.toString() },
    val onChange: ((Float) -> Unit)? = null,
    val onCommit: ((Float) -> Unit)? = null,
)

data class UiDropdownItem(
    val label: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val mark: UiDropdownMark? = null,
    val slider: UiDropdownSlider? = null,
    val closeOnClick: Boolean = true,
    val shortcut: String? = null,
    val separatorBefore: Boolean = false,
    val children: List<UiDropdownItem>? = null,
    val onClick: () -> Unit = {},
)

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

private val SubmenuAlignment = UiPopupAlignment(
    anchorHorizontal = UiAlign.END,
    anchorVertical = UiAlign.START,
    offsetX = 6f,
    offsetY = -4f,
)

@Composable
fun ContextMenu(
    id: String,
    anchorBounds: UiRect,
    items: List<UiDropdownItem>,
    alignment: UiPopupAlignment = UiPopupAlignment.BelowStart,
    layer: Int = 0,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val anyLeading = items.any { it.icon != null || it.mark != null }
    var openSubmenu by remember { mutableStateOf<Int?>(null) }
    var anchors by remember { mutableStateOf(emptyMap<Int, UiRect>()) }
    Popup(
        anchorBounds = anchorBounds,
        alignment = alignment,
        layer = layer,
        id = "$id-popup",
        tags = listOf("dropdown-popup"),
        onDismiss = { onExpandedChange(false) },
    ) {
        items.forEachIndexed { index, item ->
            if (item.separatorBefore && index > 0) Box(tags = listOf("dropdown-separator"))
            if (item.slider != null) {
                DropdownSliderRow("$id-item-$index", item.label, item.slider) { openSubmenu = null }
                return@forEachIndexed
            }
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
                    .onEnter { openSubmenu = if (children != null && item.enabled) index else null }
                    .let { if (children != null) it.onPlaced { rect -> if (anchors[index] != rect) anchors = anchors + (index to rect) } else it }
                    .onClick(itemAction)
            ) {
                if (item.mark != null) {
                    Checkbox(
                        checked = item.checked,
                        variant = when (item.mark) {
                            UiDropdownMark.CHECKBOX -> UiCheckboxVariant.CHECKBOX
                            UiDropdownMark.RADIO -> UiCheckboxVariant.RADIO
                        },
                        tags = listOf("dropdown-item-check"),
                        modifier = Modifier.onClick(itemAction),
                    )
                } else if (item.icon != null) {
                    Image(item.icon, tags = listOf("dropdown-item-icon"))
                } else if (anyLeading) {
                    Box(modifier = Modifier.size(16.px, 16.px))
                }
                Text(item.label, tags = listOf("dropdown-item-label"))
                if (item.shortcut != null) Text(item.shortcut, tags = listOf("dropdown-item-shortcut"))
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
        )
    }
}

@Composable
private fun DropdownSliderRow(id: String, label: String, slider: UiDropdownSlider, onEnter: () -> Unit) {
    var live by remember(slider.value) { mutableStateOf(slider.value) }
    Row(
        id = id,
        tags = listOf("dropdown-item", "dropdown-slider-item"),
        modifier = Modifier.input(hoverable = true).alignItems(vertical = UiAlign.CENTER).onEnter { onEnter() },
    ) {
        Text(label, tags = listOf("dropdown-item-label"))
        Slider(
            value = slider.value,
            min = slider.min,
            max = slider.max,
            step = slider.step,
            onValueChange = { live = it; slider.onChange?.invoke(it) },
            onValueCommit = { slider.onCommit?.invoke(it) },
            tags = listOf("dropdown-item-slider"),
            modifier = Modifier.grow(),
        )
        Text(slider.valueLabel(live), tags = listOf("dropdown-item-value"))
    }
}