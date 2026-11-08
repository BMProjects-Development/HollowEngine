import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionSink
import ru.hollowhorizon.hollowengine.common.scripting.ide.Diagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryAnalysis

/** Narrative constraints must be visible before code generation in the built-in IDE. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StageAnalysisTest {
    private lateinit var compiler: StageCompiler

    @BeforeAll
    fun setUp() {
        compiler = StageCompiler()
    }

    @AfterAll
    fun close() = compiler.close()
    private fun diagnostics(source: String): List<Diagnostic> =
        compiler.environment.analyzer.diagnostic("editing.stage.kts", source.trimIndent())

    @Test
    fun `type facts include source-only classes aliases and enum constants without loading classes`() {
        val source = """
            class SourceOnly : ArrayList<String>()
            typealias Names = List<String?>
            enum class SourceMood { CALM, ANGRY }
            suspend fun inspect() {
                val storyPinList = SourceOnly()
                val storyPinAlias: Names = emptyList()
                val storyPinEnum = SourceMood.CALM
                val storyPinBottom = emptyMap<String, Nothing>()
                val storyPinError: List<Missing> = emptyList()
            }
        """.trimIndent()
        val found = compiler.environment.analyzer.propertyTypes("facts.stage.kts", source, "storyPin")
        val analysis = StoryAnalysis(relations = found.fits, details = found.details)
        fun type(pin: String) = found.types.getValue("storyPin$pin")
        assertEquals(StoryPinType.LIST, analysis.pinType(type("List")))
        assertEquals("kotlin.collections.List<kotlin.String?>", type("Alias"))
        assertEquals(listOf("CALM", "ANGRY"), found.details.getValue(type("Enum")).enumEntries)
        assertTrue(found.details.getValue(type("Bottom")).containsNothing)
        assertFalse(found.details.getValue(type("Alias")).containsNothing)
        assertNull(found.types["storyPinError"])
        val form = analysis.fieldForm(type("Enum"))
        assertTrue(form is StoryFieldForm.Constant)
        assertTrue(form.choices.all { it.endsWith(".CALM") || it.endsWith(".ANGRY") })
    }

    @Test
    fun `inner generic classes and suspend receiver functions are named as legal Kotlin types`() {
        val source = """
            class Outer<A> { inner class Inner<B> }
            suspend fun inspect(
                inner: Outer<String>.Inner<Int>,
                function: (suspend String.(Int) -> List<String?>)?,
            ) {
                val storyPinInner = inner
                val storyPinFunction = function
            }
        """.trimIndent()
        val analyzer = compiler.environment.analyzer
        val found = analyzer.propertyTypes("complex-types.stage.kts", source, "storyPin")
        val inner = found.types.getValue("storyPinInner")
        val function = found.types.getValue("storyPinFunction")
        assertTrue(inner.contains("Outer<kotlin.String>.Inner<kotlin.Int>"), inner)
        assertTrue(function.contains("suspend"), function)
        val checks = found.types.values.mapIndexed { index, type -> "val verify$index: $type = TODO()" }.joinToString("\n")
        val errors = analyzer.diagnostic("complex-types.stage.kts", "$source\nsuspend fun verify() {\n$checks\n}")
            .filter { it.severity.isError() }
        assertEquals(emptyList(), errors.map { it.message })
    }

    @Test
    fun `the editor locates an enclosing local helper at its call`() {
        val source = """
            @State
            suspend fun main() {
                var lock: Any? = null
                suspend fun playSing(name: String) { lock = name }
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
        assertTrue(error.message.contains("'lock', which cannot be saved"), error.message)
    }

    @Test
    fun `named values tell what they turned out to be`() {
        val source = """
            suspend fun first() {
                val storyPin0 = (listOf("x"))
            }
            suspend fun second() {
                val storyPin1 = (listOf("x")).iterator().next()
                val storyPin2 = (missing)
                val other = 1
            }
        """.trimIndent()
        val types = compiler.environment.analyzer.propertyTypes("types.stage.kts", source, "storyPin").types
        assertEquals(
            mapOf("storyPin0" to "kotlin.collections.List<kotlin.String>", "storyPin1" to "kotlin.String"), types
        )
    }

    @Test
    fun `values fit places the way Kotlin assigns them`() {
        val source = """
            suspend fun check() {
                val storyPin0 = listOf("x")
                val storyPin1 = listOf(1)
                val storyPin2 = listOf<String?>()
                val storyPin3 = ArrayList<StringBuilder>()
                val storyPin4: List<String> = listOf()
                val storyPin5 = if (true) 1 else "x"
                val storyPin6: Iterable<*> = listOf<Int>()
                val storyPin7: Missing = ""
                val storyPin8: List<Missing> = listOf()
                val storyPin9: String? = null
                val storyPin10 = System.getProperty("a")
                val storyPin11 = "x"
            }
        """.trimIndent()
        val found = compiler.environment.analyzer.propertyTypes("fits.stage.kts", source, "storyPin")
        fun fits(from: Int, into: Int) =
            found.types.getValue("storyPin$into") in found.fits.getValue(found.types.getValue("storyPin$from"))
        assertEquals(true, fits(0, 4))
        assertEquals(true, fits(1, 6))
        assertEquals(false, fits(1, 4))
        assertEquals(false, fits(2, 4))
        assertEquals(true, fits(3, 6), "a Java class reaches Kotlin's collections through its supertypes")
        assertEquals(false, fits(3, 4))
        assertEquals(true, fits(9, 11), "a value that can be null fits once it is not")
        assertEquals(true, fits(0, 0), "a type fits itself")
        assertEquals(
            "java.util.ArrayList<java.lang.StringBuilder>",
            found.types.getValue("storyPin3"),
            "an alias is the type it stands for"
        )
        assertEquals("kotlin.String", found.types.getValue("storyPin10"), "a platform type is read as not null")
        assertTrue(found.types.getValue("storyPin5").startsWith("kotlin."), "a type with no name of its own is made one")
        assertTrue("storyPin7" !in found.types && "storyPin8" !in found.types)
    }

    @Test
    fun `a field is checked against the type a linked value has, as analysis names it`() {
        val source = """
            data class Cost(val block: String, val cost: Int)
            suspend fun check() {
                val storyPin0 = Cost("a", 1)
                val storyPin1 = listOf(Cost("a", 1))
            }
        """.trimIndent()
        val found = compiler.environment.analyzer.propertyTypes("declared.stage.kts", source, "storyPin").types
        val cost = found.getValue("storyPin0")
        val list = found.getValue("storyPin1")

        fun errors(type: String, code: String) = diagnostics(
            "$source\nsuspend fun storyCheck0() {\nval storyValue: $type = (\n$code\n)\n}"
        ).filter { it.severity.isError() }
        assertEquals(emptyList(), errors(cost, "Cost(\"b\", 2)"), "a class of the script is named so the script reads it")
        assertEquals(emptyList(), errors(list, "listOf(Cost(\"b\", 2))"))
        assertTrue(errors(cost, "1f").isNotEmpty(), "a value of another type is an error in its field")
    }

    /**
     * A generic block is checked as a call of functions written for it: the first values linked in fix its type
     * parameters, or the place its output goes into does, and the rest of its inputs are checked against them.
     */
    @Test
    fun `a generic block is typed the way Kotlin infers a call`() {
        val source = """
            data class Cost(val block: String, val cost: Int)
            class StorySink<in X>
            fun <X> storySink(): StorySink<X> = StorySink()
            class StoryBlock0<T> {
                fun call(p0: T, p1: T) {}
                fun pin0(): T = TODO()
                fun pin2(): List<T> = TODO()
            }
            fun <T> storyFix0(p0: T): StoryBlock0<T> = StoryBlock0()
            suspend fun storyFixed0() = storyFix0((Cost("a", 1))!!)
            suspend fun storyBlock0() {
                val storyLinked0 = (Cost("a", 1))!!
                storyFix0(storyLinked0).call(p0 = storyLinked0, p1 = (
                1f
                ))
                val storyPinFix0 = storyFixed0().pin0()
                val storyPinFix1 = storyFixed0().pin2()
            }
            class StoryBlock1<T> {
                fun call(p0: T) {}
                fun pin0(): T = TODO()
            }
            fun <T> storyPlace1(into: StorySink<List<T>>): StoryBlock1<T> = StoryBlock1()
            suspend fun storyFixed1() = storyPlace1(storySink<Iterable<Cost>>())
            suspend fun storyBlock1() {
                storyFixed1().call(p0 = (
                1
                ))
                val storyPinFix2 = storyFixed1().pin0()
            }
            suspend fun storyFixed2() = storyPlace1(storySink<Iterable<*>>())
            suspend fun storyBlock2() {
                storyFixed2().call(p0 = (
                1
                ))
                val storyPinFix3 = storyFixed2().pin0()
            }
            class StoryBlock3<V> {
                fun <K> call(p0: K, p1: V) {}
                fun pin1(): V = TODO()
            }
            fun <V> storyPlace3(into: StorySink<V>): StoryBlock3<V> = StoryBlock3()
            suspend fun storyFixed3() = storyPlace3(storySink<Cost>())
            suspend fun storyBlock3() {
                storyFixed3().call(p0 = (
                "key"
                ), p1 = (
                2
                ))
                val storyPinFix5 = storyFixed3().pin1()
            }
        """.trimIndent()
        val lines = source.lines()
        val errors = diagnostics(source).filter { it.severity.isError() }
            .map { lines[it.range.start.line].trim() to it.message }
        val found = compiler.environment.analyzer.propertyTypes("generic.stage.kts", source, "storyPin").types
        assertEquals("Cost", found["storyPinFix0"]?.substringAfterLast('.'), "the first value linked in tells the type")
        assertTrue(found["storyPinFix1"].orEmpty().endsWith("List<Cost>"), found.toString())
        assertEquals("Cost", found["storyPinFix2"]?.substringAfterLast('.'), "the place an output goes into tells the type")
        assertTrue("storyPinFix3" !in found, "a place that tells nothing fixes nothing")
        assertEquals("Cost", found["storyPinFix5"]?.substringAfterLast('.'), "a place fixes the parameters its output has")
        val inBlocks = errors.filter { (line, _) -> line == "1f" || line == "1" || line == "2" || ".call(" in line }
        assertEquals(listOf("1", "1f", "2"), inBlocks.map { it.first }.sorted(), errors.toString())
    }

    @Test
    fun `pins of a graph get their types from the script it compiles to`() {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("trust", "0")),
            nodes = listOf(
                StoryNode.Start("s"),
                StoryNode.Expression("text", "\"a\""),
                StoryNode.Expression("sum", "trust + 1"),
                StoryNode.Pure("list", "list", count = 1),
                StoryNode.Loop("each", ""),
                StoryNode.Code("block", "val found = 2\nfound.toString()"),
            ),
            links = listOf(
                StoryLink("s", StoryPins.NEXT, "each", StoryPins.IN),
                StoryLink("text", StoryPins.VALUE, "list", "a"),
                StoryLink("list", "list", "each", StoryPins.ITEMS),
            ),
        )
        val pins = StoryGraphCompiler.pins(graph, StoryFunctions.Builtin).entries.toList()
        val script = StoryGraphCompiler.compile(graph).code + pins.withIndex().joinToString("") { (index, pin) ->
            "\nsuspend fun storyPinOf$index() {\nval storyPin$index = (\n${pin.value}\n)\n}"
        }
        val found = compiler.environment.analyzer.propertyTypes("graph.stage.kts", script, "storyPin").types
        val types =
            found.entries.associate { (name, type) -> pins[name.removePrefix("storyPin").toInt()].key to type }
        assertEquals("kotlin.String", types["text" to StoryPins.VALUE])
        assertEquals("kotlin.Int", types["sum" to StoryPins.VALUE])
        assertEquals("kotlin.collections.List<kotlin.String>", types["list" to "list"])
        assertEquals("kotlin.String", types["each" to StoryPins.ITEM])
        assertEquals("kotlin.String", types["block" to StoryPins.VALUE])
    }

    @Test
    fun `the editor reports mutable and unserializable captures`() {
        val errors = diagnostics(
            """
            fun start(runs: StoryRuns) {
                var counter = 0
                val lock = Any()
                runs.launch("main") { log(counter.toString()); log(lock.toString()) }
            }
        """
        )
        assertTrue(
            errors.any { it.message.contains("can share 'var counter' only with the @State function or lambda that declares it directly") },
            errors.toString()
        )
        assertTrue(errors.any { it.message.contains("'lock' cannot be saved") }, errors.toString())
    }

    @Test
    fun `the editor validates stage declarations and argument types`() {
        val errors = diagnostics(
            """
            @State fun plain() {}
            @State suspend fun <T> generic(value: T) {}
            @State suspend fun unsafe(lock: Any): Any = lock
            fun outer() { @State suspend fun local() {} }
        """
        )
        listOf(
            "must be a suspend function",
            "cannot have type parameters",
            "'lock' cannot be",
            "The result of a stage is saved",
            "top level"
        ).forEach { expected ->
            assertTrue(errors.any { expected in it.message }, "$expected: $errors")
        }
    }

    @Test
    fun `safe helpers and captures do not receive narrative errors`() {
        val errors = diagnostics(
            """
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
        """
        ).filter { it.severity.isError() }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `the editor reports stage calls in lambdas and exception handlers`() {
        val errors = diagnostics(
            """
            @State suspend fun step() {}
            @State suspend fun main() {
                listOf(1).forEach { step() }
                try { log("try") } catch (failure: Exception) { step() } finally { step() }
            }
        """
        )
        assertTrue(errors.any { "cannot be called from a lambda" in it.message }, errors.toString())
        assertEquals(2, errors.count { "cannot be called in catch or finally" in it.message }, errors.toString())
    }

    @Test
    fun `the editor reports a local the story cannot keep past a checkpoint or a stage call`() {
        fun errors(body: String) = diagnostics(
            """
            @State suspend fun step() {}
            @State suspend fun main() {
            $body
            }
        """
        ).filter { it.severity.isError() }

        val afterCheckpoint = errors(
            """
            val iterator = listOf(1).iterator()
            checkpoint("gate")
            log(iterator.next().toString())
            """
        )
        assertTrue(
            afterCheckpoint.any { "'iterator' is used after checkpoint 'gate'" in it.message },
            afterCheckpoint.toString()
        )
        val afterStage = errors(
            """
            val iterator = listOf(1).iterator()
            step()
            log(iterator.next().toString())
            """
        )
        assertTrue(afterStage.any { "'iterator' is used after the call 'step'" in it.message }, afterStage.toString())
        val inLoop = errors(
            """
            val iterator = listOf(1).iterator()
            while (iterator.hasNext()) {
                log(iterator.next().toString())
                step()
            }
            """
        )
        assertTrue(inLoop.any { "'iterator'" in it.message }, inLoop.toString())
        val operand = errors("""log(listOf(listOf(1).iterator(), step()).toString())""")
        assertTrue(operand.any { "A value computed before the call 'step'" in it.message }, operand.toString())
        val elements = errors("""for (item in listOf(Any())) step()""")
        assertTrue(elements.any { "goes over a copy of its elements" in it.message }, elements.toString())
    }

    @Test
    fun `a local read only where the stage does not go on after the call need not be saved`() {
        val errors = diagnostics(
            """
            @State suspend fun step() {}
            @State suspend fun main(flag: Boolean) {
                val iterator = listOf(1).iterator()
                if (flag) step() else log(iterator.next().toString())
                val other = listOf(2).iterator()
                if (flag) {
                    step()
                    return
                }
                log(other.next().toString())
                var replaced = listOf(3).iterator()
                step()
                replaced = listOf(4).iterator()
                log(replaced.next().toString())
                for (index in 0 until 3) step()
            }
        """
        ).filter { it.severity.isError() }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `the editor validates checkpoint placement and names`() {
        val errors = diagnostics(
            """
            suspend fun helper() { checkpoint("outside") }
            @State suspend fun main() {
                checkpoint("gate")
                checkpoint("gate")
                checkpoint(StageTrace.token)
            }
        """
        )
        listOf("must be called directly", "appears twice", "needs a constant name").forEach { expected ->
            assertTrue(errors.any { expected in it.message }, "$expected: $errors")
        }
    }

    @Test
    fun `the editor validates actions and duplicate stage names`() {
        val errors = diagnostics(
            """
            @State("same") suspend fun first() {}
            @State("same") suspend fun second() {}
            suspend fun invalid() = action { perform { } }
            @Action suspend fun unsafe(lock: Any) = action { perform { } }
            @Action suspend fun missing(@Unsaved lock: Any) = action { perform { } }
        """
        )
        listOf(
            "Two stages", "must be the body of an @Action", "'lock' cannot be saved", "needs one"
        ).forEach { expected ->
            assertTrue(errors.any { expected in it.message }, "$expected: $errors")
        }
    }

    @Test
    fun `the editor rejects function references and stored ordinary lambdas`() {
        val errors = diagnostics(
            """
            suspend fun helper() {}
            fun start(runs: StoryRuns) {
                val body: @State suspend () -> Unit = {}
                runs.launch("one", ::helper)
                runs.launch("two", body)
            }
        """
        )
        assertEquals(2, errors.count { "a regular function value has no saved stage" in it.message }, errors.toString())
    }

    @Test
    fun `lambda arguments and results have the same persistence restrictions as named stages`() {
        val errors = diagnostics(
            """
            fun consume(block: @State suspend (Any) -> Unit) {}
            fun produce(block: @State suspend () -> Any) {}
            fun start() {
                consume { lock -> log(lock.toString()) }
                produce { Any() }
            }
        """
        )
        assertTrue(errors.any { "'lock' cannot be" in it.message }, errors.toString())
        assertTrue(errors.any { "The result of a stage is saved" in it.message }, errors.toString())
    }

    @Test
    fun `unfinished stage code produces diagnostics without breaking editor analysis`() {
        val errors = diagnostics(
            """
            @State suspend fun main() {
                async("timer") {
                    val captured =
                }
            }
        """
        )
        assertTrue(errors.any { it.severity.isError() }, errors.toString())
    }

    @Test
    fun `a rewind to a checkpoint the script does not have is a warning`() {
        val warnings = diagnostics(
            """
            @State
            suspend fun main() {
                checkpoint("arena")
                rewind("arnea")
            }
        """
        ).filter { !it.severity.isError() }
        assertTrue(warnings.any { "No checkpoint of this script is named 'arnea'" in it.message }, warnings.toString())
    }

    @Test
    fun `the names of checkpoints are offered inside rewind`() {
        val source = """
            @State
            suspend fun main() {
                checkpoint("arena")
                checkpoint("boss")
                rewind("ar")
            }
        """.trimIndent()
        val items = ArrayList<String>()
        compiler.environment.analyzer.completions(
            "names.stage.kts", source, source.indexOf("\"ar\")") + 3, CompletionSink { batch ->
                batch.forEach { items += it.show }
                true
            })
        assertEquals(listOf("arena"), items)
    }
}
