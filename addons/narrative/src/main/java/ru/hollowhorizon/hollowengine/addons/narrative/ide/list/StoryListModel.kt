package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryScriptingAnalyzer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.StoryString
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.treeGuideContinuations

/** A story as the list shows it: its statements by card and what the compiler says about each line. */
internal class StoryListModel(address: String, val text: String) {
    private val compiled = StoryCompiler.compile(address, text, StoryScriptingAnalyzer.catalog())

    val outline = StoryOutline.of(compiled.cst)

    val problems: Map<Int, List<StoryDiagnostic>> = compiled.diagnostics.groupBy { it.span.line }

    /**
     * A name for each statement that outlives edits elsewhere in the file, for what is folded: its text and how many
     * of the same come before it.
     */
    val keys: Map<Int, String> = HashMap<String, Int>().let { seen ->
        compiled.cst.lines.filter { outline.blockAt(it.index) != null }.associate { line ->
            val statement = statement(line)
            val count = seen.merge(statement, 1, Int::plus)!!
            line.index to "$statement#$count"
        }
    }

    /** [blocks] split into the cards that show them: an `@if` chain and a menu of `@choice` are one card each. */
    fun cards(blocks: List<StoryBlock>): List<List<StoryBlock>> {
        val cards = mutableListOf<List<StoryBlock>>()
        var index = 0
        while (index < blocks.size) {
            val group = outline.groupOf(blocks[index])
            cards += group
            index += group.size
        }
        return cards
    }

    /**
     * The rows of the list, top to bottom, leaving out what is folded away: a row per statement, and a head above
     * each menu of options, which is what a whole menu is selected, moved and folded by.
     */
    fun rows(folded: (String) -> Boolean): List<StoryRow> {
        val rows = openRows(folded)
        val continues = treeGuideContinuations(rows.map { it.guides.size })
        return rows.mapIndexed { index, row -> row.withJoints(StoryGuideJoint.of(continues[index])) }
    }

    private fun openRows(folded: (String) -> Boolean): List<StoryRow> = buildList {
        fun walk(blocks: List<StoryBlock>, guides: List<StoryCardKind>) {
            val top = guides.isEmpty()
            for (card in cards(blocks)) {
                val first = card.first()
                when (val kind = StoryCardKind.of(first)) {
                    StoryCardKind.CHOICE -> {
                        add(StoryRow(first, StoryRowRole.MENU, kind, guides, dot = top))
                        if (folded(cardKey(first))) continue
                        for (option in card) {
                            add(StoryRow(option, StoryRowRole.OPTION, kind, guides + kind, dot = false))
                            if (!folded(rowKey(option))) walk(option.children, guides + kind + kind)
                        }
                    }

                    StoryCardKind.CONDITION -> card.forEachIndexed { index, branch ->
                        add(StoryRow(branch, StoryRowRole.BRANCH, kind, guides, dot = top && index == 0))
                        if (!folded(rowKey(branch))) walk(branch.children, guides + kind)
                    }

                    else -> {
                        add(StoryRow(first, StoryRowRole.CARD, kind, guides, dot = top))
                        if (!folded(rowKey(first))) walk(first.children, guides + kind)
                    }
                }
            }
        }
        walk(outline.roots, emptyList())
    }

    /** The statements whose text holds [query], in the order of the file. */
    fun matches(query: String): List<StorySelection> {
        if (query.isBlank()) return emptyList()
        return outline.cst.lines.filter { line ->
            outline.blockAt(line.index) != null && statement(line).contains(query, ignoreCase = true)
        }.map { StorySelection(it.index) }
    }

    /** The fold keys that hide [selection]: those of the blocks around it and of the menus they are in. */
    fun foldsOver(selection: StorySelection): List<String> {
        val block = outline.blockAt(selection.line) ?: return emptyList()
        val around = generateSequence(block.parent) { it.parent }.toList()
        val menus = (around + block).filter { it.line.kind is StoryLineKind.Choice }
            .map { cardKey(outline.groupOf(it).first()) }
        return around.map(::rowKey) + if (selection.menu) menus.dropLast(1) else menus
    }

    fun cardKey(block: StoryBlock) = "card:" + keys.getValue(block.line.index)

