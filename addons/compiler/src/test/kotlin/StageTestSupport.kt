import StageTrace.release
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryHandle
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryInstances
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryOwner
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryRuns
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.NODE_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.io.path.createTempDirectory
import kotlin.test.fail

/** The base of the scripts stage tests compile. */
abstract class StageTestScript

/** What test scripts write down and wait for; it outlives a simulated restart, like the world does. */
object StageTrace {
    val events = mutableListOf<String>()

    /** A value scripts read and tests change, to make code behave differently after a restart. */
    @JvmStatic
    var token: String = ""

    /** What actions of test scripts place in the world; like the world, it outlives a restart and is not rewound. */
    @JvmStatic
    val blocks = linkedSetOf<String>()
    private val released = HashSet<String>()
    private val waiting = HashMap<String, MutableList<CompletableDeferred<Unit>>>()

    @JvmStatic
    fun log(event: String) {
        events += event
    }

    /** Suspends until the test calls [release] with [name]; returns at once if it already did. */
    @JvmStatic
    suspend fun await(name: String) {
        if (name in released) return
        CompletableDeferred<Unit>().also { waiting.getOrPut(name, ::ArrayList) += it }.await()
    }

    fun release(name: String) {
        released += name
        waiting.remove(name)?.forEach { it.complete(Unit) }
    }

    /** Cancels what waits in [await] with [name], as an NPC channel that another owner takes cancels its action. */
    fun cancel(name: String) {
        waiting.remove(name)?.forEach { it.cancel(CancellationException("'$name' was taken")) }
    }

    /** Makes [await] with [name] wait again, as before it was released. */
    fun hold(name: String) {
        released -= name
    }

    fun take(): List<String> = events.toList().also { events.clear() }

    fun reset() {
        events.clear()
        token = ""
        blocks.clear()
        released.clear()
        waiting.clear()
    }
}

/** What the narrative addon adds to node scripts in the game. */
private val NARRATIVE_IMPORTS = listOf(
    "ru.hollowhorizon.hollowengine.addons.narrative.story.*",
    "ru.hollowhorizon.hollowengine.addons.narrative.npcs.*",
)

/** Compiles stage scripts with the real compiler and plugin, as `.stage.kts` or as a node of the game. */
class StageCompiler : AutoCloseable {
    val environment = ScriptingEnvironmentImpl(
        javaHome = File(System.getProperty("java.home")),
        classpath = System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotBlank)
            .map(::File).filter(File::exists),
        scriptTypes = listOf(
            ScriptClassProvider(
                extension = ".stage.kts",
                baseClass = StageTestScript::class.qualifiedName!!,
                defaultImports = listOf(
                    "ru.hollowhorizon.hollowengine.addons.narrative.story.*",
                    "StageTrace.log",
                    "StageTrace.await"
                ),
            ),
            DefaultScriptDefinitions.providerFor("story.$NODE_SCRIPT_EXTENSION")!!.let { node ->
                node.copy(defaultImports = node.defaultImports + NARRATIVE_IMPORTS + "StageTrace.log" + "StageTrace.await")
            },
        ),
        mappings = Mappings.EMPTY,
    ).also { ScriptingEnvironment.INSTANCE = it }

    private val sources = createTempDirectory("stages").toFile()

    /** Compiles [code] from a file, as the game compiles scripts, so the same [name] keeps its path across versions. */
    fun compile(name: String, code: String): CompiledScript =
        compileFile(name, code).getOrElse { fail("Expected the script to compile: $it") }

    /** The errors compiling [code] reports; fails if it compiles. */
    fun errors(code: String, name: String = "broken"): String {
        val failure = compileFile(name, code).exceptionOrNull() ?: fail("Expected the script not to compile:\n$code")
        return failure.toString()
    }

    /** Compiles [code] as the file [fileName], with [others] written beside it for it to import. */
    fun compileAs(fileName: String, code: String, others: Map<String, String> = emptyMap()): Result<CompiledScript> {
        others.forEach { (name, text) -> sources.resolve(name).apply { parentFile.mkdirs() }.writeText(text.trimIndent()) }
        return environment.compiler.compile(sources.resolve(fileName).apply { writeText(code.trimIndent()) })
    }

    private fun compileFile(name: String, code: String): Result<CompiledScript> = compileAs("$name.stage.kts", code)

    override fun close() {
        sources.deleteRecursively()
        sources.deleteRecursively()
        ScriptingEnvironment.clear()
        environment.close()
        File("hollowengine").deleteRecursively()
    }
}

/**
 * A script instance with an owner of stories, which can be stopped and brought back from what it saved, the way
 * a server restart does: the runs are written out, everything is canceled, a fresh instance of the script is
 * started and the runs are read back.
 */
class StageWorld(private val test: TestScope, private var compiled: CompiledScript) {
    private class Owner(override val scope: CoroutineScope) : StoryOwner {
        override val server: MinecraftServer get() = error("There is no server in stage tests")
        override val entity: Entity? get() = null
    }

    private var scope = newScope()
    var instance: Any = execute()
        private set
    var runs = StoryRuns(Owner(scope))
        private set

    val key: String get() = StoryInstances.keyOf(instance)

    fun launch(stage: String, key: String = "main"): StoryHandle<Unit> =
        runs.launch(key, this.key, stage).also { settle() }

    /** Calls a function the script declares with [arguments]. */
    fun call(name: String, vararg arguments: Any?): Any? {
        val method = instance.javaClass.declaredMethods.first { it.name == name && it.parameterCount == arguments.size }
        method.trySetAccessible()
        return method.invoke(instance, *arguments).also { settle() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun settle() = test.advanceUntilIdle()

    fun release(name: String) {
        StageTrace.release(name)
        settle()
    }

    /** What the runs would be written to the world save as. */
    fun save(): CompoundTag = runs.save()

    /**
     * Stops everything and starts it again from the save, optionally with the script recompiled as [changed].
     * With [startScript] false the stories are read back before the script runs again; [startScript] runs it.
     */
    fun restart(changed: CompiledScript? = null, startScript: Boolean = true) {
        val saved = save()
        scope.cancel()
        settle()
        changed?.let { compiled = it }
        scope = newScope()
        if (startScript) instance = execute()
        runs = StoryRuns(Owner(scope))
        runs.load(saved)
        settle()
    }

    fun startScript() {
        instance = execute()
        settle()
    }

    fun stop() {
        scope.cancel()
        settle()
    }

    private fun newScope() = CoroutineScope(test.coroutineContext[ContinuationInterceptor]!! + SupervisorJob())

    private fun execute(): Any {
        val instance = compiled.execute<Any> { }.getOrElse { fail("The script failed to start: $it") }
        StoryInstances.register(instance, scope)
        return instance
    }
}

/** A test that compiles stage scripts, with a fresh trace and compiler for every test. */
abstract class StageTest {
    protected lateinit var compiler: StageCompiler

    @BeforeTest
    fun setUpStages() {
        StageTrace.reset()
        compiler = StageCompiler()
    }

    @AfterTest
    fun tearDownStages() = compiler.close()

    protected fun TestScope.world(name: String, code: String) = StageWorld(this, compiler.compile(name, code))
}
