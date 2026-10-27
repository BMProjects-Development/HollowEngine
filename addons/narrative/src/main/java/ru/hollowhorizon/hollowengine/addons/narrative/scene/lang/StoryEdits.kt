package ru.hollowhorizon.hollowengine.addons.narrative.scene.lang

/** The text after an edit, and the line the edited statement is on now. */
data class StoryEdit(val text: String, val line: Int)

/** Where a statement goes relative to another: [INTO] makes it the first one under it. */
enum class StoryPlacement { BEFORE, AFTER, INTO }

/**
 * Edits of a story's text by statements, for the editors that show it otherwise than as text. Only the lines an edit
 * touches change: everything else, line endings, indentation and comments included, is kept as it was written.
 */
object StoryEdits {
    private const val DEFAULT_INDENT = "    "

    /** Replaces [span], which lies on one line, with [replacement]. */
    fun replace(text: String, span: StorySpan, replacement: String): String {
        require('\n' !in replacement) { "A replacement must stay on its line" }
        return text.substring(0, span.start) + replacement + text.substring(span.end)
    }

    /** Writes [content] as the statement of [line], keeping its indentation and its trailing comment. */
    fun setStatement(text: String, line: Int, content: String): String {
        val lines = Lines(text)
        val raw = lines.raw(line)
        val indent = raw.takeWhile { it == ' ' || it == '\t' }
        val comment = StoryParser.parse(text).cst.lines[line].commentStart?.let { start ->
            val before = raw.substring(0, start)
            raw.substring(before.trimEnd().length)
        } ?: ""
        lines.set(line, indent + content + comment)
        return lines.print()
    }

    /** Inserts [statements] after the block of [line] at its level, or at the end of the file when it is null. */
    fun insertAfter(text: String, line: Int?, statements: List<String>): StoryEdit {
        val outline = StoryOutline.of(text)
        val anchor = line?.let(outline::blockAt)
        val lines = Lines(text)
        if (anchor == null) {
            val at = lines.append(statements)
            return StoryEdit(lines.print(), at)
        }
        val at = anchor.last + 1
        lines.insert(at, statements.map { anchor.line.indent + it })
        return StoryEdit(lines.print(), at)
    }

    /** Inserts [statements] as the last ones under the block of [line]. */
    fun insertInto(text: String, line: Int, statements: List<String>): StoryEdit {
        val outline = StoryOutline.of(text)
        val parent = requireNotNull(outline.blockAt(line)) { "No statement on line $line" }
        val lines = Lines(text)
        val indent = childIndent(outline, parent)
        val at = parent.last + 1
        lines.insert(at, statements.map { indent + it })
        return StoryEdit(lines.print(), at)
    }

    /** Removes the block of [line] with the comments above it, or with [whole] the card it is part of. */
    fun remove(text: String, line: Int, whole: Boolean = false): String {
        val (first, last) = covered(text, line, whole)
        val lines = Lines(text)
        lines.remove(first, last + 1)
        return lines.print()
    }

    /** Copies the block of [line], or with [whole] its card, right after it and returns where the copy is. */
    fun duplicate(text: String, line: Int, whole: Boolean = false): StoryEdit {
        val (first, last) = covered(text, line, whole)
        val lines = Lines(text)
        val copy = (first..last).map(lines::raw)
        lines.insert(last + 1, copy)
        return StoryEdit(lines.print(), last + 1 + (line - first))
    }

    /**
     * Moves the block of [line] past its neighbor. With [whole] it moves the card it is part of (an `@if` chain, a
     * group of `@choice`) past the neighboring card; without it, only past a neighbor in the same card. Null when
     * there is nothing to move past.
     */
    fun move(text: String, line: Int, up: Boolean, whole: Boolean): StoryEdit? {
        val outline = StoryOutline.of(text)
        val block = outline.blockAt(line) ?: return null
        val moving = if (whole) outline.groupOf(block) else listOf(block)
        val siblings = block.siblings
        val neighborIndex = if (up) siblings.indexOf(moving.first()) - 1 else siblings.indexOf(moving.last()) + 1
        val neighbor = siblings.getOrNull(neighborIndex) ?: return null
        val passed = if (whole) outline.groupOf(neighbor) else listOf(neighbor)
        if (!whole && neighbor !in outline.groupOf(block)) return null

        val (top, bottom) = if (up) passed to moving else moving to passed
        val lines = Lines(text)
        val upper = (top.first().first..top.last().last).map(lines::raw)
        val between = (top.last().last + 1 until bottom.first().first).map(lines::raw)
        val lower = (bottom.first().first..bottom.last().last).map(lines::raw)
        val start = top.first().first
        lines.remove(start, bottom.last().last + 1)
        lines.insert(start, lower + between + upper)

        val offset = line - moving.first().first
        val landed = if (up) start else start + lower.size + between.size
        return StoryEdit(lines.print(), landed + offset)
    }

