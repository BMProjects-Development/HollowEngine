import ru.hollowhorizon.hollowengine.common.scripting.ide.Diagnostic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Narrative constraints must be visible before code generation in the built-in IDE. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StageAnalysisTest {
    private lateinit var compiler: StageCompiler

    @BeforeAll
    fun setUp() { compiler = StageCompiler() }

    @AfterAll
    fun close() = compiler.close()
    private fun diagnostics(source: String): List<Diagnostic> =
        compiler.environment.analyzer.diagnostic("editing.stage.kts", source.trimIndent())

    @Test
    fun `the editor locates an enclosing local helper at its call`() {
        val source = """
            @State
            suspend fun main() {
                var timer: StoryHandle<*>? = null
                suspend fun playSing(name: String) { timer?.cancel() }
                listOf("left", "right").forEach { direction ->
                    async("input_" + direction) { playSing(direction) }
                }
                await("end")
            }
        """.trimIndent()
        val errors = diagnostics(source).filter { it.severity.isError() }
        val error = errors.firstOrNull { it.message.contains("local function 'playSing'") }
        assertTrue(error != null, errors.toString())
        assertEquals(5, error.range.start.line)
        assertTrue(error.message.contains("'timer', which cannot be saved"), error.message)
    }

    @Test
    fun `the editor reports mutable and unserializable captures`() {
        val errors = diagnostics("""
            fun start(runs: StoryRuns) {
                var counter = 0
                val lock = Any()
                runs.launch("main") { log(counter.toString()); log(lock.toString()) }
            }
        """)
        assertTrue(errors.any { it.message.contains("can share 'var counter' only with the @State function or lambda that declares it directly") }, errors.toString())
        assertTrue(errors.any { it.message.contains("'lock' cannot be saved") }, errors.toString())
    }

    @Test
    fun `the editor validates stage declarations and argument types`() {
        val errors = diagnostics("""
            @State fun plain() {}
            @State suspend fun <T> generic(value: T) {}
            @State suspend fun unsafe(lock: Any): Any = lock
            fun outer() { @State suspend fun local() {} }
        """)
        listOf("must be a suspend function", "cannot have type parameters", "'lock' cannot be",
            "The result of a stage is saved", "top level").forEach { expected ->
            assertTrue(errors.any { expected in it.message }, "$expected: $errors")
        }
    }

    @Test
    fun `safe helpers and captures do not receive narrative errors`() {
        val errors = diagnostics("""
            import ru.hollowhorizon.hollowengine.addons.narrative.story.State as Stage

            fun greet(name: String) = log(name)
            @Stage suspend fun main() {
                val name = "left"
                async("input") {
                    fun sing() = greet(name)
                    sing()
                    await("end")
                }
                await("end")
            }
        """).filter { it.severity.isError() }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `the editor reports stage calls in lambdas and exception handlers`() {
        val errors = diagnostics("""
            @State suspend fun step() {}
            @State suspend fun main() {
                listOf(1).forEach { step() }
                try { log("try") } catch (failure: Exception) { step() } finally { step() }
            }
        """)
        assertTrue(errors.any { "cannot be called from a lambda" in it.message }, errors.toString())
        assertEquals(2, errors.count { "cannot be called in catch or finally" in it.message }, errors.toString())
    }

    @Test
    fun `the editor validates checkpoint placement and names`() {
        val errors = diagnostics("""
            suspend fun helper() { checkpoint("outside") }
            @State suspend fun main() {
                checkpoint("gate")
                checkpoint("gate")
                checkpoint(StageTrace.token)
            }
        """)
        listOf("must be called directly", "appears twice", "needs a constant name").forEach { expected ->
            assertTrue(errors.any { expected in it.message }, "$expected: $errors")
        }
    }

    @Test
    fun `the editor validates actions and duplicate stage names`() {
        val errors = diagnostics("""
            @State("same") suspend fun first() {}
            @State("same") suspend fun second() {}
            suspend fun invalid() = action { perform { } }
            @Action suspend fun unsafe(lock: Any) = action { perform { } }
            @Action suspend fun missing(@Unsaved lock: Any) = action { perform { } }
        """)
        listOf("Two stages", "must be the body of an @Action", "'lock' cannot be saved", "needs one").forEach { expected ->
            assertTrue(errors.any { expected in it.message }, "$expected: $errors")
        }
    }

    @Test
    fun `the editor rejects function references and stored ordinary lambdas`() {
        val errors = diagnostics("""
            suspend fun helper() {}
            fun start(runs: StoryRuns) {
                val body: @State suspend () -> Unit = {}
                runs.launch("one", ::helper)
                runs.launch("two", body)
            }
        """)
        assertEquals(2, errors.count { "a regular function value has no saved stage" in it.message }, errors.toString())
    }

    @Test
    fun `lambda arguments and results have the same persistence restrictions as named stages`() {
        val errors = diagnostics("""
            fun consume(block: @State suspend (Any) -> Unit) {}
            fun produce(block: @State suspend () -> Any) {}
            fun start() {
                consume { lock -> log(lock.toString()) }
                produce { Any() }
            }
        """)
        assertTrue(errors.any { "'lock' cannot be" in it.message }, errors.toString())
        assertTrue(errors.any { "The result of a stage is saved" in it.message }, errors.toString())
    }

    @Test
    fun `unfinished stage code produces diagnostics without breaking editor analysis`() {
        val errors = diagnostics("""
            @State suspend fun main() {
                async("timer") {
                    val captured =
                }
            }
        """)
        assertTrue(errors.any { it.severity.isError() }, errors.toString())
    }
}
