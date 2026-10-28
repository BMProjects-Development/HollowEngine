package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*

/**
 * A field of a built-in statement.
 *
 * @param required whether the statement cannot do without it; such a field cleared leaves the file as it was.
 * @param slot the type of value it takes when it is an expression, which a variable or a command can fill.
 * @param expression the expression it is in the statement, for a [slot].
 */
internal class StoryBuiltinField(
    val name: String,
    val required: Boolean = true,
    val slot: StoryType? = null,
    val expression: (StoryLineKind) -> StoryExpression? = { null },
)

/**
 * A built-in statement, such as `@set` or `@timeout`, as the list edits it: a field per part of it, read from the
 * statement and written back whole.
 *
 * @param id what its texts are found by, `hollowengine.story.command.<id>`; the keyword unless two forms share one.
 * @param keyword what it is written with, without the `@`.
 * @param category the heading the add menu lists it under, the name of a `hollowengine.gui.ide.story.category` key.
 * @param template the statement adding it writes.
 * @param reader what each part says now, the fields first; the parts after them are kept as they are.
 * @param write the statement of the parts.
 */
internal class StoryBuiltin(
    val id: String,
    val keyword: String,
    val icon: String,
    val category: String,
    val template: String,
    val fields: List<StoryBuiltinField>,
    private val reader: StoryListModel.(StoryLineKind) -> List<String>,
    val write: (List<String>) -> String,
) {
    fun read(model: StoryListModel, kind: StoryLineKind): List<String> = model.reader(kind).map(String::trim)

    /** The statement with the part [index] set to [value], or null when that leaves a required part empty. */
    fun with(model: StoryListModel, kind: StoryLineKind, index: Int, value: String): String? {
        if (fields.getOrNull(index)?.required == true && value.isBlank()) return null
        val parts = read(model, kind).toMutableList()
        parts[index] = value.trim()
        return write(parts)
    }
}

/** Every built-in statement the list draws fields for. `@if` and `@choice` have rows of their own. */
internal object StoryBuiltins {
    private val SET = builtin(
        "set", "command", "variables", "@set x = 0",
        listOf(field("variable"), slot("value", StoryType.ANY) { (it as StoryLineKind.Set).value }),
        read = { listOf((it as StoryLineKind.Set).variable, source(it.value.span)) },
        write = { "@set ${it[0]} = ${it[1]}" },
    )
    private val WHILE = builtin(
        "while", "condition", "flow", "@while true",
        listOf(slot("condition", StoryType.BOOL) { (it as StoryLineKind.While).condition }),
        read = { listOf(source((it as StoryLineKind.While).condition.span)) },
        write = { "@while ${it[0]}" },
    )
    private val JUMP = builtin(
        "jump", "label", "flow", "@jump #label",
        listOf(field("target")),
        read = { listOf(source((it as StoryLineKind.Jump).target.span)) },
        write = { "@jump ${it[0]}" },
    )
    private val CALL = builtin(
        "call", "label", "flow", "@call #label",
        listOf(field("target")),
        read = { listOf(source((it as StoryLineKind.Call).target.span)) },
        write = { "@call ${it[0]}" },
    )
    private val CALL_DEFINE = builtin(
        "call-define", "label", "flow", "@call name()",
        listOf(field("name"), field("args", required = false), field("result", required = false)),
        read = {
            val call = it as StoryLineKind.CallDefine
            listOf(call.name, arguments(call.args), call.resultSpan?.let(::source).orEmpty())
        },
        write = { "@call ${it[0]}(${it[1]})" + optional(" result=", it[2]) },
        keyword = "call",
    )
    private val RETURN = builtin(
        "return", "label", "flow", "@return",
        listOf(slot("value", StoryType.ANY, required = false) { (it as StoryLineKind.Return).value }),
        read = { listOf((it as StoryLineKind.Return).value?.span?.let(::source).orEmpty()) },
        write = { "@return" + optional(" ", it[0]) },
    )
    private val DEFINE = builtin(
        "define", "block", "flow", "@define name()",
        listOf(field("name"), field("params", required = false)),
        read = { listOf((it as StoryLineKind.Define).name, it.params.joinToString(", ")) },
        write = { "@define ${it[0]}(${it[1]})" },
    )
    private val IMPORT = builtin(
        "import", "label", "flow", "@import other.story",
        listOf(field("story")),
        read = { listOf(source((it as StoryLineKind.Import).addressSpan)) },
        write = { "@import ${it[0]}" },
    )
    private val STAGE = builtin(
        "stage", "label", "flow", "@stage script.kts#stage",
        listOf(field("target"), field("args", required = false)),
        read = { listOf(source((it as StoryLineKind.Stage).targetSpan), arguments(it.args)) },
        write = { "@stage ${it[0]}" + optional(" ", it[1]) },
    )
    private val ASYNC = builtin(
        "async", "block", "tracks", "@async",
        listOf(field("track", required = false)),
        read = {
            val inline = (it as StoryLineKind.Async).inline
            listOf(
                it.trackName.orEmpty(),
                inline?.let { call -> "@${call.function}" + optional(" ", arguments(call.args)) }.orEmpty()
            )
        },
        write = { "@async" + optional(" name=", it[0]) + optional(" ", it[1]) },
    )
    private val AWAIT = builtin(
        "await", "command", "tracks", "@await",
        listOf(field("tracks", required = false)),
        read = { listOf((it as StoryLineKind.Await).trackNames.joinToString(" ")) },
        write = { "@await" + optional(" ", it[0]) },
    )
    private val CANCEL = builtin(
        "cancel", "command", "tracks", "@cancel track",
        listOf(field("track")),
        read = { listOf((it as StoryLineKind.Cancel).trackName) },
        write = { "@cancel ${it[0]}" },
    )
    private val TIMEOUT = builtin(
        "timeout", "block", "tracks", "@timeout 5s",
        listOf(slot("time", StoryType.NUMBER) { (it as StoryLineKind.Timeout).duration }),
        read = { listOf(source((it as StoryLineKind.Timeout).duration.span)) },
        write = { "@timeout ${it[0]}" },
    )
    private val CHECKPOINT = builtin(
        "checkpoint", "label", "story", "@checkpoint name",
        listOf(field("name")),
        read = { listOf((it as StoryLineKind.Checkpoint).name) },
        write = { "@checkpoint ${it[0]}" },
    )
    private val REWIND = builtin(
        "rewind", "label", "story", "@rewind",
        listOf(field("checkpoint", required = false)),
        read = { listOf((it as StoryLineKind.Rewind).name.orEmpty()) },
        write = { "@rewind" + optional(" ", it[0]) },
    )
    private val TEMPORARY = builtin(
        "temporary", "block", "story", "@temporary",
        listOf(field("until", required = false)),
        read = { listOf((it as StoryLineKind.Temporary).until.orEmpty()) },
        write = { "@temporary" + optional(" until=", it[0]) },
    )
    private val COMMAND = builtin(
        "command", "command", "world", "@command time set day",
        listOf(field("text")),
        read = { listOf(source((it as StoryLineKind.Command).textSpan)) },
        write = { "@command ${it[0]}" },
    )

