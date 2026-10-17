import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.utils.expressions.*
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Parse, resolve and evaluate, against declarations shaped like the ones the animator will use. */
class ExpressionEvalTests {
    private class Ctx(
        val health: Float = 10f,
        val speed: Float = 0f,
        val alive: Boolean = true,
        val sneaking: Boolean = false,
    ) {
        val variables = HashMap<String, Float>()
        val temps = HashMap<String, Float>()
        val data = HashMap<String, Float>()
    }

    private val float = ExprType.Primitive.FLOAT

    private val declarations = Declarations<Ctx> {
        val vec3 = struct<FloatArray>("vec3") {
            float("x") { it[0] }
            float("y") { it[1] }
            float("z") { it[2] }
        }
        val query = struct<Ctx>("query") {
            float("health") { it.health }
            float("speed") { it.speed }
            bool("is_alive") { it.alive }
            bool("is_sneaking") { it.sneaking }
            field("velocity", vec3) { floatArrayOf(1f, 2f, 3f) }
        }
        val math = struct<Any?>("math") {
            method("max", float, float, float) { _, args -> max(args[0] as Float, args[1] as Float) }
            method("clamp", float, float, float, float) { _, args ->
                (args[0] as Float).coerceIn(args[1] as Float, args[2] as Float)
            }
        }
        val temporaries = dynamic(
            name = "temporaries",
            read = { owner, key -> (owner as Ctx).temps[key] ?: 0f },
            write = { owner, key, value -> (owner as Ctx).temps[key] = (value as Number).toFloat() },
        )
        val variables = dynamic(
            name = "variables",
            read = { owner, key -> (owner as Ctx).variables[key] ?: 0f },
            write = { owner, key, value -> (owner as Ctx).variables[key] = (value as Number).toFloat() },
        )

        val entityData = dynamic(
            name = "data",
            read = { owner, key -> (owner as Ctx).data[key] ?: 0f },
            nested = true,
        )

        property("query", query, alias = "q") { it }
        property("math", math) { it }
        property("variable", variables, alias = "v") { it }
        property("temp", temporaries, alias = "t") { it }
        property("data", entityData, alias = "d") { it }
        receiver("query")
        receiver("math")
    }

    private val expressions = Expression<Ctx> {
        options { unresolvedReferences(References.warnWithDefault(0f)) }
        declarations(declarations)
    }

    private fun evalFloat(source: String, context: Ctx = Ctx()) =
        expressions.bake(source).asFloat().eval(context)

    private fun evalBool(source: String, context: Ctx = Ctx()) =
        expressions.bake(source).asBool().eval(context)

    @Test
    fun `arithmetic over declared members`() {
        assertEquals(5f, evalFloat("q.health / 2", Ctx(health = 10f)))
    }

    /** The receiver is what makes the animator's bare names and Molang's prefixed ones one dictionary. */
    @Test
    fun `a bare name resolves through the receiver`() {
        assertEquals(evalFloat("q.health"), evalFloat("health"))
    }

    @Test
    fun `a nested struct member reads without building the struct`() {
        assertEquals(2f, evalFloat("q.velocity.y"))
    }

    @Test
    fun `a bool member converts to a number under implicit casts`() {
        assertEquals(1f, evalFloat("q.is_alive", Ctx(alive = true)))
        assertEquals(0f, evalFloat("q.is_alive", Ctx(alive = false)))
    }

    @Test
    fun `comparisons and logic produce booleans`() {
        assertTrue(evalBool("q.health > 5 && q.is_alive", Ctx(health = 10f)))
        assertFalse(evalBool("q.health > 5 && q.is_alive", Ctx(health = 1f)))
    }

    @Test
    fun `methods resolve by name and arity`() {
        assertEquals(7f, evalFloat("math.max(3, 7)"))
        assertEquals(5f, evalFloat("math.clamp(9, 1, 5)"))
    }

