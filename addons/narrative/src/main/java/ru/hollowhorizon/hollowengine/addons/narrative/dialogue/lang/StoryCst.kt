package ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang

/**
 * Lossless syntax tree of one `.story` file.
 *
 * The tree is line-based: every physical line keeps its raw text (indent, spacing and trailing
 * comment included), so [print] reproduces the source byte-for-byte.
 */
class StoryFileCst(
    val source: String,
    val lines: List<StoryLine>,
) {
    fun print(): String = buildString(source.length) {
        for (line in lines) {
            append(line.raw)
            append(line.eol)
        }
    }
}

/**
 * One physical line. [raw] excludes the terminator; [eol] is `"\n"`, `"\r\n"` or empty on the last
 * line. [commentStart] is the offset of `//` inside [raw], if a comment is present.
 */
class StoryLine(
    val index: Int,
    val offset: Int,
    val raw: String,
    val eol: String,
    val indent: String,
    val kind: StoryLineKind,
    val commentStart: Int? = null,
) {
    val comment: String? get() = commentStart?.let { raw.substring(it) }

    /** True for lines that own an indented block underneath (`@if`, `@choice`, bare `@async`…). */
    val opensBlock: Boolean
        get() = when (val k = kind) {
            is StoryLineKind.If, is StoryLineKind.ElseIf, is StoryLineKind.Else,
            is StoryLineKind.While, is StoryLineKind.Choice,
                -> true

            is StoryLineKind.Async -> k.inline == null
            else -> false
        }
}

/** Reference to a story position: `#Label`, `project:stories/other.story#Label` or a bare address. */
data class StoryRef(val address: String?, val label: String?, val span: StorySpan) {
    init {
        require(address != null || label != null) { "Empty story reference" }
    }

    override fun toString() = buildString {
        address?.let { append(it) }
        label?.let { append('#').append(it) }
    }
}

/** One argument of a command: positional (`name` == null) or named (`volume=1.0`). */
data class StoryArg(
    val name: String?,
    val nameSpan: StorySpan?,
    val expr: StoryExpression,
    val span: StorySpan,
)

sealed interface StoryLineKind {
    /** Empty or whitespace-only line. */
    data object Blank : StoryLineKind

    /** A line holding nothing but a `//` comment. */
    data object CommentOnly : StoryLineKind

    /** The parser could not make sense of the line; [message] mirrors the emitted diagnostic. */
    data class Broken(val message: String) : StoryLineKind

    data class Label(val name: String, val nameSpan: StorySpan) : StoryLineKind

    data class If(val condition: StoryExpression) : StoryLineKind
    data class ElseIf(val condition: StoryExpression) : StoryLineKind
    data object Else : StoryLineKind
    data class While(val condition: StoryExpression) : StoryLineKind
    data class Set(val variable: String, val variableSpan: StorySpan, val value: StoryExpression) : StoryLineKind

    data class Jump(val target: StoryRef) : StoryLineKind
    data class Call(val target: StoryRef) : StoryLineKind
    data object Return : StoryLineKind

    data class Choice(
        val text: TextTemplate,
        val args: List<StoryArg>,
    ) : StoryLineKind

    /**
     * `@async` opening a block ([inline] == null), or `@async <command>` running a single [inline]
     * command on the new track. [trackName] comes from a leading `name=` argument.
     */
    data class Async(val trackName: String?, val inline: FuncCall?) : StoryLineKind

    /** `@await` with no names waits for every live track. */
    data class Await(val trackNames: List<String>) : StoryLineKind
    data class Cancel(val trackName: String, val nameSpan: StorySpan) : StoryLineKind
    data object Sync : StoryLineKind

    /**
     * `@command <vanilla command>`: the tail goes to the server as-is except for `{var}`
     * interpolation. Brackets stay literal so entity selectors (`@e[tag=x]`) survive.
     */
    data class Command(val text: TextTemplate, val textSpan: StorySpan) : StoryLineKind

    /** Any `@name args` that is not a built-in form: a call into the function registry. */
    data class FuncCall(
        val function: String,
        val functionSpan: StorySpan,
        val args: List<StoryArg>,
    ) : StoryLineKind

    /**
     * `Vitalik: Hello!`, `{player}: Hello!` or narrator text.
     *
     * [speaker] is the name written before the colon and [speakerExpr] the `{...}` form of it; both
     * are null for narration, and never both set. [speakerSpan] covers whichever one was written.
     */
    data class Dialogue(
        val speaker: String?,
        val speakerSpan: StorySpan?,
        val text: TextTemplate,
        val speakerExpr: StoryExpression? = null,
    ) : StoryLineKind
}

/** Text with variable expressions, inline commands, etc. */
data class TextTemplate(val parts: List<TextPart>, val span: StorySpan) {
    fun literalText(): String = parts.filterIsInstance<TextPart.Literal>().joinToString("") { it.text }
}

sealed interface TextPart {
    val span: StorySpan

    data class Literal(val text: String, override val span: StorySpan) : TextPart
    data class Interpolation(val expr: StoryExpression, override val span: StorySpan) : TextPart
    data class InlineCall(val call: StoryLineKind.FuncCall, override val span: StorySpan) : TextPart
    data class WaitInput(override val span: StorySpan) : TextPart
}
