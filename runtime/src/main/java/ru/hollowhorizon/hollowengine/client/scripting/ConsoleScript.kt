package ru.hollowhorizon.hollowengine.client.scripting

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.server.IntegratedServer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import ru.hollowhorizon.hollowengine.common.coroutines.dispatcher
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scripting.CONSOLE_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptCompilationException
import java.util.concurrent.Executors
import kotlin.script.experimental.api.constructorArgs

/**
 * Base of snippets typed into the IDE console. A snippet runs on the render thread and works in the
 * menu as well as in a world, so everything that needs one is nullable. Coroutines it launches keep
 * running after it returns, until [ConsoleScripts.stop].
 */
abstract class ConsoleScript(scope: CoroutineScope) : CoroutineScope by scope {
    val minecraft: Minecraft get() = Minecraft.getInstance()

    val player: LocalPlayer? get() = minecraft.player

    val level: ClientLevel? get() = minecraft.level

    /** The server of a singleplayer world. Touch it from its own thread: `server?.execute { ... }`. */
    val server: IntegratedServer? get() = minecraft.singleplayerServer

    /** Prints to the console, not to the game's standard output. */
    fun println(value: Any?) {
        ConsoleScripts.LOGGER.info(value.toString())
    }

    fun print(value: Any?) = println(value)
}

/** Compiles console snippets off the render thread and runs them on it. */
object ConsoleScripts {
    /** Also the name the IDE analyzes the console input under, so both agree on the script type. */
    const val SCRIPT_NAME = "console.$CONSOLE_SCRIPT_EXTENSION"

    internal val LOGGER: Logger = LogManager.getLogger("Console")

    private val compiler = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HollowEngine-ConsoleCompiler").apply { isDaemon = true }
    }

    @Volatile
    private var scope: CoroutineScope? = null

    /**
     * Compiles and runs [code]; [onFinished] is called on the render thread either way. Whatever the
     * snippet's last expression evaluates to is printed, like in a REPL.
     */
    fun run(code: String, onFinished: () -> Unit) {
        val environment = ScriptingEnvironment.currentOrNull()
        if (environment == null) {
            LOGGER.warn("Kotlin snippets need the compiler addon, which is not installed")
            onFinished()
            return
        }
        LOGGER.info("> {}", code.trimEnd())
        compiler.execute {
            val compiled = environment.compiler.compile(SCRIPT_NAME, code)
            Minecraft.getInstance().execute {
                compiled.onSuccess { script ->
                    script.evaluate { constructorArgs(currentScope()) }
                        .onSuccess { result -> if (result.hasValue) LOGGER.info("= {}", result.value) }
                        .onFailure { error -> LOGGER.error("The snippet failed", error) }
                }.onFailure(::reportCompilationFailure)
                onFinished()
            }
        }
    }

    /** Whether coroutines a snippet launched are still alive. */
    val hasRunningWork: Boolean
        get() = scope?.coroutineContext?.job?.children?.any(Job::isActive) == true

    /** Cancels every coroutine snippets have launched. */
    fun stop() {
        scope?.cancel()
        scope = null
    }

    private fun currentScope(): CoroutineScope = scope ?: CoroutineScope(
        SupervisorJob() + Minecraft.getInstance().dispatcher + LogicalSide.CLIENT + CoroutineName("Console snippets"),
    ).also { scope = it }

    private fun reportCompilationFailure(error: Throwable) {
        val reports = (error as? ScriptCompilationException)?.reports
        if (reports == null) {
            LOGGER.error("The snippet did not compile", error)
            return
        }
        reports.filter { it.severity.isError() }.forEach { report ->
            val start = report.range.start
            if (start.line < 0) LOGGER.error(report.message)
            else LOGGER.error("{}:{}: {}", start.line + 1, start.column + 1, report.message)
        }
    }
}