    /** The fold key of the block under [block]; an option has its own, which starts folded. */
    fun rowKey(block: StoryBlock) =
        (if (block.line.kind is StoryLineKind.Choice) OptionFold else "row:") + keys.getValue(block.line.index)

    fun source(span: StorySpan): String = text.substring(span.start, span.end)

    /** What a phrase shows as its speaker: the name, or the text of a quoted one, or the `{expression}` as written. */
    fun speaker(kind: StoryLineKind.Dialogue): String? =
        kind.speaker ?: (kind.speakerExpr?.constant as? StoryString)?.value ?: kind.speakerSpan?.let(::source)

    companion object {
        const val OptionFold = "option:"

        /** The statement of [line] as written, without its indentation and comment. */
        fun statement(line: StoryLine): String =
            line.raw.substring(line.indent.length, line.commentStart ?: line.raw.length).trimEnd()
    }
}

/** What is selected: a statement, or with [menu] the head of the menu its option starts. */
internal data class StorySelection(val line: Int, val menu: Boolean = false)

internal enum class StoryRowRole { CARD, MENU, OPTION, BRANCH }

/**
 * One row of the list: a statement, or the head of a menu. [guides] are the kinds of the cards it is nested in,
 * outermost first, a guide each, drawn as [joints] say; [dot] marks the head of a card of the top level on the rail.
 */
internal class StoryRow(
    val block: StoryBlock,
    val role: StoryRowRole,
    val kind: StoryCardKind,
    val guides: List<StoryCardKind>,
    val dot: Boolean,
    val joints: List<StoryGuideJoint?> = emptyList(),
) {
    val line: Int get() = block.line.index
    val selection = StorySelection(block.line.index, menu = role == StoryRowRole.MENU)
    val key: String get() = "$line:$role"

    fun withJoints(joints: List<StoryGuideJoint?>) = StoryRow(block, role, kind, guides, dot, joints)
}

/** What a guide draws in a row, as the lines of a tree: on past it, into it and on, or into it as the last. */
internal enum class StoryGuideJoint(val tag: String) {
    LINE("line"), BRANCH("branch"), LAST("last");

    companion object {
        /** The joints of a row whose guides go on below it where [continues] says. */
        fun of(continues: BooleanArray): List<StoryGuideJoint?> = continues.indices.map { level ->
            when {
                level == continues.lastIndex -> if (continues[level]) BRANCH else LAST
                continues[level] -> LINE
                else -> null
            }
        }
    }
}

/** What a card is about, which decides how it looks and what the inspector edits in it. */
internal enum class StoryCardKind(val tag: String) {
    PHRASE("phrase"), WAIT("wait"), CHOICE("choice"), CONDITION("condition"), LABEL("label"), BLOCK("block"), COMMAND("command"), BROKEN(
        "broken"
    );

    val title: String get() = storyLang("kind.$tag")

    companion object {
        const val WAIT_FUNCTION = "wait"

        fun of(block: StoryBlock): StoryCardKind = when (val kind = block.line.kind) {
            is StoryLineKind.Dialogue -> PHRASE
            is StoryLineKind.FuncCall -> if (kind.function == WAIT_FUNCTION) WAIT else COMMAND
            is StoryLineKind.Choice -> CHOICE
            is StoryLineKind.If, is StoryLineKind.ElseIf, StoryLineKind.Else -> CONDITION
            is StoryLineKind.Label -> LABEL
            is StoryLineKind.Broken -> BROKEN
            else -> if (block.line.opensBlock) BLOCK else COMMAND
        }
    }
}

/** The time of a `@wait`, read and written in seconds when it is a plain duration such as `2s` or `500ms`. */
internal object WaitDuration {
    private val PLAIN = Regex("""(\d+(?:\.\d+)?)(ms|s|sec|min|h)""")

    fun seconds(written: String): Float? {
        val match = PLAIN.matchEntire(written.trim()) ?: return null
        val value = match.groupValues[1].toFloat()
        return when (match.groupValues[2]) {
            "ms" -> value / 1000f
            "min" -> value * 60f
            "h" -> value * 3600f
            else -> value
        }
    }

    fun write(seconds: Float): String = seconds.toString().removeSuffix(".0") + "s"
}
