package ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.*
import ru.hollowhorizon.hollowengine.common.utils.expressions.*

/** Where a name in an expression comes from: the session's variables, then its actors. */
fun interface StoryVariables {
    fun get(name: String): StoryValue?
}

class StoryParseException(message: String, val span: StorySpan) : RuntimeException(message)

/** Thrown by [StoryExpression.evaluate] for type errors; carries the span of the failing expression. */
class StoryEvalException(message: String, val span: StorySpan) : RuntimeException(message)

/** A failure inside the language, turned into a [StoryEvalException] with a span by [StoryExpression]. */
private class StoryValueError(message: String) : RuntimeException(message)

/** Milliseconds of a duration literal suffix (`1s`, `500ms`, `2min`, `1h`). */
private val DURATION_SUFFIXES = mapOf(
    "ms" to 1f,
    "s" to 1_000f,
    "sec" to 1_000f,
    "min" to 60_000f,
    "h" to 3_600_000f,
)

/**
 * The dialogue declarations of the expression language.
 *
 * Unlike the animation declarations it is dynamically typed: a variable holds whatever was last put in it, so
 * every value is a [StoryValue] and what `+` means is decided when it runs.
 */
val StoryDeclarations: Declarations<StoryVariables> = Declarations {
    val value = reference<StoryValue>(
        name = "value",
        jvmClass = StoryValue::class.java,
        truthy = { (it as StoryValue).isTruthy() },
        number = { value ->
            (value as? StoryNumber)?.value
                ?: throw StoryValueError("Expected a number, got ${value.story().typeName()}")
        },
    ) { self ->
        field("size", self) { value ->
            when (value) {
                is StoryList -> StoryNumber(value.values.size.toFloat())
                is StoryString -> StoryNumber(value.value.length.toFloat())
                else -> throw StoryValueError("A ${value.typeName()} has no 'size'")
            }
        }
        field("name", self) { value ->
            (value as? StoryActor)?.let { StoryString(it.character.name) }
                ?: throw StoryValueError("A ${value.typeName()} has no 'name'")
        }
        field("uuid", self) { value ->
            val actor = value as? StoryActor ?: throw StoryValueError("A ${value.typeName()} has no 'uuid'")
            (actor.character as? EntityCharacter)?.let { StoryString(it.entity.uuid.toString()) }
                ?: throw StoryValueError("'${actor.character.name}' is a name without an entity, so it has no uuid")
        }

        operator(BinaryOp.ADD, self) { a, b -> add(a.story(), b.story()) }
        operator(BinaryOp.SUBTRACT, self) { a, b -> StoryNumber(numeric(a, "-") - numeric(b, "-")) }
        operator(BinaryOp.MULTIPLY, self) { a, b -> StoryNumber(numeric(a, "*") * numeric(b, "*")) }
        operator(BinaryOp.DIVIDE, self) { a, b -> StoryNumber(numeric(a, "/") / numeric(b, "/")) }
        operator(BinaryOp.REMAINDER, self) { a, b -> StoryNumber(numeric(a, "%") % numeric(b, "%")) }
        operator(BinaryOp.LT, self) { a, b -> StoryBool(compare(a.story(), b.story(), "<") < 0) }
        operator(BinaryOp.LTE, self) { a, b -> StoryBool(compare(a.story(), b.story(), "<=") <= 0) }
        operator(BinaryOp.GT, self) { a, b -> StoryBool(compare(a.story(), b.story(), ">") > 0) }
        operator(BinaryOp.GTE, self) { a, b -> StoryBool(compare(a.story(), b.story(), ">=") >= 0) }
        operator(BinaryOp.INDEX, self) { target, index -> element(target.story(), index) }
    }

    literals(StoryLiterals(value))

    val variables = dynamic(
        name = "variables",
        valueType = value,
        read = { owner, key ->
            (owner as StoryVariables).get(key) ?: throw StoryValueError("Unknown variable '$key'")
        },
    )
    property("variables", variables) { it }
    receiver("variables")
}

val StoryLanguage: Expression<StoryVariables> = Expression {
    options {
        casts(Casts.EXPLICIT)
        unresolvedReferences(References.FAIL)
        numberSuffixes(DURATION_SUFFIXES)
    }
    declarations(StoryDeclarations)
}

private class StoryLiterals(override val type: ExprType) : Literals {
    override fun number(value: Float): Any = StoryNumber(value)
    override fun string(value: String): Any = StoryString(value)
    override fun bool(value: Boolean): Any = StoryBool(value)
    override fun list(items: List<Any?>): Any = StoryList(items.map { it.story() })
}

