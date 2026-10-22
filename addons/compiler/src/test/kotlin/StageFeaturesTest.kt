import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What stages accept besides plain arguments: lambdas with parameters, receivers, arrays, any iterable, edited code. */
class StageFeaturesTest : StageTest() {
    @Test
    fun `a stage lambda with a receiver and parameters saves them like arguments`() = runTest {
        val world = world("lambdaParameters", $$"""
            @State
            suspend fun twice(name: String, body: @State suspend String.(Int) -> Unit) {
                name.body(1)
                log("between")
                name.body(2)
            }

            @State
            suspend fun main() {
                val greeting = "hi"
                twice("alex") { round ->
                    log("$greeting $this $round")
                    await("r$round")
                }
                log("done")
            }
        """
        )
        world.launch("main")
        world.restart()
        assertEquals(listOf("hi alex 1", "hi alex 1"), StageTrace.take())

        world.release("r1")
        world.restart()
        assertEquals(listOf("between", "hi alex 2", "hi alex 2"), StageTrace.take())

        world.release("r2")
        assertEquals(listOf("done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `stages take receivers and context parameters as saved arguments`() = runTest {
        val world = world("receivers", $$"""
            @State
            suspend fun String.greet(times: Int) {
                log("greet $this $times")
                await("g")
                log("bye $this")
            }

            context(prefix: String)
            @State
            suspend fun announce(times: Int) {
                log("$prefix $times")
                await("a")
                "alex".greet(times)
            }

            context(prefix: String)
            fun start(runs: StoryRuns) {
                runs.launch("story") { announce(3) }
            }
        """
        )
        world.call("start", "hello", world.runs)
        world.restart()
        assertEquals(listOf("hello 3", "hello 3"), StageTrace.take())

        world.release("a")
        world.restart()
        assertEquals(listOf("greet alex 3", "greet alex 3"), StageTrace.take())

        world.release("g")
        assertEquals(listOf("bye alex"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `arrays are saved like lists`() = runTest {
        val world = world("arrays", """
            @State
            suspend fun show(names: Array<String>, counts: IntArray) {
                checkpoint("shown")
                log(names.joinToString() + " " + counts.sum())
                await("x")
            }

            @State
            suspend fun main() = show(arrayOf("a", "b"), intArrayOf(1, 2))
        """)
        world.launch("main")
        world.restart()
        assertEquals(listOf("a, b 3", "a, b 3"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a loop over any collection goes over a copy taken when it starts`() = runTest {
        val world = world("iterables", $$"""
            @State
            suspend fun visit(name: String) {
                log("visit $name")
                await(name)
            }

            @State
            suspend fun main() {
                val tags = mutableSetOf("x", "y")
                for (tag in tags) {
                    visit(tag)
                    tags.clear()
                }
                for ((key, value) in mapOf("k" to 1)) visit("$key$value")
                for (letter in "q") visit(letter.toString())
                log("done")
            }
        """
        )
        world.launch("main")
        world.release("x")
        world.restart()
        assertEquals(listOf("visit x", "visit y", "visit y"), StageTrace.take(), "clearing the set does not stop the loop")

        world.release("y")
        world.restart()
        assertEquals(listOf("visit k1", "visit k1"), StageTrace.take())

        world.release("k1")
        world.release("q")
        assertEquals(listOf("visit q", "done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a local whose type changed makes its stage start over`() = runTest {
        fun version(reward: String) = $$"""
            @State
            suspend fun main() {
                log("start")
                val reward = $$reward
                checkpoint("reward")
                log("reward $reward")
                await("x")
            }
        """
        val world = StageWorld(this, compiler.compile("migration", version("100")))
        world.launch("main")
        assertEquals(listOf("start", "reward 100"), StageTrace.take())

        world.restart(compiler.compile("migration", version("\"gold\"")))
        assertEquals(listOf("start", "reward gold"), StageTrace.take(), "an Int does not read as a String, so main starts over")
        world.stop()
    }

    @Test
    fun `a local that became a Float reads the saved Int as a number`() = runTest {
        fun version(reward: String) = $$"""
            @State
            suspend fun main() {
                log("start")
                val reward = $$reward
                checkpoint("reward")
                log("reward $reward")
                await("x")
            }
        """
        val world = StageWorld(this, compiler.compile("floats", version("100")))
        world.launch("main")
        assertEquals(listOf("start", "reward 100"), StageTrace.take())

        world.restart(compiler.compile("floats", version("100.5f")))
        assertEquals(listOf("reward 100.0"), StageTrace.take(), "the NBT number converts, so main resumes after the checkpoint")
        world.stop()
    }

    @Test
    fun `an argument that no longer reads as its type fails the story with its name`() = runTest {
        fun version(type: String, value: String) = $$"""
            @State
            suspend fun pay(amount: $$type) {
                log("pay $amount")
                await("pay")
            }

            @State
            suspend fun main() = pay($$value)
        """
        val world = StageWorld(this, compiler.compile("arguments", version("Int", "100")))
        world.launch("main")
        assertEquals(listOf("pay 100"), StageTrace.take())

        world.restart(compiler.compile("arguments", version("String", "\"gold\"")))
        assertEquals(emptyList(), StageTrace.take())
        assertTrue(world.runs.keys.isEmpty(), "the story cannot go on with an argument it cannot read")
        world.stop()
    }
}
