package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.TextField
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.inspector.CollapsibleSection
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.TextRow

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"

/** The signature of [function]: its name, then its parameters, results and flows, a folding section each. */
@Composable
internal fun FunctionInspector(state: StoryEditorState, function: StoryNode.Function) {
    val signature = StorySignatureEditor(state, function)
    TextRow(
        storyLang("function.name"),
        function.name,
        id = "st-${function.id}-name",
        help = storyLang("function.open"),
        onChange = signature::rename,
    )
    StorySignaturePart.entries.forEach { part ->
        val entries = signature.entries(part)
        CollapsibleSection(
            "${part.heading} (${entries.size})",
            signature.isOpen(part),
            id = "st-${function.id}-${part.key}",
            onToggle = { signature.toggle(part) },
        ) {
            entries.forEach { entry -> key(entry.index) { EntryRow(state, "st-${function.id}-${part.key}", entry) } }
            InspectorButton(part.addLabel, AddIcon) { signature.add(part) }
        }
    }
}

/** An entry of a signature on one line: its name, its type when it has one, and the button that removes it. */
@Composable
private fun EntryRow(state: StoryEditorState, id: String, entry: StorySignatureEntry) {
    Row(tags = listOf("insp-input-row", "st-signature-entry")) {
        TextField(
            value = entry.name,
            id = "$id-name-${entry.index}",
            fontSize = 9f,
            onChange = entry.rename,
            tags = listOf("insp-input"),
            modifier = Modifier.grow(1f),
        )
        entry.type?.let { type ->
            TextField(
                value = type,
                id = "$id-type-${entry.index}",
                insight = StoryInsight.type(state),
                placeholder = "Int",
                fontSize = 9f,
                onChange = entry.retype,
                tags = listOf("insp-input"),
                modifier = Modifier.grow(1f),
            )
        }
        InspectorIconButton(RemoveIcon, storyLang("function.value.remove"), id = "$id-remove-${entry.index}", onClick = entry.remove)
    }
}
