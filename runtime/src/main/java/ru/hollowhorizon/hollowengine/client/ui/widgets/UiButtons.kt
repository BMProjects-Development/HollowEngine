package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*

/** What a [Button] is for: an ordinary action, the one a dialog leads to, or one that destroys something. */
enum class UiButtonKind(val tag: String?) {
    NORMAL(null), PRIMARY("primary"), DANGER("danger"),
}

/**
 * Button of the editor: a label, with an icon before it when [icon] is given; [active] marks one picked out of
 * a few, like a tool.
 */
@Composable
fun Button(
    label: String,
    id: String? = null,
    icon: String? = null,
    kind: UiButtonKind = UiButtonKind.NORMAL,
    active: Boolean = false,
    enabled: Boolean = true,
    tooltip: String? = null,
    tags: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        id = id,
        tags = listOfNotNull("button", kind.tag, "active".takeIf { active }, "disabled".takeUnless { enabled }) + tags,
        modifier = modifier.buttonInput(enabled, tooltip, onClick),
    ) {
        icon?.let { Image(it, tags = listOf("button-icon")) }
        Text(label, tags = listOf("button-label"))
    }
}

/**
 * An icon that acts. Its fill shows under the pointer, and [active] marks it as switched on; `.icon-button` in
 * buttons.hss. The `inline` tag drops the fill, for icons that sit inside a row of text or next to a field.
 */
@Composable
fun IconButton(
    icon: String,
    tooltip: String?,
    id: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    iconSize: Float? = null,
    tags: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOfNotNull("icon-button", "active".takeIf { active }, "disabled".takeUnless { enabled }) + tags,
        modifier = modifier.buttonInput(enabled, tooltip, onClick),
    ) {
        val placed = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
        key(icon) {
            Image(icon, tags = listOf("button-icon"), modifier = iconSize?.let { placed.size(it.px, it.px) } ?: placed)
        }
    }
}

@Composable
private fun Modifier.buttonInput(enabled: Boolean, tooltip: String?, onClick: () -> Unit): Modifier =
    input(hoverable = true, clickable = true).cursor(if (enabled) UiCursorShape.HAND else UiCursorShape.DEFAULT)
        .tooltipOnHover(tooltip.orEmpty()).onClick { event ->
            if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onClick
            if (enabled) onClick()
            event.consume()
        }
