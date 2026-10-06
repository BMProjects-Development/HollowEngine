package ru.hollowhorizon.hollowengine.client.ui.inspector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ru.hollowhorizon.hollowengine.client.ui.Checkbox
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.UiCursorShape
import ru.hollowhorizon.hollowengine.client.ui.cursor
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.onClick
import ru.hollowhorizon.hollowengine.client.utils.lang

/** Colliders on the bone whose attachment the inspector shows, by name, for [CollidersField]. */
val LocalEditorColliders = staticCompositionLocalOf { emptyList<String>() }

/**
 * Which of the bone's colliders something is made of, one box per collider.
 */
@Composable
internal fun CollidersField(label: String?, description: String?, value: JsonElement, onChange: (JsonElement) -> Unit) {
    val available = LocalEditorColliders.current
    val chosen = (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val takesAll = chosen.isEmpty()

    fun write(next: Set<String>) {
        if (next.isEmpty()) return
        val names = if (next.containsAll(available) && next.all { it in available }) emptyList() else next.toList()
        onChange(JsonArray(names.map(::JsonPrimitive)))
    }

    Column(tags = listOf("insp-field")) {
        FieldLabel(label, description)
        if (available.isEmpty()) {
            Text(text("none_on_bone"), tags = listOf("insp-hint", "warning"))
            return@Column
        }
        val ticked = if (takesAll) available.toSet() else chosen.toSet()
        Text(
            if (takesAll) text("all") else "hollowengine.gui.inspector.colliders.some".lang(ticked.count { it in available }, available.size),
            tags = listOf("insp-hint"),
        )
        available.forEach { name ->
            key(name) { ColliderRow(name, name in ticked) { checked -> write(if (checked) ticked + name else ticked - name) } }
        }
        chosen.filterNot { it in available }.forEach { missing ->
            key(missing) { ColliderRow("hollowengine.gui.inspector.colliders.missing".lang(missing), true) { write(ticked - missing) } }
        }
    }
}

@Composable
private fun ColliderRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        tags = listOf("insp-check-row"),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .onClick { onToggle(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle, tags = listOf("insp-checkbox"))
        Text(label, tags = listOf("insp-check-label"), modifier = Modifier.grow(1f))
    }
}

private fun text(name: String): String = "hollowengine.gui.inspector.colliders.$name".lang
