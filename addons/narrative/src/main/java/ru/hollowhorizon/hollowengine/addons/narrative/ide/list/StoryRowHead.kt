package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang

/** The band at the start of a row that says what the row is: an icon and a name, and for a command what it does. */
internal class StoryHead(val icon: String, val title: String, val tooltip: String? = null) {
    companion object {
        /** The head of [row], or null for an option, which starts with its number instead. */
        fun of(row: StoryRow): StoryHead? = when (row.role) {
            StoryRowRole.OPTION -> null
            StoryRowRole.MENU -> StoryHead(row.kind.icon, row.kind.title)
            StoryRowRole.BRANCH -> StoryHead(row.kind.icon, storyLang("branch.${branchKeyword(row.block.line.kind)}"))
            StoryRowRole.CARD -> when (val kind = row.block.line.kind) {
                is StoryLineKind.FuncCall -> command(kind)
                is StoryLineKind.Dialogue, is StoryLineKind.Label, is StoryLineKind.Broken -> plain(row)
                else -> StoryHead(row.kind.icon, statementKeyword(row) ?: row.kind.title)
            }
        }

        private fun plain(row: StoryRow) = StoryHead(row.kind.icon, row.kind.title)

        /** A command by what its action says of itself, or by its name where the action says nothing. */
        private fun command(call: StoryLineKind.FuncCall): StoryHead {
            val signature = signature(call)
            val name = "@${call.function}"
            val title = signature?.title?.lang
            val tooltip = listOfNotNull(name.takeIf { title != null }, signature?.description?.lang)
            return StoryHead(
                signature?.icon ?: StoryCardKind.COMMAND.icon,
                title ?: name,
                tooltip.joinToString("\n").ifEmpty { null },
            )
        }
    }
}

@Composable
internal fun StoryRowHead(row: StoryRow, head: StoryHead) {
    Row(
        tags = listOf("story-head", row.tag),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER)
            .let { modifier -> head.tooltip?.let { modifier.tooltipOnHover(it) } ?: modifier },
    ) {
        Image(head.icon, tags = listOf("story-head-icon"))
        Text(head.title, tags = listOf("story-head-title"))
    }
}

/** The keyword of a branch of an `@if` chain: `if`, `elseif` or `else`. */
internal fun branchKeyword(kind: StoryLineKind) = when (kind) {
    is StoryLineKind.If -> "if"
    is StoryLineKind.ElseIf -> "elseif"
    else -> "else"
}

/** The `@keyword` a statement of no row of its own starts with, or null when it starts with none. */
internal fun statementKeyword(row: StoryRow): String? = StoryListModel.statement(row.block.line).substringBefore(' ')
    .takeIf { it.startsWith("@") && row.kind != StoryCardKind.BROKEN }
