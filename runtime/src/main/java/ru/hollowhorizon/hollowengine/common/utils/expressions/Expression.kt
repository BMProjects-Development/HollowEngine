package ru.hollowhorizon.hollowengine.common.utils.expressions

/**
 * An evaluated expression over a context of type [C].
 */
fun interface FloatExpression<in C> {
    fun eval(context: C): Float
}

fun interface BoolExpression<in C> {
    fun eval(context: C): Boolean
}

fun interface ObjectExpression<in C> {
    fun eval(context: C): Any?
}

/**
 * A parsed, resolved and folded expression, ready to lower.
 */
class Baked<C> internal constructor(
    val source: String,
    val diagnostics: List<Diagnostic>,
    val ast: Ast?,
    internal val ir: Ir?,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }

    /** The value when the whole expression folded to a constant, for callers that can skip it entirely. */
    val constant: Any? get() = ir?.constant

    fun asFloat(default: Float = 0f): FloatExpression<C> =
        ir?.let(Interpreter::floats) ?: FloatExpression { default }

    fun asBool(default: Boolean = false): BoolExpression<C> =
        ir?.let(Interpreter::bools) ?: BoolExpression { default }

    /**
     * The value as the dialect represents it.
     *
     * Where a dialect declared [Literals], a primitive result is wrapped the same way a literal would
     * be, so `a && b` in a dialogue comes back as a story value rather than a bare `Boolean`.
     */
    fun asObject(literals: Literals? = null): ObjectExpression<C> {
        val value = ir?.let(Interpreter::objects) ?: return ObjectExpression { null }
        val type = ir.type
        if (literals == null || !type.isPrimitive) return value
        return if (type === ExprType.Primitive.BOOL) {
            ObjectExpression { literals.bool(Coercions.toBool(value.eval(it))) }
        } else {
            ObjectExpression { literals.number(Coercions.toFloat(value.eval(it))) }
        }
    }

    /** Throws with every diagnostic attached; for callers that treat a bad expression as fatal. */
    fun orThrow(): Baked<C> {
        if (hasErrors) throw ExpressionException(diagnostics)
        return this
    }
}

/**
 * A configured dialect of the language: what may be named, and how strict it is about naming it.
 *
 * ```kotlin
 * val animations = Expression<AnimatorEvaluationContext> {
 *     options { unresolvedReferences(References.warnWithDefault(0f)) }
 *     declarations(AnimationDeclarations)
 * }
 * val weight: FloatExpression<AnimatorEvaluationContext> = animations.bake("q.health").asFloat()
 * ```
 */
class Expression<C> internal constructor(
    val declarations: Declarations<C>,
    val options: Options,
) {
    /**
     * [offset] shifts every span and diagnostic, for an expression cut out of a larger file.
     */
    fun bake(source: String, offset: Int = 0): Baked<C> {
        val diagnostics = Diagnostics()
        val ast = parseExpression(source, diagnostics, options, offset)
        val ir = ast?.let { Resolver(declarations, options, diagnostics).resolve(it) }
        return Baked(source, diagnostics.all, ast, ir)
    }

    fun compile(sources: Iterable<String>): CompiledUnit<C> {
        val baked = sources.distinct().map(::bake)
        val compiled = ExpressionCompiler.compile(baked.map { it.ir })
        return CompiledUnit(baked.mapIndexed { index, entry -> entry.source to (entry to compiled[index]) }.toMap())
    }

    class Builder<C> internal constructor() {
        private var declarations = Declarations<C> {}
        private val options = Options()

        fun options(block: Options.() -> Unit) {
            options.block()
        }

        fun declarations(value: Declarations<C>) {
            declarations += value
        }

        fun declarations(block: DeclarationsBuilder<C>.() -> Unit) {
            declarations += Declarations(block)
        }

        internal fun build() = Expression(declarations, options)
    }
}

fun <C> Expression(block: Expression.Builder<C>.() -> Unit): Expression<C> =
    Expression.Builder<C>().apply(block).build()

/**
 * The expressions of one model or particle file, compiled together.
 */
class CompiledUnit<C> internal constructor(
    private val entries: Map<String, Pair<Baked<C>, FloatExpression<Any?>?>>,
) {
    val sources: Set<String> get() = entries.keys

    val diagnostics: List<Diagnostic> get() = entries.values.flatMap { it.first.diagnostics }

    fun diagnostics(source: String): List<Diagnostic> = entries[source]?.first?.diagnostics.orEmpty()

    /** Is [source] runs as bytecode. false means it fallback to the interpreter. */
    fun isCompiled(source: String): Boolean = entries[source]?.second != null

    fun float(source: String, default: Float = 0f): FloatExpression<C> {
        val entry = entries[source] ?: return FloatExpression { default }
        return entry.second ?: entry.first.asFloat(default)
    }

    fun bool(source: String, default: Boolean = false): BoolExpression<C> {
        val entry = entries[source] ?: return BoolExpression { default }
        val compiled = entry.second ?: return entry.first.asBool(default)
        return BoolExpression { compiled.eval(it) != 0f }
    }
}