    /**
     * Moves the block of [line], or with [whole] its card, to [placement] of the block of [target], indented to its
     * new level. Null when it cannot go there: into itself, or where it would split an `@if` chain or a menu.
     */
    fun relocate(text: String, line: Int, whole: Boolean, target: Int, placement: StoryPlacement): StoryEdit? {
        val outline = StoryOutline.of(text)
        if (!canRelocate(outline, line, whole, target, placement)) return null
        val block = outline.blockAt(line)!!
        val moving = if (whole) outline.groupOf(block) else listOf(block)
        val anchor = outline.blockAt(target)!!
        val (at, indent) = when (placement) {
            StoryPlacement.BEFORE -> anchor.first to anchor.line.indent
            StoryPlacement.AFTER -> anchor.last + 1 to anchor.line.indent
            StoryPlacement.INTO -> (anchor.children.firstOrNull()?.first ?: (anchor.last + 1)) to childIndent(outline, anchor)
        }
        val first = moving.first().first
        val last = moving.last().last
        val lines = Lines(text)
        val moved = (first..last).map(lines::raw)
        lines.remove(first, last + 1)
        val start = if (at > last) at - moved.size else at
        lines.insert(start, moved)
        reindent(lines, start, start + moved.size - 1, block.line.indent, indent)
        return StoryEdit(lines.print(), start + (line - first))
    }

    /** Whether [relocate] can put the block of [line] there. */
    fun canRelocate(outline: StoryOutline, line: Int, whole: Boolean, target: Int, placement: StoryPlacement): Boolean {
        val block = outline.blockAt(line) ?: return false
        val anchor = outline.blockAt(target) ?: return false
        val moving = if (whole) outline.groupOf(block) else listOf(block)
        if (target in moving.first().first..moving.last().last) return false
        val choice = moving.all { it.isChoice }
        val group = outline.groupOf(anchor)
        val index = group.indexOf(anchor)
        return when (placement) {
            StoryPlacement.INTO -> anchor.line.opensBlock
            StoryPlacement.BEFORE -> index == 0 || anchor.isChoice && choice
            StoryPlacement.AFTER -> index == group.lastIndex || anchor.isChoice && choice
        }
    }

    /**
     * Sets the argument [name] of the statement on [line] to [value] as written, adding it at the end when it is not
     * there and taking it out when [value] is blank.
     */
    fun setArgument(text: String, line: Int, name: String, value: String): String {
        val parsed = StoryParser.parse(text).cst.lines[line]
        val arg = arguments(parsed.kind).firstOrNull { it.name == name }
        return when {
            value.isBlank() && arg == null -> text
            value.isBlank() -> removeArgument(text, arg!!.span)
            arg != null -> replace(text, arg.expr.span, value)
            else -> insertAt(text, statementEnd(parsed), " $name=$value")
        }
    }

    /**
     * Sets the positional argument [index] of the statement on [line] to [value] as written. A missing one is added
     * after the positional ones there are, so it lands at [index] only when those before it are written.
     */
    fun setPositional(text: String, line: Int, index: Int, value: String): String {
        val parsed = StoryParser.parse(text).cst.lines[line]
        val args = arguments(parsed.kind)
        val positional = args.filter { it.name == null }
        positional.getOrNull(index)?.let { arg ->
            return if (value.isBlank()) removeArgument(text, arg.span) else replace(text, arg.expr.span, value)
        }
        if (value.isBlank()) return text
        val named = args.firstOrNull { it.name != null }
        return if (named != null) insertAt(text, named.span.start, "$value ") else insertAt(text, statementEnd(parsed), " $value")
    }

    private fun arguments(kind: StoryLineKind): List<StoryArg> = when (kind) {
        is StoryLineKind.FuncCall -> kind.args
        is StoryLineKind.Choice -> kind.args
        is StoryLineKind.Stage -> kind.args
        is StoryLineKind.CallDefine -> kind.args
        else -> emptyList()
    }

    /** Where the statement of [line] ends in the text: before its trailing spaces and comment. */
    private fun statementEnd(line: StoryLine): Int =
        line.offset + line.raw.substring(0, line.commentStart ?: line.raw.length).trimEnd().length

    private fun insertAt(text: String, offset: Int, inserted: String) = text.substring(0, offset) + inserted + text.substring(offset)

    /** Removes an argument with the spaces before it. */
    private fun removeArgument(text: String, span: StorySpan): String {
        var start = span.start
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        return text.substring(0, start) + text.substring(span.end)
    }

