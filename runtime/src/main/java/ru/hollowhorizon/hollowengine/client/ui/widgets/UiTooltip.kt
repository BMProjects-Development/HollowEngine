package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import kotlin.time.Duration.Companion.milliseconds

/**
 * A hover tooltip for the node the returned modifier is applied to.
 *
 * It is a modifier rather than a wrapper composable so it can be added to a button that already has
 * its own layout, without a container in between: the call sites the tooltip and the popup it emits
 * both live here, and the caller only spends a `.then(...)`.
 *
 * ```kotlin
 * Box(modifier = Modifier.onClick { ... }.tooltipOnHover("Next (Enter)"))
 * ```
 */
@Composable
fun Modifier.tooltipOnHover(
    text: String,
    alignment: UiPopupAlignment = UiPopupAlignment.BelowStart,
    delayMillis: Long = TooltipDelayMillis,
): Modifier = tooltipOnHover(enabled = text.isNotBlank(), alignment, delayMillis) {
    Text(text, tags = listOf("ui-tooltip-label"))
}

/**
 * A hover tooltip with any [content], such as an item's own tooltip lines. Nothing shows while
 * [enabled] is false, so a node can keep the modifier and simply have nothing to say.
 */
@Composable
fun Modifier.tooltipOnHover(
    enabled: Boolean,
    alignment: UiPopupAlignment = UiPopupAlignment.BelowStart,
    delayMillis: Long = TooltipDelayMillis,
    tags: List<String> = listOf("ui-tooltip"),
    content: HollowUiContent,
): Modifier {
    var bounds by remember { mutableStateOf(UiRect.Zero) }
    var hovered by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(hovered, enabled, delayMillis) {
        if (!hovered || !enabled) {
            visible = false
            return@LaunchedEffect
        }
        delay(delayMillis.milliseconds)
        visible = true
    }

    if (visible && bounds.width > 0f) {
        Popup(
            anchorBounds = bounds,
            alignment = alignment,
            layer = TooltipLayer,
            tags = tags,
            modifier = Modifier.inputTransparent(),
            dismissOnOutside = false,
            content = { content() },
        )
    }

    return then(Modifier.input(hoverable = true)
        .onEnter { hovered = true }
        .onExit { hovered = false }
        .onPlaced { bounds = it })
}

private const val TooltipDelayMillis = 450L
private const val TooltipLayer = 100
