package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.state.StateContext
import ru.hollowhorizon.hollowengine.common.scripting.state.StateExecutor
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.implicitReceivers

/**
 * Holds the server-bound nodes for a single [MinecraftServer]. Ticking is no longer driven from here,
 * nodes subscribe to [ru.hollowhorizon.hollowengine.common.events.tick.TickEvent.Server] through
 * `onUpdate`, so this manager only owns lifecycle, persistence and lookup.
 */
class NodeManager(val server: MinecraftServer) {
    private val nodes = mutableMapOf<String, RunningNode>()

    /**
     * Nodes whose namespace is not available right now: an addon was disabled, or a save mentions one
     * that is not installed. Their data is kept verbatim and written back out on save, so turning the
     * addon on again resumes exactly where it stopped.
     */
    private val dormant = mutableMapOf<String, CompoundTag>()

    private val lifecycle = NodeLifecycle { server.runtimeContext.scope }

    fun serialize(tag: CompoundTag) {
        dormant.forEach { (name, nodeTag) -> tag.put(name, nodeTag) }
        nodes.forEach { (name, entry) ->
            runCatching { tag.put(name, entry.persist(server)) }
                .onFailure { HollowEngine.LOGGER.error("Error while saving node '$name'", it) }
        }
    }

    fun deserialize(tag: CompoundTag) {
        tag.allKeys.forEach { key ->
            runCatching {
                val nodeTag = tag.getCompound(key)
                if (!isAvailable(key)) {
                    dormant[key] = nodeTag
                    HollowEngine.LOGGER.info("Node '{}' is kept dormant: its namespace is not installed", key)
                    return@runCatching
                }
                val extras = nodeTag.getCompound("extras")
                val context = (nodeTag.get("states") as? CompoundTag)
                    ?.let { StateContext.deserialize(it) }
                server.addNode(key, extras, context)
            }.onFailure {
                HollowEngine.LOGGER.error("Error while deserializing node '$key'", it)
            }
        }
    }

    internal fun register(script: NodeScript, executor: StateExecutor, context: StateContext?) {
        dormant.remove(script.path)
        nodes[script.path] = RunningNode(script, executor, context)
        context?.let { executor.start(it) }
    }

    fun removeNode(path: String) {
        val canonicalPath = canonicalNodePath(path)
        dormant.remove(canonicalPath)
        lifecycle.cancelStart(canonicalPath)
        nodes.remove(canonicalPath)?.let { stop(canonicalPath, it) }
    }

    /**
     * Runs [start] once the previous instance of [path], if any, has fully stopped. A node that is still
     * running is restarted rather than duplicated.
     */
    internal fun startAfterStop(path: String, saved: CompoundTag, start: () -> Unit) {
        val canonicalPath = canonicalNodePath(path)
        removeNode(canonicalPath)
        dormant[canonicalPath] = saved

        lifecycle.start(canonicalPath, start)
    }

    private fun stop(path: String, node: RunningNode) {
        lifecycle.stop(path, node.script.coroutineContext.job)
    }

    fun dispose() {
        paths().forEach(::removeNode)
    }

    fun contains(path: String): Boolean = canonicalNodePath(path).let { nodes.containsKey(it) || lifecycle.isPending(it) }
    internal fun isStopping(path: String): Boolean = lifecycle.isStopping(canonicalNodePath(path))
    internal fun isStarting(path: String): Boolean = lifecycle.isPending(canonicalNodePath(path))

    fun paths(): Set<String> = nodes.keys + lifecycle.pendingPaths()
    internal fun executionPaths(): Set<String> = paths() + lifecycle.stoppingPaths()

