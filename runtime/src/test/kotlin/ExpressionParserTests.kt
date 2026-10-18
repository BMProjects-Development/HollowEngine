import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.utils.expressions.Ast
import ru.hollowhorizon.hollowengine.common.utils.expressions.Diagnostics
import ru.hollowhorizon.hollowengine.common.utils.expressions.parseExpression
import kotlin.test.*

class ExpressionParserTests {
    private fun parse(source: String): Ast? = parseExpression(source, Diagnostics())

    private fun diagnose(source: String): Diagnostics =
        Diagnostics().also { parseExpression(source, it) }

    /** Renders the tree so precedence and associativity can be asserted without matching nested types. */
    private fun Ast.render(): String = when (this) {
        is Ast.NumberLit -> if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        is Ast.BoolLit -> value.toString()
        is Ast.StringLit -> "'$value'"
        is Ast.ListLit -> items.joinToString(", ", "[", "]") { it.render() }
        is Ast.Name -> name
        is Ast.Access -> "${target.render()}.$name"
        is Ast.Index -> "${target.render()}[${index.render()}]"
        is Ast.Call -> "${target?.let { "${it.render()}." } ?: ""}$name(${arguments.joinToString { it.render() }})"
        is Ast.Unary -> "(${op.symbol}${operand.render()})"
        is Ast.Binary -> "(${left.render()} ${op.symbol} ${right.render()})"
        is Ast.Conditional -> "(${condition.render()} ? ${ifTrue.render()} : ${ifFalse.render()})"
        is Ast.Assign -> "(${target.render()} = ${value.render()})"
        is Ast.Sequence -> statements.joinToString("; ") { it.render() }
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        assertEquals("(1 + (2 * 3))", parse("1 + 2 * 3")?.render())
    }

    @Test
    fun `comparison binds looser than arithmetic`() {
        assertEquals("((a + 1) > b)", parse("a + 1 > b")?.render())
    }

    @Test
    fun `and binds tighter than or`() {
        assertEquals("(a || (b && c))", parse("a || b && c")?.render())
    }

    @Test
    fun `arithmetic is left associative`() {
        assertEquals("((10 - 3) - 2)", parse("10 - 3 - 2")?.render())
    }

    @Test
    fun `assignment is right associative`() {
        assertEquals("(a = (b = 1))", parse("a = b = 1")?.render())
    }

    @Test
    fun `parentheses override precedence`() {
        assertEquals("((1 + 2) * 3)", parse("(1 + 2) * 3")?.render())
    }

    @Test
    fun `member access chains`() {
        assertEquals("q.velocity.x", parse("q.velocity.x")?.render())
    }

    @Test
    fun `a call on a member is a call, not an access`() {
        assertEquals("math.clamp(a, 0, 1)", parse("math.clamp(a, 0, 1)")?.render())
    }

    @Test
    fun `a bare call has no target so it can resolve through receivers`() {
        val call = parse("sin(x)")
        assertIs<Ast.Call>(call)
        assertNull(call.target)
    }

    @Test
    fun `indexing and access combine`() {
        assertEquals("items[0].name", parse("items[0].name")?.render())
    }

    @Test
    fun `unary minus applies before multiplication`() {
        assertEquals("((-a) * b)", parse("-a * b")?.render())
    }

    @Test
    fun `ternary nests to the right`() {
        assertEquals("(a ? 1 : (b ? 2 : 3))", parse("a ? 1 : b ? 2 : 3")?.render())
    }

    @Test
    fun `a sequence yields its statements in order`() {
        assertEquals("(a = 1); (b = 2); (a + b)", parse("a = 1; b = 2; a + b")?.render())
    }

    @Test
    fun `a trailing semicolon is allowed`() {
        assertEquals("(a = 1)", parse("a = 1;")?.render())
    }

    @Test
    fun `lists and strings parse`() {
        assertEquals("['a', 'b']", parse("[\"a\", \"b\"]")?.render())
    }

    @Test
    fun `a leading dot number is a number`() {
        assertEquals("0.5", parse(".5")?.render())
    }

    @Test
    fun `an unknown character is reported instead of dropped`() {
        val diagnostics = diagnose("q.speed \$ 2")

        assertTrue(diagnostics.hasErrors)
        assertTrue(diagnostics.errors.first().message.contains("Unexpected character"))
    }

    @Test
    fun `a single ampersand is reported`() {
        assertTrue(diagnose("a & b").hasErrors)
    }

    @Test
    fun `an unterminated string is reported`() {
        assertTrue(diagnose("\"hello").hasErrors)
    }

    @Test
    fun `assigning to something that is not a name is refused`() {
        val diagnostics = diagnose("1 = 2")

        assertTrue(diagnostics.hasErrors)
        assertTrue(diagnostics.errors.first().message.contains("Cannot assign"))
    }

    @Test
    fun `an incomplete expression is reported rather than parsed`() {
        assertNull(parse("1 +"))
        assertTrue(diagnose("1 +").hasErrors)
    }

    @Test
    fun `a span points at the offending text`() {
        val diagnostics = diagnose("a + %")
        val span = diagnostics.errors.first().span

        assertEquals("%", "a + %".substring(span.start, span.end))
    }

    @Test
    fun `spans cover the whole subexpression`() {
        val tree = parse("1 + 2 * 3")
        assertNotNull(tree)

        assertEquals(0, tree.span.start)
        assertEquals("1 + 2 * 3".length, tree.span.end)
    }
}