    /** Puts the card of [line] under the statement above it, when that one opens a block. Null when it cannot. */
    fun indent(text: String, line: Int): StoryEdit? {
        val outline = StoryOutline.of(text)
        val block = outline.blockAt(line) ?: return null
        val group = outline.groupOf(block)
        val above = block.siblings.getOrNull(block.siblings.indexOf(group.first()) - 1) ?: return null
        if (!above.line.opensBlock) return null
        val lines = Lines(text)
        reindent(lines, group.first().first, group.last().last, block.line.indent, childIndent(outline, above))
        return StoryEdit(lines.print(), line)
    }

    /** Takes the card of [line] out of the block it is in, to right after that block. Null at the top level. */
    fun outdent(text: String, line: Int): StoryEdit? {
        val outline = StoryOutline.of(text)
        val block = outline.blockAt(line) ?: return null
        val parent = block.parent ?: return null
        val group = outline.groupOf(block)
        val parentGroup = outline.groupOf(parent)
        val lines = Lines(text)
        val from = group.first().first
        val to = group.last().last
        val moved = (from..to).map(lines::raw)
        val after = parentGroup.last().last
        lines.insert(after + 1, moved)
        lines.remove(from, to + 1)
        val start = after + 1 - moved.size
        reindent(lines, start, start + moved.size - 1, block.line.indent, parent.line.indent)
        return StoryEdit(lines.print(), start + (line - from))
    }

    /** Renames the label [from] to [to], and every `@jump`/`@call` of this file that goes to it. */
    fun renameLabel(text: String, from: String, to: String): String {
        val replacements = StoryParser.parse(text).cst.lines.mapNotNull { line ->
            when (val kind = line.kind) {
                is StoryLineKind.Label -> kind.nameSpan.takeIf { kind.name == from }?.let { it to to }
                is StoryLineKind.Jump -> kind.target.takeIf { it.address == null && it.label == from }?.let { it.span to "#$to" }
                is StoryLineKind.Call -> kind.target.takeIf { it.address == null && it.label == from }?.let { it.span to "#$to" }
                else -> null
            }
        }
        return replacements.sortedByDescending { it.first.start }
            .fold(text) { result, (span, replacement) -> replace(result, span, replacement) }
    }

    /** The lines the block of [line] covers, or with [whole] the card it is part of. */
    private fun covered(text: String, line: Int, whole: Boolean): Pair<Int, Int> {
        val outline = StoryOutline.of(text)
        val block = requireNotNull(outline.blockAt(line)) { "No statement on line $line" }
        val blocks = if (whole) outline.groupOf(block) else listOf(block)
        return blocks.first().first to blocks.last().last
    }

    /** The indentation of the statements under [parent]: what they already use, or one step deeper. */
    private fun childIndent(outline: StoryOutline, parent: StoryBlock): String =
        parent.children.firstOrNull()?.line?.indent ?: (parent.line.indent + indentUnit(outline))

    /** The step of indentation the file uses. */
    private fun indentUnit(outline: StoryOutline): String {
        val nested = generateSequence(outline.roots.firstOrNull { it.children.isNotEmpty() }) { block ->
            block.children.firstOrNull { it.children.isNotEmpty() }
        }.firstOrNull() ?: return DEFAULT_INDENT
        return nested.children.first().line.indent.removePrefix(nested.line.indent).ifEmpty { DEFAULT_INDENT }
    }

    private fun reindent(lines: Lines, from: Int, to: Int, base: String, target: String) {
        for (index in from..to) {
            val raw = lines.raw(index)
            if (raw.isBlank()) continue
            lines.set(index, if (raw.startsWith(base)) target + raw.removePrefix(base) else target + raw.trimStart())
        }
    }

    /**
     * The lines of a text being edited. A line keeps its own ending; new lines take the one the file already uses,
     * and the file keeps ending with a newline or without one, as it did.
     */
    private class Lines(text: String) {
        private class Line(var raw: String, val eol: String)

        private val cst = StoryParser.parse(text).cst
        private val lines = cst.lines.mapTo(mutableListOf()) { Line(it.raw, it.eol) }
        private val eol = cst.lines.firstOrNull { it.eol.isNotEmpty() }?.eol ?: "\n"
        private val endsWithNewline = cst.lines.last().eol.isNotEmpty()

        fun raw(index: Int) = lines[index].raw

        fun set(index: Int, raw: String) {
            lines[index].raw = raw
        }

        fun insert(index: Int, raws: List<String>) {
            lines.addAll(index, raws.map { Line(it, eol) })
        }

        /** Adds [raws] at the end, in place of the only line of an empty file, and returns where they start. */
        fun append(raws: List<String>): Int {
            if (lines.size == 1 && lines[0].raw.isBlank()) lines.clear()
            val at = lines.size
            insert(at, raws)
            return at
        }

        fun remove(from: Int, to: Int) {
            lines.subList(from, to).clear()
        }

        fun print(): String = buildString {
            lines.forEachIndexed { index, line ->
                append(line.raw)
                if (index < lines.lastIndex || endsWithNewline) append(line.eol.ifEmpty { eol })
            }
        }
    }
}
