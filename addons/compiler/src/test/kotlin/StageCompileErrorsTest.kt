import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains

/** What a stage cannot do is a compilation error that says why, not a story that breaks after a restart. */
class StageCompileErrorsTest {
    private lateinit var compiler: StageCompiler

    @BeforeTest
    fun setUp() {
        compiler = StageCompiler()
    }

    @AfterTest
    fun tearDown() = compiler.close()

    private fun assertError(expected: String, code: String) = assertContains(compiler.errors(code), expected)

    @Test
    fun `a stage cannot be called from a lambda inside a stage`() = assertError(
        "cannot be called from a lambda", """
        @State suspend fun step() {}

        @State
        suspend fun main() {
            listOf(1, 2).forEach { step() }
        }
    """
    )

    @Test
    fun `a stage cannot be called in catch or finally`() {
        assertError(
            "cannot be called in catch or finally", """
            @State suspend fun step() {}

            @State
            suspend fun main() {
                try { log("try") } catch (failure: Exception) { step() }
            }
        """
        )
        assertError(
            "cannot be called in catch or finally", """
            @State suspend fun step() {}

            @State
            suspend fun main() {
                try { log("try") } finally { step() }
            }
        """
        )
    }

    @Test
    fun `a local that cannot be saved cannot outlive a stage call`() = assertError(
        "'iterator', used after the stage call 'step#1'", """
        @State suspend fun step() {}

        @State
        suspend fun main() {
            val iterator = listOf(1).iterator()
            step()
            log(iterator.next().toString())
        }
    """
    )

    @Test
    fun `a value computed before a stage call in the same expression must be saveable`() = assertError(
        "A value computed before the stage call 'name#1'", """
        @State suspend fun name(): String = "a"

        fun join(first: Any, second: String) = log(first.toString() + second)

        @State
        suspend fun main() {
            join(Any(), name())
        }
    """
    )

    @Test
    fun `arguments and results of stages must be saveable`() {
        assertError(
            "'target' cannot be", """
            @State suspend fun aim(target: Any) {}
        """
        )
        assertError(
            "The result of a stage is saved", """
            @State suspend fun make(): Any = Any()
        """
        )
        assertError(
            "cannot have type parameters", """
            @State suspend fun <T> pass(value: T) {}
        """
        )
        assertError(
            "'this' cannot be", """
            @State suspend fun Any.shout() {}
        """
        )
    }

    @Test
    fun `a stage must be a top level suspend function of a script`() {
        assertError(
            "must be a suspend function", """
            @State fun plain() {}
        """
        )
        assertError(
            "must be declared at the top level of a script", """
            class Holder {
                @State suspend fun inner() {}
            }
        """
        )
    }

    @Test
    fun `two stages cannot share a name`() = assertError(
        "Two stages of this script are named 'same'", """
        @State(name = "same") suspend fun one() {}

        @State(name = "same") suspend fun two() {}
    """
    )

    @Test
    fun `checkpoints need a constant unique name inside a stage`() {
        assertError(
            "must be called directly in a @State function", """
            suspend fun helper() = checkpoint("outside")
        """
        )
        assertError(
            "needs a constant name", """
            @State
            suspend fun main() {
                val name = "dynamic" + log("x")
                checkpoint(name)
            }
        """
        )
        assertError(
            "appears twice", """
            @State
            suspend fun main() {
                checkpoint("gate")
                checkpoint("gate")
            }
        """
        )
    }

    @Test
    fun `a stage lambda saves what it captures so it can capture only values that can be saved`() {
        assertError(
            "can share 'var counter' only with the @State function or lambda that declares it directly", """
            fun start(runs: StoryRuns) {
                var counter = 1
                counter++
                runs.launch("story") { log(counter.toString()) }
            }
        """
        )
        assertError(
            "'lock' cannot be saved", """
            fun start(runs: StoryRuns) {
                val lock = Any()
                runs.launch("story") { log(lock.toString()) }
            }
        """
        )
    }

    @Test
    fun `a loop that calls stages cannot go over a sequence`() = assertError(
        "it may never end", """
        @State suspend fun step(value: Int) {}

        @State
        suspend fun main() {
            for (value in generateSequence(1) { it + 1 }) step(value)
        }
    """
    )

    @Test
    fun `a stage lambda takes as many parameters as there are lambda classes for`() = assertError(
        "at most 8 parameters", """
        fun many(block: @State suspend (Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit) = Unit

        fun start() = many { a, b, c, d, e, f, g, h, i -> log("${'$'}a") }
    """
    )

    @Test
    fun `a loop that calls stages cannot go over values a story cannot keep`() = assertError(
        "goes over a copy of its elements", """
        @State suspend fun step() {}

        @State
        suspend fun main() {
            for (lock in listOf(Any(), Any())) step()
        }
    """
    )

    @Test
    fun `action is only written as the body of an action`() = assertError(
        "must be the body of an @Action function", """
        suspend fun helper() = action { perform { } }
    """
    )

    @Test
    fun `the arguments of an action must be serializable`() = assertError(
        "'lock' cannot be saved", """
        @Action
        suspend fun hold(lock: Any) = action { perform { } }
    """
    )

    @Test
    fun `an unsaved argument of an action needs a default`() = assertError(
        "needs one", """
        @Action
        suspend fun hold(@Unsaved lock: Any) = action { perform { } }
    """
    )

    @Test
    fun `a checkpoint cannot share its name with a function of the script`() = assertError(
        "A checkpoint cannot be named 'arena'", """
        @State suspend fun arena() {}

        @State
        suspend fun main() {
            checkpoint("arena")
            arena()
        }
    """
    )
}
