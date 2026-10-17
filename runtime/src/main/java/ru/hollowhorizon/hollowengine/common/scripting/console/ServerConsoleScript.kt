package ru.hollowhorizon.hollowengine.common.scripting.console

import kotlinx.coroutines.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.coroutines.dispatcher
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scripting.SERVER_CONSOLE_SCRIPT_EXTENSION

/**
 * Base of snippets run on the logical server: a dedicated one, or the one inside a singleplayer or LAN
 * world. A snippet runs on the server thread, and the coroutines it launches keep running after it
 * returns, until [ServerConsoleScripts.stop] or until the server stops.
 */
abstract class ServerConsoleScript(scope: CoroutineScope, val server: MinecraftServer) : CoroutineScope by scope {
    val overworld: ServerLevel get() = server.overworld()

    val players: List<ServerPlayer> get() = server.playerList.players

    /**
     * The player hosting a singleplayer or LAN world, or the only one on a dedicated server; null when
     * that does not name a single player.
     */
    val player: ServerPlayer?
        get() = players.firstOrNull { server.isSingleplayerOwner(it.gameProfile) } ?: players.singleOrNull()

    /** Prints to the console, not to the game's standard output. */
    fun println(value: Any?) {
        ServerConsoleScripts.runner.print(value.toString())
    }

    fun print(value: Any?) = println(value)
}

/** Compiles server snippets off the server thread and runs them on it. */
object ServerConsoleScripts {
    const val SCRIPT_NAME = "console.$SERVER_CONSOLE_SCRIPT_EXTENSION"

    internal val runner = SnippetRunner(SCRIPT_NAME, LogManager.getLogger("Server Console"), "HollowEngine-ServerConsoleCompiler")

    /** Touched only on the server thread, where snippets are constructed. */
    private var scope: CoroutineScope? = null
    private var scopeServer: MinecraftServer? = null

    /** Compiles and runs [code] on [server]'s thread, and hands back what it printed and returned. */
    suspend fun evaluate(server: MinecraftServer, code: String): SnippetRun =
        runner.run(code, server.dispatcher) { listOf(scopeOf(server), server) }

    /** Cancels every coroutine server snippets have launched. Call it on the server thread. */
    fun stop() {
        scope?.cancel()
        scope = null
        scopeServer = null
    }

    /** A child of the server's own scope, so a stopping server takes the snippets' coroutines with it. */
    private fun scopeOf(server: MinecraftServer): CoroutineScope {
        val current = scope
        if (current != null && scopeServer === server && current.coroutineContext.job.isActive) return current
        return CoroutineScope(
            SupervisorJob(server.coroutineScope.coroutineContext.job) + server.dispatcher + LogicalSide.SERVER +
                CoroutineName("Server console snippets"),
        ).also {
            scope = it
            scopeServer = server
        }
    }
}
