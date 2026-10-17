package ru.hollowhorizon.hollowengine.client.scripting

import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.server.IntegratedServer
import org.apache.logging.log4j.LogManager
import ru.hollowhorizon.hollowengine.client.scripting.ConsoleScripts.run
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.coroutines.dispatcher
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scripting.CONSOLE_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.console.SnippetRun
import ru.hollowhorizon.hollowengine.common.scripting.console.SnippetRunner

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
        ConsoleScripts.runner.print(value.toString())
    }

    fun print(value: Any?) = println(value)
}

/** Compiles console snippets off the render thread and runs them on it. */
object ConsoleScripts {
    /** Also the name the IDE analyzes the console input under, so both agree on the script type. */
    const val SCRIPT_NAME = "console.$CONSOLE_SCRIPT_EXTENSION"

    internal val runner = SnippetRunner(SCRIPT_NAME, LogManager.getLogger("Console"), "HollowEngine-ConsoleCompiler")

    @Volatile
    private var scope: CoroutineScope? = null

    /**
     * Compiles and runs [code]; [onFinished] is called on the render thread either way. Whatever the
     * snippet's last expression evaluates to is printed, like in a REPL.
     */
    fun run(code: String, onFinished: () -> Unit) {
        val minecraft = Minecraft.getInstance()
        minecraft.coroutineScope.launch {
            try {
                evaluate(code)
            } finally {
                onFinished()
            }
        }
    }

    /** Compiles and runs [code] like [run], and hands back what it printed and returned. */
    suspend fun evaluate(code: String): SnippetRun {
        val minecraft = Minecraft.getInstance()
        return runner.run(code, minecraft.dispatcher) { listOf(currentScope()) }
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
}
