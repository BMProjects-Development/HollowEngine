package ru.hollowhorizon.hollowengine.client.ui.inspector

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.utils.lang

val LocalEditorBones = staticCompositionLocalOf { emptyList<String>() }

/** The IK targets of the rig being edited, by name, for the fields that pick one. */
val LocalEditorRigTargets = staticCompositionLocalOf { emptyList<String>() }

@Composable
internal fun BoneField(label: String?, description: String?, value: String, onChange: (String) -> Unit) =
    NamePickerField(label, description, value, LocalEditorBones.current, "bone", onChange)

@Composable
internal fun RigTargetField(label: String?, description: String?, value: String, onChange: (String) -> Unit) =
    NamePickerField(label, description, value, LocalEditorRigTargets.current, "target", onChange)

/**
 * Picks one of [names] in a searchable popup, or none. [kind] names the lang keys of its texts,
 * `hollowengine.gui.inspector.<kind>.*`.
 */
@Composable
private fun NamePickerField(
    label: String?,
    description: String?,
    value: String,
    names: List<String>,
    kind: String,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }

    Column(tags = listOf("insp-field")) {
        FieldLabel(label, description)
        Row(
            tags = listOf("insp-bone", if (value.isBlank()) "empty" else "set"),
            modifier = Modifier.size(100.percent, 22.px)
                .input(hoverable = true, clickable = true)
                .cursor(UiCursorShape.HAND)
                .alignItems(vertical = UiAlign.CENTER)
                .onPlaced { anchor = it }
                .onClick { event ->
                    open = !open
                    event.consume()
                },
        ) {
            Text(
                value.ifBlank { pickerText(kind, "none") },
                tags = listOf("insp-bone-label"),
                modifier = Modifier.grow(1f),
            )
            Box(tags = listOf("dropdown-button-arrow"))
        }
    }

    if (open) NamePopup(anchor, names, value, kind, onDismiss = { open = false }) { picked ->
        open = false
        onChange(picked)
    }
}

@Composable
private fun NamePopup(
    anchor: UiRect,
    names: List<String>,
    selected: String,
    kind: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(names, query) {
        if (query.isBlank()) names else names.filter { it.contains(query, ignoreCase = true) }
    }

    Popup(
        anchorBounds = anchor,
        id = "insp-bone-popup",
        tags = listOf("dropdown-popup", "insp-bone-popup"),
        onDismiss = onDismiss,
    ) {
        TextField(
            value = query,
            id = "insp-bone-search",
            placeholder = pickerText(kind, "search"),
            fontSize = 9f,
            onChange = { query = it },
            tags = listOf("insp-input"),
            modifier = Modifier.size(100.percent, 20.px),
        )

        Column(
            tags = listOf("insp-bone-list"),
            modifier = Modifier.scrollable(horizontal = false),
        ) {
            if (selected.isNotBlank()) PickerRow(pickerText(kind, "clear"), selected = false) { onPick("") }
            matches.forEach { name ->
                key(name) { PickerRow(name, selected = name == selected) { onPick(name) } }
            }
            if (matches.isEmpty()) Text(pickerText(kind, "no_matches"), tags = listOf("insp-hint"))
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        tags = if (selected) listOf("dropdown-item", "selected") else listOf("dropdown-item"),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .alignItems(vertical = UiAlign.CENTER)
            .onClick { event ->
                onPick()
                event.consume()
            },
    ) {
        Text(label, tags = listOf("dropdown-item-label"))
    }
}

private fun pickerText(kind: String, name: String): String = "hollowengine.gui.inspector.$kind.$name".lang
