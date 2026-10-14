package ru.hollowhorizon.hollowengine.common.scripting.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.network.ScriptExecutionState
import ru.hollowhorizon.hollowengine.common.network.ScriptExecutionStatePacket
import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import ru.hollowhorizon.hollowengine.common.scripting.nodes.addNode
import ru.hollowhorizon.hollowengine.common.scripting.nodes.nodeStateFunctions
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.state.StateContext
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class ScriptRunService(private val server: MinecraftServer) {
    @Volatile
    private var disposed = false
    private val tracker = ScriptRunTracker(server.runtimeContext.scope) {
        if (!disposed) server.execute { if (!disposed) synchronize() }
    }
    private val failures = ConcurrentHashMap<String, String>()
    private val updates = ScriptExecutionUpdates<ServerPlayer, ScriptExecutionState>(ScriptExecutionState())

    fun dispose() {
        disposed = true
    }

    /** Runs on the server thread; includes nodes started by commands, reloads and world restoration. */
    fun synchronize() {
        if (disposed) return
        val paths = tracker.paths() + server.runtimeContext.nodes.executionPaths() + failures.keys
        val current = paths.associateWith { ScriptExecutionState(status(it), failure(it)) }
        val players = server.playerList.players.filter { it.hasPermissions(PlayerPermissions.GAMEMASTER) }.toSet()
        updates.synchronize(current, players) { player, allowed, reset, states ->
            if (player in server.playerList.players) ScriptExecutionStatePacket(states, allowed, reset).send(player)
        }
    }

    fun status(raw: String): ScriptExecutionStatus {
        val path = ScriptRegistry.display(ScriptRegistry.parse(raw))
        val compiling = tracker.status(path)
        if (compiling != ScriptExecutionStatus.IDLE) return compiling
        val nodes = server.runtimeContext.nodes
        return when {
            nodes.isStopping(path) -> ScriptExecutionStatus.STOPPING
            nodes.isStarting(path) -> ScriptExecutionStatus.STARTING
            nodes.contains(path) -> ScriptExecutionStatus.RUNNING
            else -> ScriptExecutionStatus.IDLE
        }
    }

    fun failure(raw: String): String? = failures[ScriptRegistry.display(ScriptRegistry.parse(raw))]

    fun executePlain(id: ScriptId): Result<Any?> {
        val path = ScriptRegistry.display(id)
        return runCatching {
            require(DefaultScriptDefinitions.providerFor(id.path)?.baseClass == "kotlin.Any") { "Not a plain script: $path" }
            tracker.execute(path) {
                failures.remove(path)
                ScriptLoader.execute<Any?>(id).getOrThrow()
            }
        }.onFailure { failures[path] = it.message ?: it.javaClass.simpleName }
    }

    fun start(raw: String, state: String? = null) {
        val id = ScriptRegistry.parse(raw)
        val path = ScriptRegistry.display(id)
        if (status(path) != ScriptExecutionStatus.IDLE) return
        val provider = DefaultScriptDefinitions.providerFor(id.path) ?: error("Unsupported script: $path")
        val node = provider.baseClass == NodeScript::class.qualifiedName
        require(node || provider.baseClass == "kotlin.Any") { "This script type cannot be started from the editor" }
        require(state == null || node) { "Only node scripts have states" }
        failures.remove(path)
        tracker.start(path) {
            try {
                val compiled = withContext(Dispatchers.IO) { ScriptLoader.compile(id).getOrThrow() }
                coroutineContext.ensureActive()
                tracker.markRunning(path)
                if (node) {
                    require(compiled.implicitReceiverCount == 1) { "An attached node must be started on its entity" }
                    require(state == null || state in compiled.type.java.nodeStateFunctions()) { "Unknown node state: $state" }
                    val nodes = server.runtimeContext.nodes
                    if (nodes.contains(path) || nodes.isStopping(path)) return@start
                    server.addNode(
                        path,
                        tag = null,
                        context = state?.let { StateContext(nextState = it) },
                        compiled = compiled
                    )
                    check(server.runtimeContext.nodes.contains(path)) { "Node initialization failed; see the server log" }
                } else ScriptLoader.execute<Any?>(id).getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failures[path] = failure.message ?: failure.javaClass.simpleName
                HollowEngine.LOGGER.error("Could not run script '{}' from the editor", path, failure)
            }
        }
    }

    fun stop(raw: String) {
        val path = ScriptRegistry.display(ScriptRegistry.parse(raw))
        tracker.stop(path)
        server.runtimeContext.nodes.removeNode(path)
    }
}

@SubscribeEvent
fun onServerTick(event: TickEvent.Server) {
    event.server.runtimeContext.scriptRuns.synchronize()
}