    @Test
    fun `a bare call resolves through the receiver too`() {
        assertEquals(7f, evalFloat("max(3, 7)"))
    }

    @Test
    fun `a ternary picks a branch`() {
        assertEquals(1f, evalFloat("q.is_alive ? 1 : 0"))
    }

    /** Molang variables are a bag: any name is valid and starts at zero. */
    @Test
    fun `dynamic variables read as zero and can be assigned`() {
        val context = Ctx()

        assertEquals(0f, evalFloat("v.anything", context))
        assertEquals(3f, evalFloat("v.counter = 3", context))
        assertEquals(3f, evalFloat("v.counter", context))
    }

    @Test
    fun `temporaries are a second bag, separate from variables`() {
        val context = Ctx()

        assertEquals(5f, evalFloat("t.scratch = 5", context))
        assertEquals(5f, evalFloat("t.scratch", context))
        assertEquals(0f, evalFloat("v.scratch", context))
    }

    @Test
    fun `a sequence evaluates in order and yields the last value`() {
        val context = Ctx()

        assertEquals(3f, evalFloat("v.a = 1; v.b = 2; v.a + v.b", context))
    }

    @Test
    fun `an unknown query member falls back instead of failing`() {
        val baked = expressions.bake("q.nonexistent")

        assertEquals(0f, baked.asFloat().eval(Ctx()))
        assertFalse(baked.hasErrors)
    }

    @Test
    fun `a strict dialect refuses the same unknown member`() {
        val strict = Expression<Ctx> {
            options {
                casts(Casts.EXPLICIT)
                unresolvedReferences(References.FAIL)
            }
            declarations(declarations)
        }

        assertTrue(strict.bake("q.nonexistent").hasErrors)
    }

    @Test
    fun `explicit casts refuse using a number as a condition`() {
        val strict = Expression<Ctx> {
            options {
                casts(Casts.EXPLICIT)
                unresolvedReferences(References.FAIL)
            }
            declarations(declarations)
        }

        assertTrue(strict.bake("q.health && q.is_alive").hasErrors)
    }

    /**
     * Molang content spells a flag test as `is_sneaking != 0.0`. Comparing the two sides as objects
     * would box a Boolean against a Float, which are never equal, so every such test would pass.
     */
    @Test
    fun `a bool compares numerically against a number`() {
        assertTrue(evalBool("q.is_alive != 0.0", Ctx(alive = true)))
        assertFalse(evalBool("q.is_alive != 0.0", Ctx(alive = false)))
        assertTrue(evalBool("q.is_alive == 1.0", Ctx(alive = true)))
        assertTrue(evalBool("q.is_alive == 0.0", Ctx(alive = false)))
    }

    @Test
    fun `the standard preset sneak condition only fires while sneaking`() {
        assertFalse(evalBool("is_alive != 0.0 && is_sneaking != 0.0", Ctx(alive = true)))
        assertTrue(evalBool("is_alive != 0.0 && is_sneaking != 0.0", Ctx(alive = true, sneaking = true)))
    }

    @Test
    fun `a nested bag keeps taking path segments`() {
        val context = Ctx().apply {
            data["emotion"] = 1f
            data["emotion.happy"] = 0.75f
            data["quest.stage.index"] = 3f
        }

        assertEquals(1f, evalFloat("d.emotion", context))
        assertEquals(0.75f, evalFloat("d.emotion.happy", context))
        assertEquals(3f, evalFloat("d.quest.stage.index", context))
    }

    @Test
    fun `an unset path in a nested bag reads as zero`() {
        assertEquals(0f, evalFloat("d.nothing.here.at.all"))
        assertTrue(evalBool("d.quest.done == 0"))
    }

    @Test
    fun `a broken expression still yields a usable constant`() {
        val baked = expressions.bake("1 +")

        assertTrue(baked.hasErrors)
        assertEquals(0f, baked.asFloat().eval(Ctx()))
        assertEquals(1f, baked.asFloat(default = 1f).eval(Ctx()))
    }
}