/**
 * One expression of a `.story` file, positioned in that file.
 */
class StoryExpression internal constructor(
    val source: String,
    val span: StorySpan,
    val ast: Ast?,
    val parts: List<StoryExpression>,
    private val value: ObjectExpression<StoryVariables>,
    val constant: StoryValue?,
) {
    fun names(): List<Ast.Name> {
        val found = ArrayList<Ast.Name>()
        parts.forEach { found += it.names() }
        ast?.walk { if (it is Ast.Name) found += it }
        return found
    }

    fun evaluate(variables: StoryVariables): StoryValue = try {
        value.eval(variables) as? StoryValue
            ?: throw StoryEvalException("Expression produced no value", span)
    } catch (e: StoryValueError) {
        throw StoryEvalException(e.message ?: "Bad value", span)
    }
}

/** Parses story expressions; the entry point [StoryParser] and the editor share. */
object StoryExpressions {
    fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    /**
     * Parses `text[from, to)` as one expression.
     */
    fun parse(text: String, offset: Int, line: Int, from: Int, to: Int): StoryExpression {
        val fragment = text.substring(from, to)
        val baked = StoryLanguage.bake(fragment, offset + from)
        val span = StorySpan(offset + from, offset + to, line)

        baked.diagnostics.firstOrNull { it.severity == Severity.ERROR }?.let { diagnostic ->
            val at = diagnostic.span
            throw StoryParseException(
                diagnostic.message,
                StorySpan(at.start, maxOf(at.end, at.start), line),
            )
        }
        return StoryExpression(
            source = fragment,
            span = span,
            ast = baked.ast,
            parts = emptyList(),
            value = baked.asObject(StoryDeclarations.literals),
            constant = baked.constant as? StoryValue,
        )
    }

    /** Parses string as one expression, for a fragment that is already on its own. */
    fun parse(text: String, offset: Int, line: Int): StoryExpression = parse(text, offset, line, 0, text.length)

    /** A list the parser built rather than the author writing it, such as `@play x, y, z`. */
    fun list(items: List<StoryExpression>, span: StorySpan): StoryExpression {
        val constants = items.map { it.constant }
        return StoryExpression(
            source = items.joinToString(", ") { it.source },
            span = span,
            ast = null,
            parts = items,
            value = { variables -> StoryList(items.map { it.evaluate(variables) }) },
            constant = if (constants.all { it != null }) StoryList(constants.filterNotNull()) else null,
        )
    }

    /** A value the parser produced itself, such as the bare word an argument was written as. */
    fun literal(value: StoryValue, span: StorySpan): StoryExpression = StoryExpression(
        source = value.display(),
        span = span,
        ast = null,
        parts = emptyList(),
        value = { value },
        constant = value,
    )
}

private fun Any?.story(): StoryValue = this as? StoryValue
    ?: throw StoryValueError("Expected a value, got ${this ?: "nothing"}")

private fun numeric(value: Any?, operator: String): Float = (value as? StoryNumber)?.value
    ?: throw StoryValueError("Operator '$operator' expects a number, got ${value.story().typeName()}")

private fun add(l: StoryValue, r: StoryValue): StoryValue = when {
    l is StoryList && r is StoryList -> StoryList(l.values + r.values)
    l is StoryList -> StoryList(l.values + r)
    r is StoryList -> StoryList(listOf(l) + r.values)
    l is StoryString || r is StoryString -> StoryString(l.display() + r.display())
    else -> StoryNumber(numeric(l, "+") + numeric(r, "+"))
}

private fun compare(l: StoryValue, r: StoryValue, operator: String): Int = when {
    l is StoryNumber && r is StoryNumber -> l.value.compareTo(r.value)
    l is StoryString && r is StoryString -> l.value.compareTo(r.value)
    else -> throw StoryValueError(
        "Cannot compare ${l.typeName()} with ${r.typeName()} using '$operator'",
    )
}

private fun element(target: StoryValue, index: Any?): StoryValue {
    val list = target as? StoryList ?: throw StoryValueError("Only a list can be indexed")
    val position = (index as? StoryNumber)?.value?.toInt()
        ?: throw StoryValueError("A list index must be a number")
    return list.values.getOrNull(position)
        ?: throw StoryValueError("No element $position: the list holds ${list.values.size}")
}

internal fun StoryValue.typeName(): String = when (this) {
    is StoryString -> "string"
    is StoryNumber -> "number"
    is StoryBool -> "bool"
    is StoryList -> "list"
    is StoryActor -> "character"
}
