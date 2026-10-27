package ru.hollowhorizon.hollowengine.addons.narrative.scene.lang

/**
 * The statements of a story nested by indentation the way the compiler reads them, with the lines each one covers:
 * the comments written right above it, the statement and everything indented under it.
 */
class StoryOutline private constructor(val cst: StoryFileCst) {
    val roots: List<StoryBlock>

    private val byLine = HashMap<Int, StoryBlock>()

    init {
        val roots = mutableListOf<StoryBlock>()
        val openers = ArrayDeque<Pair<StoryBlock, Int>>()
        var comments: Int? = null
        for (line in cst.lines) {
            when (line.kind) {
                StoryLineKind.Blank -> comments = null
                StoryLineKind.CommentOnly -> if (comments == null) comments = line.index
                else -> {
                    val width = indentWidth(line.indent)
                    while (openers.isNotEmpty() && width <= openers.last().second) openers.removeLast()
                    val parent = openers.lastOrNull()?.first
                    val block = StoryBlock(line, parent, parent?.mutableChildren ?: roots, comments ?: line.index)
                    block.siblingList += block
                    byLine[line.index] = block
                    generateSequence(parent) { it.parent }.forEach { it.last = line.index }
                    if (line.opensBlock) openers.addLast(block to width)
                    comments = null
                }
            }
        }
        this.roots = roots
    }

    /** The block whose statement is on [line]. */
    fun blockAt(line: Int): StoryBlock? = byLine[line]

    /** The statements a card of the editors shows together: an `@if` with its `@elseif`/`@else`, a group of `@choice`. */
    fun groupOf(block: StoryBlock): List<StoryBlock> {
        val siblings = block.siblings
        var from = siblings.indexOf(block)
        var to = from
        if (block.isChoice) {
            while (from > 0 && siblings[from - 1].isChoice) from--
            while (to + 1 < siblings.size && siblings[to + 1].isChoice) to++
        } else if (block.continuesIf || block.line.kind is StoryLineKind.If) {
            while (siblings[from].continuesIf && from > 0) from--
            while (to + 1 < siblings.size && siblings[to + 1].continuesIf) to++
        }
        return siblings.subList(from, to + 1)
    }

    companion object {
        fun of(text: String) = StoryOutline(StoryParser.parse(text).cst)

        fun of(cst: StoryFileCst) = StoryOutline(cst)

        internal fun indentWidth(indent: String): Int = indent.sumOf { if (it == '\t') 4 else 1 }
    }
}

/** One statement of a story and the statements indented under it. */
class StoryBlock internal constructor(
    val line: StoryLine,
    val parent: StoryBlock?,
    internal val siblingList: MutableList<StoryBlock>,
    val first: Int,
) {
    internal val mutableChildren = mutableListOf<StoryBlock>()

    val children: List<StoryBlock> get() = mutableChildren

    /** The blocks of the same parent, this one included. */
    val siblings: List<StoryBlock> get() = siblingList

    /** The last line the block covers: its statement, or the last statement under it. */
    var last: Int = line.index
        internal set

    internal val isChoice get() = line.kind is StoryLineKind.Choice

    internal val continuesIf get() = line.kind is StoryLineKind.ElseIf || line.kind == StoryLineKind.Else
}