    /** In the order the add menu lists them. */
    val all: List<StoryBuiltin> = listOf(
        SET, WHILE, JUMP, CALL, CALL_DEFINE, RETURN, DEFINE, IMPORT, STAGE,
        ASYNC, AWAIT, CANCEL, TIMEOUT, CHECKPOINT, REWIND, TEMPORARY, COMMAND,
    )

    /** The built-in statement [kind] is, or null for a command, a phrase or a row of its own. */
    fun of(kind: StoryLineKind): StoryBuiltin? = when (kind) {
        is StoryLineKind.Set -> SET
        is StoryLineKind.While -> WHILE
        is StoryLineKind.Jump -> JUMP
        is StoryLineKind.Call -> CALL
        is StoryLineKind.CallDefine -> CALL_DEFINE
        is StoryLineKind.Return -> RETURN
        is StoryLineKind.Define -> DEFINE
        is StoryLineKind.Import -> IMPORT
        is StoryLineKind.Stage -> STAGE
        is StoryLineKind.Async -> ASYNC
        is StoryLineKind.Await -> AWAIT
        is StoryLineKind.Cancel -> CANCEL
        is StoryLineKind.Timeout -> TIMEOUT
        is StoryLineKind.Checkpoint -> CHECKPOINT
        is StoryLineKind.Rewind -> REWIND
        is StoryLineKind.Temporary -> TEMPORARY
        is StoryLineKind.Command -> COMMAND
        else -> null
    }

    private fun builtin(
        id: String,
        icon: String,
        category: String,
        template: String,
        fields: List<StoryBuiltinField>,
        read: StoryListModel.(StoryLineKind) -> List<String>,
        write: (List<String>) -> String,
        keyword: String = id,
    ) = StoryBuiltin(id, keyword, storyIcon(icon), category, template, fields, read, write)

    private fun field(name: String, required: Boolean = true) = StoryBuiltinField(name, required)

    private fun slot(
        name: String,
        type: StoryType,
        required: Boolean = true,
        expression: (StoryLineKind) -> StoryExpression?,
    ) = StoryBuiltinField(name, required, type, expression)

    private fun optional(prefix: String, value: String) = if (value.isBlank()) "" else prefix + value

    /** The arguments as written, from the first to the last. */
    private fun StoryListModel.arguments(args: List<StoryArg>): String {
        val first = args.firstOrNull() ?: return ""
        return source(StorySpan(first.span.start, args.last().span.end, first.span.line))
    }
}
