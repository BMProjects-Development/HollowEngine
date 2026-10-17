import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.utils.expressions.ExprType
import ru.hollowhorizon.hollowengine.common.utils.expressions.Expression
import ru.hollowhorizon.hollowengine.common.utils.expressions.References
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bytecode backend has to be identical to interpreter, a model behaving differently
 * depending on which one ran would be the worst kind of bug to chase. Every case here is checked against
 * the interpreted result rather than against a handwritten expected value.
 */
class ExpressionCompilerTests {
    private class Ctx(
        val value: Float = 3f,
        val flag: Boolean = true,
        val label: String? = "npc",
    ) {
        val variables = HashMap<String, Any?>()
    }

    private val language = Expression<Ctx> {
        options { unresolvedReferences(References.warnWithDefault(0f)) }
        declarations {
            val text = reference<String>("string", String::class.java) {
                float("length") { it.length.toFloat() }
            }
            val query = struct<Ctx>("query") {
                float("value") { it.value }
                bool("flag") { it.flag }
                field("label", text) { it.label }
                function("double") { a -> a * 2f }
                function2("min") { a, b -> minOf(a, b) }
                function3("clamp") { v, low, high -> v.coerceIn(low, high) }
                method("name_is", ExprType.Primitive.BOOL, text) { owner, args ->
                    (owner as Ctx).label == args[0]
                }
            }
            val math = struct<Any?>("math") {
                function("abs") { a -> abs(a) }
            }
            val variables = dynamic(
                "variable",
                read = { owner, key -> (owner as Ctx).variables[key] },
                write = { owner, key, value -> (owner as Ctx).variables[key] = value },
            )
            property("query", query, alias = "q") { it }
            property("math", math) { it }
            property("variable", variables, alias = "v") { it }
            receiver("query")
            receiver("math")
        }
    }

    /** Runs [source] both ways over a fresh context each time and asserts they agree. */
    private fun assertSameAsInterpreter(source: String, context: () -> Ctx = { Ctx() }) {
        val unit = language.compile(listOf(source))
        assertTrue(unit.isCompiled(source), "'$source' was not compiled")

        val interpreted = language.bake(source).asFloat().eval(context())
        val compiled = unit.float(source).eval(context())
        assertEquals(interpreted, compiled, 0.0001f, "'$source' differs from the interpreter")
    }

    private fun assertSameBool(source: String, context: () -> Ctx = { Ctx() }) {
        val unit = language.compile(listOf(source))
        assertTrue(unit.isCompiled(source), "'$source' was not compiled")

        val interpreted = language.bake(source).asBool().eval(context())
        assertEquals(interpreted, unit.bool(source).eval(context()), "'$source' differs from the interpreter")
    }

    @Test
    fun `arithmetic matches`() {
        listOf("1 + 2", "value * 2", "value / 4", "value % 2", "-value", "value * value - 1")
            .forEach(::assertSameAsInterpreter)
    }

    @Test
    fun `field reads match`() {
        assertSameAsInterpreter("q.value")
        assertSameAsInterpreter("value")
        assertSameAsInterpreter("q.flag")
        assertSameAsInterpreter("q.label.length")
    }

    @Test
    fun `calls of every arity match`() {
        assertSameAsInterpreter("q.double(4)")
        assertSameAsInterpreter("q.min(value, 2)")
        assertSameAsInterpreter("q.clamp(value, 0, 1)")
        assertSameAsInterpreter("math.abs(0 - value)")
    }

    /** A call that is not all-float goes through the generic invoker in both backends. */
    @Test
    fun `a generic call matches`() {
        assertSameBool("q.name_is(q.label)")
        assertSameBool("q.name_is(q.label)") { Ctx(label = null) }
    }

    @Test
    fun `comparisons match`() {
        listOf("value > 2", "value >= 3", "value < 2", "value <= 3", "value == 3", "value != 3")
            .forEach(::assertSameBool)
    }

    /** `is_sneaking != 0` is what Bedrock content actually writes, so a bool against a number matters. */
    @Test
    fun `a bool compared with a number matches`() {
        assertSameBool("q.flag != 0.0")
        assertSameBool("q.flag == 1.0")
        assertSameAsInterpreter("q.flag + 1")
    }

    @Test
    fun `logic and negation match`() {
        listOf("q.flag && value > 2", "q.flag || value > 99", "!q.flag", "!(value > 2) && q.flag")
            .forEach(::assertSameBool)
    }

    /** The right side of a short-circuit must not run; the write makes that visible. */
    @Test
    fun `short circuit skips the right side`() {
        val source = "false && (v.hit = 1)"
        val unit = language.compile(listOf(source))
        val context = Ctx()

        unit.bool(source).eval(context)

        assertFalse(context.variables.containsKey("hit"))
    }

    @Test
    fun `conditionals match`() {
        assertSameAsInterpreter("value > 2 ? 10 : 20")
        assertSameAsInterpreter("value > 99 ? 10 : 20")
        assertSameBool("value > 2 ? q.flag : !q.flag")
    }

    @Test
    fun `bag reads and writes match`() {
        assertSameAsInterpreter("v.speed")
        assertSameAsInterpreter("v.speed = 5")
        assertSameAsInterpreter("v.speed = value * 2; v.speed + 1")
    }

    @Test
    fun `a write through the compiled path reaches the context`() {
        val source = "v.speed = value * 2"
        val unit = language.compile(listOf(source))
        val context = Ctx(value = 4f)

        assertEquals(8f, unit.float(source).eval(context))
        assertEquals(8f, context.variables["speed"])
    }

    @Test
    fun `sequences match`() {
        assertSameAsInterpreter("v.a = 1; v.b = 2; v.a + v.b")
        assertSameBool("v.a = 1; v.a == 1")
    }

    /** An unresolved name is 0 in this dialect; the compiled form must agree rather than throw. */
    @Test
    fun `unknown members match`() {
        assertSameAsInterpreter("q.nonexistent")
        assertSameAsInterpreter("q.nonexistent + 1")
    }

    /** One class per model, not per expression: the whole batch has to come back working. */
    @Test
    fun `a batch compiles every expression`() {
        val sources = listOf("value", "value * 2", "q.flag", "value > 1 && q.flag", "v.x = value")
        val unit = language.compile(sources)
        val context = Ctx(value = 5f)

        sources.forEach { assertTrue(unit.isCompiled(it), "'$it' was not compiled") }
        assertEquals(5f, unit.float("value").eval(context))
        assertEquals(10f, unit.float("value * 2").eval(context))
        assertTrue(unit.bool("q.flag").eval(context))
        assertTrue(unit.bool("value > 1 && q.flag").eval(context))
        assertEquals(5f, unit.float("v.x = value").eval(context))
    }

    /** A float expression asked for as a bool, and the other way round, keeps the interpreter's rule. */
    @Test
    fun `the kinds convert into each other`() {
        val unit = language.compile(listOf("value", "q.flag"))
        val context = Ctx(value = 0f, flag = true)

        assertFalse(unit.bool("value").eval(context))
        assertEquals(1f, unit.float("q.flag").eval(context))
    }

    /** A broken expression must cost that expression, not the whole model. */
    @Test
    fun `a batch survives an expression that does not parse`() {
        val unit = language.compile(listOf("value * 2", "value +"))
        val context = Ctx(value = 4f)

        assertEquals(8f, unit.float("value * 2").eval(context))
        assertFalse(unit.isCompiled("value +"))
        assertEquals(7f, unit.float("value +", default = 7f).eval(context))
    }
}