    internal fun reloadChanged(plan: NodeReloadPlan) {
        val waiting = dormant.filterKeys { it !in nodes && !lifecycle.isPending(it) }
        nodes.toMap().forEach { (path, node) ->
            val compiled = plan.replacement(path, node.script.binding.fingerprint, expectedReceivers = 1) ?: return@forEach
            val saved = runCatching { node.persist(server) }
                .onFailure { HollowEngine.LOGGER.error("Error while restarting server node '$path'", it) }
                .getOrNull() ?: return@forEach
            dormant[path] = saved
            stop(path, node)
            lifecycle.start(path) {
                val latest = runCatching { node.persist(server) }
                    .onFailure { HollowEngine.LOGGER.error("Error while saving stopped node '$path'", it) }
                    .getOrDefault(saved)
                nodes.remove(path, node)
                startSaved(path, latest, compiled)
            }
        }
        waiting.forEach { (path, saved) ->
            plan.replacement(path, null, expectedReceivers = 1)?.let { startSaved(path, saved, it) }
        }
    }

    private fun startSaved(path: String, saved: CompoundTag, compiled: CompiledScript) {
        server.addNode(
            path,
            saved.getCompound("extras"),
            (saved.get("states") as? CompoundTag)?.let { StateContext.deserialize(it) },
            compiled,
        )
    }

    /** Stops the nodes of [namespace] without losing their state. */
    internal fun suspendNamespace(namespace: String) {
        lifecycle.pendingPaths().filter { ScriptRegistry.parse(it).namespace == namespace }
            .forEach(lifecycle::cancelStart)
        nodes.filterKeys { path -> ScriptRegistry.parse(path).namespace == namespace }
            .forEach { (path, entry) ->
                runCatching { dormant[path] = entry.persist(server) }
                    .onFailure { HollowEngine.LOGGER.error("Error while suspending node '$path'", it) }
                nodes.remove(path)
                stop(path, entry)
            }
    }

    /** Starts the nodes of [namespace] again from the state they were suspended with. */
    internal fun resumeNamespace(namespace: String) {
        dormant.filterKeys { path -> ScriptRegistry.parse(path).namespace == namespace }
            .forEach { (path, nodeTag) ->
                if (path in nodes || lifecycle.isPending(path)) return@forEach
                dormant.remove(path)
                runCatching {
                    val context = (nodeTag.get("states") as? CompoundTag)?.let { StateContext.deserialize(it) }
                    server.addNode(path, nodeTag.getCompound("extras"), context)
                }.onFailure { HollowEngine.LOGGER.error("Error while resuming node '$path'", it) }
            }
    }

    private fun isAvailable(path: String): Boolean =
        ScriptRegistry.source(ScriptRegistry.parse(path).namespace) != null
}

internal class RunningNode(
    val script: NodeScript,
    val executor: StateExecutor,
    val context: StateContext?,
) {
    /** The tag this node would be written to the world save as. */
    fun persist(server: MinecraftServer): CompoundTag {
        val serialization = SerializationContext(server, CompoundTag())
        script.onSaveHandlers.forEach { it(serialization) }
        return CompoundTag().apply {
            put("extras", serialization.tag)
            context?.let { put("states", it.serialize()) }
        }.copy()
    }
}

/**
 * Compiles [path], binds it to a fresh child scope of [host]'s scope, runs the script body (which
 * registers its handlers), then replays `onLoad` -> `onStart` -> `@State` states.
 *
 * The bound [NodeScript] is returned so the caller can register it with the owning manager; `null` is
 * returned when compilation is unavailable or fails.
 */
