import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/** A moved stage must not retain hidden closures into the function that created it. */
class StageLambdaSafetyTest : StageTest() {
    @Test
    fun `a branch calls an enclosing local function only when what it uses can be saved`() {
        val errors = compiler.errors("""
            @State
            suspend fun main() {
                var timer: StoryHandle<*>? = null
                suspend fun playSing(name: String) {
                    timer?.cancel()
                    timer = async("resetToIdle") { await(name) }
                }
                listOf("left", "right").forEach { direction ->
                    async("input_" + direction) { playSing(direction) }
                }
                await("end")
            }
        """)
        assertContains(errors, "local function 'playSing'")
        assertContains(errors, "'timer', which cannot be saved")
        assertFalse(errors.contains("Exception while generating code"), errors)
    }

    @Test
    fun `an enclosing local function reference has the same diagnostic as a call`() {
        val errors = compiler.errors("""
            fun start(runs: StoryRuns, name: String) {
                fun greet() = log(name)
                runs.launch("main") { listOf(1).forEach { ::greet.invoke() } }
            }
        """)
        assertContains(errors, "local function 'greet'")
        assertFalse(errors.contains("Exception while generating code"), errors)
    }

    @Test
    fun `ordinary function values cannot masquerade as generated stage lambdas`() {
        val errors = compiler.errors("""
            suspend fun helper() {}
            fun start(runs: StoryRuns) { runs.launch("main", ::helper) }
        """)
        assertContains(errors, "a regular function value has no saved stage")
        val stored = compiler.errors("""
            fun start(runs: StoryRuns) {
                val body: @State suspend () -> Unit = { log("hello") }
                runs.launch("main", body)
            }
        """)
        assertContains(stored, "a regular function value has no saved stage")
    }

    @Test
    fun `a branch cannot construct a local class declared outside it`() {
        assertContains(compiler.errors("""
            fun start(runs: StoryRuns, text: String) {
                class Greeting { fun show() = log(text) }
                runs.launch("main") { Greeting().show() }
            }
        """), "local class 'Greeting'")
    }

    @Test
    fun `a branch can declare its own local functions and call top level helpers`() = runTest {
        val world = world("localHelpers", """
            fun greet(name: String) = log(name)

            @State
            suspend fun main() {
                val name = "left"
                async("input") {
                    fun sing() = greet(name)
                    sing()
                    await("end")
                }
                await("end")
            }
        """)
        world.launch("main")
        world.restart()
        assertEquals(listOf("left", "left"), StageTrace.take())
        world.stop()
    }
}
