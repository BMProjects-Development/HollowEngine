import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.utils.expressions.Expression
import ru.hollowhorizon.hollowengine.common.utils.expressions.References
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the resolver can work out at bake time never reaches the evaluator. A transition condition runs
 * for every entity every frame, so arithmetic between literals is worth doing once.
 */
class ExpressionFoldingTests {
    private class Ctx(val value: Float = 3f)

    private val language = Expression<Ctx> {
        options { unresolvedReferences(References.warnWithDefault(0f)) }
        declarations {
            val query = struct<Ctx>("query") {
                float("value") { it.value }
                bool("flag") { true }
            }
            val math = struct<Any?>("math") {
                function("abs") { a -> kotlin.math.abs(a) }
                function3("clamp") { v, low, high -> v.coerceIn(low, high) }
                function("random") { a -> a }
            }
            property("query", query, alias = "q") { it }
            property("math", math) { it }
            receiver("query")
            receiver("math")
        }
    }

    private fun constantOf(source: String): Any? = language.bake(source).constant

    private fun eval(source: String, context: Ctx = Ctx()) = language.bake(source).asFloat().eval(context)

    @Test
    fun `arithmetic between literals folds away`() {
        assertEquals(5f, constantOf("1 + 4"))
        assertEquals(1200f, constantOf("20 * 60"))
    }

    @Test
    fun `a constant subexpression folds inside a larger one`() {
        val baked = language.bake("value * (1 + 4)")

        assertNull(baked.constant)
        assertEquals(15f, baked.asFloat().eval(Ctx(value = 3f)))
    }

    /** `value / 20 / 60` is one division by 1200, not two. */
    @Test
    fun `chained division by constants collapses`() {
        assertEquals(0.05f, eval("value / 20 / 3", Ctx(value = 3f)), 0.0001f)
    }

    @Test
    fun `identities disappear`() {
        assertEquals(3f, eval("value * 1"))
        assertEquals(3f, eval("value + 0"))
        assertEquals(3f, eval("value / 1"))
        assertEquals(0f, eval("value * 0"))
    }

    @Test
    fun `comparisons between literals fold`() {
        assertEquals(true, constantOf("2 > 1"))
        assertEquals(false, constantOf("2 < 1"))
    }

    @Test
    fun `a constant condition picks its branch at bake time`() {
        assertEquals(7f, constantOf("true ? 7 : 9"))
        assertEquals(9f, constantOf("false ? 7 : 9"))
    }

    @Test
    fun `a constant operand collapses a logical operation`() {
        assertEquals(false, constantOf("false && q.flag"))
        assertEquals(true, constantOf("true || q.flag"))
    }

    @Test
    fun `pure math over literals folds`() {
        assertEquals(5f, constantOf("math.abs(-5)"))
        assertEquals(5f, constantOf("math.clamp(9, 1, 5)"))
    }

    @Test
    fun `random is not folded`() {
        assertNull(constantOf("math.random(10)"))
    }

    @Test
    fun `a folded expression still evaluates to the same value`() {
        val baked = language.bake("1 - (10 / 40.0)")

        assertNotNull(baked.constant)
        assertEquals(0.75f, baked.asFloat().eval(Ctx()), 0.0001f)
    }
}
