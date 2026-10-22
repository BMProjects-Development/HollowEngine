package ru.hollowhorizon.hollowengine.common.scripting.execution

import kotlinx.coroutines.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.dispatcher
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerEvent
import ru.hollowhorizon.hollowengine.common.network.ScriptExecutionState
import ru.hollowhorizon.hollowengine.common.network.ScriptExecutionStatePacket
import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeManager
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import ru.hollowhorizon.hollowengine.common.scripting.nodes.nodeSave
import ru.hollowhorizon.hollowengine.common.scripting.nodes.startNode
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Runs scripts from the editor and tells authorized players what is running, including nodes started
 * by commands, reloads and world restoration. Owned by the server thread.
 */
class ScriptRunService(
    private val server: MinecraftServer,
    scope: CoroutineScope,
    private val nodes: NodeManager,
) {
    private var disposed = false
    private var synchronizationScheduled = false
    private val tracker = ScriptRunTracker(scope, ::requestSynchronize)
    private val failures = mutableMapOf<String, String>()
    private val updates = ScriptExecutionUpdates<ServerPlayer, ScriptExecutionState>(ScriptExecutionState())

    init {
        nodes.onExecutionChanged(::requestSynchronize)
    }

    fun dispose() {
        disposed = true
    }

    /**
     * Sends what changed once the current server task finishes. Change hooks call this from inside
     * node and job transitions, so the update never reads state that is halfway through changing, and a
     * burst of changes becomes one packet.
     */
    fun requestSynchronize() {
        if (disposed || synchronizationScheduled) return
        synchronizationScheduled = true
        server.dispatcher.dispatch(EmptyCoroutineContext, Runnable {
            synchronizationScheduled = false
            synchronize()
        })
    }

    private fun synchronize() {
        if (disposed) return
        val paths = tracker.paths() + nodes.executionPaths() + failures.keys
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
        }.onFailure {
            failures[path] = it.message ?: it.javaClass.simpleName
            requestSynchronize()
        }
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
                    if (nodes.contains(path) || nodes.isStopping(path)) return@start
                    server.startNode(path, nodeSave(null), entry = state, compiled = compiled)
                    check(nodes.contains(path)) { "Node initialization failed; see the server log" }
                } else ScriptLoader.executeCompiled<Any?>(id, precompiled = compiled).getOrThrow()
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
        nodes.removeNode(path)
    }
}

/** Joining, leaving and op or deop change who receives the run states. */
@SubscribeEvent
fun onPlayerJoinSynchronizeScriptRuns(event: PlayerEvent.Join) {
    event.player.requestScriptRunsSynchronize()
}

@SubscribeEvent
fun onPlayerLeaveSynchronizeScriptRuns(event: PlayerEvent.Leave) {
    event.player.requestScriptRunsSynchronize()
}

@SubscribeEvent
fun onPermissionsSynchronizeScriptRuns(event: PlayerEvent.PermissionsChanged) {
    event.player.requestScriptRunsSynchronize()
}

private fun Player.requestScriptRunsSynchronize() {
    if (this is ServerPlayer) server.runtimeContext.scriptRuns.requestSynchronize()
}