internal fun buildNode(
    host: NodeHost,
    parentScope: CoroutineScope,
    path: String,
    tag: CompoundTag?,
    receivers: List<Any>,
    compiled: CompiledScript? = null,
): Pair<NodeScript, StateExecutor>? {
    val id = ScriptRegistry.parse(path)
    // Nodes are keyed by their path in the world save, so every spelling of one script has to settle on
    // the same string before anything is registered.
    val canonicalPath = ScriptRegistry.display(id)
    val nodeScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    val binding = NodeBinding(host, nodeScope, tag)

    val validate: (CompiledScript) -> Unit = { program ->
        receiverMismatch(program.implicitReceiverCount, host, receivers)?.let { error(it) }
        binding.fingerprint = program.fingerprint
    }
    val configure: ScriptEvaluationConfiguration.Builder.() -> Unit = {
        constructorArgs(canonicalPath, binding)
        implicitReceivers(*receivers.toTypedArray())
    }
    val evaluated = if (compiled == null) {
        ScriptLoader.executeCompiled<NodeScript>(id, validate, configure)
    } else {
        runCatching {
            validate(compiled)
            compiled.execute<NodeScript>(configure).getOrThrow()
        }
    }
    val script = evaluated.onFailure {
        HollowEngine.LOGGER.error("Error while loading $canonicalPath", it)
        nodeScope.cancel()
    }.getOrNull() ?: return null

    return runCatching {
        val executor = StateExecutor(nodeScope)
        script.prepareExecutor(executor)

        tag?.let { extras ->
            val context = SerializationContext(host.server, extras)
            script.onLoadHandlers.forEach { it(context) }
        }

        script.onStartHandlers.forEach { start -> nodeScope.launch { start() } }
        script to executor
    }.onFailure {
        HollowEngine.LOGGER.error("Error while initializing $canonicalPath", it)
        nodeScope.cancel()
    }.getOrNull()
}

/**
 * Explains a receiver count that will not fit, before the evaluator fails on it with a bare
 * `WrongMethodTypeException`.
 *
 * `@file:Attach` adds an implicit receiver, so an attached script cannot be started as a plain server
 * node and the other way round. Imported scripts also become constructor parameters, therefore the
 * receiver count comes from the compilation configuration rather than the generated JVM constructor.
 */
private fun receiverMismatch(expected: Int, host: NodeHost, receivers: List<Any>): String? {
    val given = receivers.size
    if (expected == given) return null

    val wantsMore = expected > given
    val hint = when {
        wantsMore && host is NodeHost.Server ->
            "it declares @file:Attach, so attach it to an entity instead of starting it as a server node"

        !wantsMore && host is NodeHost.OfEntity ->
            "it has no @file:Attach, so start it as a server node instead of attaching it to an entity"

        wantsMore -> "it expects more implicit receivers than this host provides"
        else -> "it expects fewer implicit receivers than this host provides"
    }
    return "Cannot run node script as ${host.describe()}: $hint " +
            "(script expects $expected implicit receivers, this host supplies $given)"
}

private fun NodeHost.describe(): String = when (this) {
    is NodeHost.Server -> "a server node"
    is NodeHost.OfEntity -> "a node attached to ${entity.type.description.string}"
}

/**
 * The spelling a node is stored under. Scripts of the sandbox stay unqualified so world saves written
 * before namespaces existed keep resolving, everything else carries its namespace.
 */
fun canonicalNodePath(path: String): String = ScriptRegistry.display(ScriptRegistry.parse(path))

fun MinecraftServer.addNode(path: String, tag: CompoundTag? = null, context: StateContext? = null) =
    addNode(path, tag, context, null)

internal fun MinecraftServer.addNode(path: String, tag: CompoundTag?, context: StateContext?, compiled: CompiledScript?) {
    val saved = CompoundTag().apply {
        tag?.let { put("extras", it.copy()) }
        context?.let { put("states", it.serialize().copy()) }
    }
    runtimeContext.nodes.startAfterStop(path, saved) {
        val (script, executor) = buildNode(
            host = NodeHost.Server(this),
            parentScope = runtimeContext.scope,
            path = path,
            tag = tag,
            receivers = listOf(this),
            compiled = compiled,
        ) ?: return@startAfterStop

        runtimeContext.nodes.register(script, executor, context)
    }
}

fun MinecraftServer.removeNode(path: String) {
    runtimeContext.nodes.removeNode(path)
}
