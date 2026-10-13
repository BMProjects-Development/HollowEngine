package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.LocalUiViewport
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Popup
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.TextField
import ru.hollowhorizon.hollowengine.client.ui.UiCursorShape
import ru.hollowhorizon.hollowengine.client.ui.cursor
import ru.hollowhorizon.hollowengine.client.ui.focusScope
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.inspector.Hint
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.maxSize
import ru.hollowhorizon.hollowengine.client.ui.onClick
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.scrollable
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang

@Composable
internal fun AttributePicker(
    anchor: UiRect,
    available: List<EntityAttributeOption>,
    onClose: () -> Unit,
    onChoose: (EntityAttributeOption) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val viewport = LocalUiViewport.current
    Popup(anchorBounds = anchor, id = "ee-attribute-picker", onDismiss = onClose) {
        Column(
            tags = listOf("ee-attribute-picker"),
            modifier = Modifier.focusScope().maxSize(
                width = (viewport.width - 12f).coerceAtLeast(0f).px,
                height = (viewport.height * 0.6f).px,
            ),
        ) {
            TextField(
                value = query,
                id = "ee-attribute-picker-search",
                placeholder = "hollowengine.gui.entity_editor.attribute_add".lang,
                onChange = { query = it },
                fontSize = 9f,
                tags = listOf("insp-input"),
            )
            Column(tags = listOf("ee-attribute-choices"), modifier = Modifier.scrollable(horizontal = false)) {
                val matches = available.filter { it.matches(query) }.sortedBy { it.displayName }
                if (matches.isEmpty()) Hint(EntityEditorLang.nothingFound)
                matches.forEach { option ->
                    Row(
                        id = "ee-attribute-choice-${option.id}",
                        tags = listOf("ee-attribute-choice"),
                        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                            .let { if (option.translatedName == null) it else it.tooltipOnHover(option.id) }
                            .onClick { event ->
                                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onChoose(option)
                                event.consume()
                            },
                    ) {
                        Text(option.displayName, tags = listOf("ee-attribute-choice-label"))
                    }
                }
            }
        }
    }
}
