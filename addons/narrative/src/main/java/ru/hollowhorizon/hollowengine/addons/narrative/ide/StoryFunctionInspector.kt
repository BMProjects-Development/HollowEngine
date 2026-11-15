package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.client.ui.*
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
        val reorder = remember(function.id, part) { StoryReorder() }
        CollapsibleSection(
            "${part.heading} (${entries.size})",
            signature.isOpen(part),
            id = "st-${function.id}-${part.key}",
            onToggle = { signature.toggle(part) },
        ) {
            entries.forEach { entry ->
                key(entry.index) {
                    EntryRow(state, "st-${function.id}-${part.key}", entry, entries.size, reorder) { from, to ->
                        signature.reorder(part, from, to)
                    }
                }
            }
            InspectorButton(part.addLabel, AddIcon) { signature.add(part) }
        }
    }
}

/** An entry of a signature on one line: its name, its type when it has one, and the button that removes it. */
@Composable
private fun EntryRow(
    state: StoryEditorState, id: String, entry: StorySignatureEntry, count: Int,
    reorder: StoryReorder, move: (Int, Int) -> Unit,
) {
    var height by remember { mutableFloatStateOf(22f) }
    val spacing = height + 3f
    val modifier = Modifier.onPlaced { height = it.height }.reorderOffset(reorder, entry.index, spacing)
    Row(id = "$id-row-${entry.index}", tags = listOf("insp-input-row", "st-signature-entry"), modifier = modifier) {
        StoryReorderHandle(
            "$id-reorder-${entry.index}",
            entry.index,
            count,
            spacing,
            1f,
            reorder,
            state.context?.readOnly != true,
            id,
            move
        )
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
        InspectorIconButton(
            RemoveIcon,
            storyLang("function.value.remove"),
            id = "$id-remove-${entry.index}",
            onClick = entry.remove
        )
    }
}
