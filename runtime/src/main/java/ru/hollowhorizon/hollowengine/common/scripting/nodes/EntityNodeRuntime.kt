package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptEditorInfo
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry

/**
 * Entry point for the nodes attached to entities via `@file:Attach`.
 *
 * The managers themselves live in the entity's `HollowAttachments`, so nodes are created, moved between
 * dimensions, persisted and torn down together with everything else attached to that entity, instead of
 * through a registry of their own. Each node still hangs off a child of the entity's [coroutineScope],
 * so cancelling that scope tears its nodes down.
 */
object EntityNodeRuntime {
    /**
     * Attaches the node [path] to [entity].
     *
     * @param tag the data its `onLoad` handlers receive.
     * @param entry what to start with, see [ru.hollowhorizon.hollowengine.common.scripting.ScriptStartedEvent.entry].
     */
    fun attach(entity: Entity, path: String, tag: CompoundTag? = null, entry: String? = null): Boolean =
        AttachmentRegistry.attachments(entity).nodes.attach(path, tag, entry)

    fun detach(entity: Entity, path: String): Boolean =
        managerOrNull(entity)?.detach(path) ?: false

    fun paths(entity: Entity): Set<String> = managerOrNull(entity)?.paths().orEmpty()

    fun editors(entity: Entity): List<ScriptEditorInfo> =
        managerOrNull(entity)?.editors().orEmpty().sortedBy(ScriptEditorInfo::path)

    fun applyEditorValues(entity: Entity, path: String, values: JsonObject): Boolean =
        managerOrNull(entity)?.applyEditorValues(path, values) ?: false

    /** Stops every attached node of [namespace] on every entity, keeping their state. */
    fun suspendNamespace(namespace: String) {
        forEachManager { it.suspendNamespace(namespace) }
    }

    /** Starts them again once the namespace is back. */
    fun resumeNamespace(namespace: String) {
        forEachManager { it.resumeNamespace(namespace) }
    }

    internal fun reloadChanged(server: MinecraftServer, plan: NodeReloadPlan) {
        AttachmentRegistry.allAttachments().forEach { attachments ->
            if (attachments.entity.server === server && !attachments.entity.level().isClientSide) {
                attachments.nodesOrNull?.reloadChanged(plan)
            }
        }
    }

    private fun managerOrNull(entity: Entity): EntityNodeManager? =
        AttachmentRegistry.attachmentsOrNull(entity)?.nodesOrNull

    private inline fun forEachManager(action: (EntityNodeManager) -> Unit) {
        AttachmentRegistry.allAttachments().forEach { attachments -> attachments.nodesOrNull?.let(action) }
    }
}

/** Per-entity node store. Mirrors [NodeManager] but binds nodes to the entity's coroutine scope. */
class EntityNodeManager(private val entity: Entity) {
    private val nodes = mutableMapOf<String, RunningNode>()
    private val lifecycle = NodeLifecycle { entity.coroutineScope }

    /** Attached nodes whose namespace is currently unavailable. See [NodeManager] for the rationale. */
    private val dormant = mutableMapOf<String, CompoundTag>()

    fun attach(path: String, tag: CompoundTag?, entry: String?): Boolean {
        val canonicalPath = canonicalNodePath(path)
        if (nodes.containsKey(canonicalPath) || lifecycle.isPending(canonicalPath)) return false

        if (tag == null && entry == null) {
            dormant[canonicalPath]?.let { return attachSaved(canonicalPath, it) }
        }
        return start(canonicalPath, nodeSave(tag), entry)
    }

    /** Attaches a node from the state [serialize] wrote for it. */
    private fun attachSaved(canonicalPath: String, nodeTag: CompoundTag, compiled: CompiledScript? = null): Boolean =
        start(canonicalPath, nodeTag, entry = null, compiled)

    private fun start(canonicalPath: String, saved: CompoundTag, entry: String?, compiled: CompiledScript? = null): Boolean {
        dormant[canonicalPath] = saved.copy()
        var accepted = true
        lifecycle.start(canonicalPath) {
            accepted = startNow(canonicalPath, saved, entry, compiled)
        }
        return accepted
    }

    private fun startNow(
        canonicalPath: String,
        saved: CompoundTag,
        entry: String?,
        compiled: CompiledScript?,
    ): Boolean {
        val server = entity.server ?: return false

        val script = buildNode(
            host = NodeHost.OfEntity(entity),
            parentScope = entity.coroutineScope,
            path = canonicalPath,
            saved = saved,
            entry = entry,
            receivers = listOf(server, entity),
            compiled = compiled,
        )
        if (script == null) {
            if (dormant.remove(canonicalPath) != null) {
                HollowEngine.LOGGER.error("Node '{}' is removed from {}: it could not start", canonicalPath, entity.uuid)
            }
            return false
        }

        dormant.remove(canonicalPath)
        nodes[canonicalPath] = RunningNode(script)
        return true
    }

    fun detach(path: String): Boolean {
        val canonicalPath = canonicalNodePath(path)
        val hadPendingStart = lifecycle.isPending(canonicalPath)
        dormant.remove(canonicalPath)
        lifecycle.cancelStart(canonicalPath)
        val removed = nodes.remove(canonicalPath) ?: return hadPendingStart
        lifecycle.stop(canonicalPath, removed.script.coroutineContext.job)
        return true
    }

    fun restart(path: String): Boolean = restart(path, null)

    private fun restart(path: String, compiled: CompiledScript?): Boolean {
        val canonicalPath = canonicalNodePath(path)
        val node = nodes[canonicalPath] ?: return false
        val saved = runCatching { node.persist(node.script.server) }
            .onFailure { HollowEngine.LOGGER.error("Error while restarting entity node '$canonicalPath'", it) }
            .getOrNull() ?: return false

        dormant[canonicalPath] = saved
        lifecycle.stop(canonicalPath, node.script.coroutineContext.job)
        var accepted = true
        lifecycle.start(canonicalPath) {
            val latest = runCatching { node.persist(node.script.server) }
                .onFailure { HollowEngine.LOGGER.error("Error while saving stopped node '$canonicalPath'", it) }
                .getOrDefault(saved)
            nodes.remove(canonicalPath, node)
            dormant[canonicalPath] = latest
            accepted = attachSaved(canonicalPath, latest, compiled)
        }
        return accepted
    }

    internal fun reloadChanged(plan: NodeReloadPlan) {
        val waiting = dormant.filterKeys { it !in nodes && !lifecycle.isPending(it) }
        nodes.toMap().forEach { (path, node) ->
            plan.replacement(path, node.script.binding.fingerprint, expectedReceivers = 2)?.let { restart(path, it) }
        }
        waiting.forEach { (path, saved) ->
            plan.replacement(path, null, expectedReceivers = 2)?.let { attachSaved(path, saved, it) }
        }
    }

    internal fun editors(): List<ScriptEditorInfo> =
        nodes.map { (path, node) -> node.script.editor.describe(path) }

    internal fun applyEditorValues(path: String, values: JsonObject): Boolean {
        val canonicalPath = canonicalNodePath(path)
        val node = nodes[canonicalPath] ?: return false
        return !node.script.editor.apply(values) || restart(canonicalPath)
    }

    fun paths(): Set<String> = nodes.keys + lifecycle.pendingPaths()

    fun serialize(): CompoundTag {
        val tag = CompoundTag()
        dormant.forEach { (path, nodeTag) -> tag.put(path, nodeTag) }
        nodes.forEach { (path, node) ->
            runCatching { tag.put(path, node.persist(node.script.server)) }
                .onFailure { HollowEngine.LOGGER.error("Error while saving entity node '$path'", it) }
        }
        return tag
    }

    /**
     * A node whose namespace is not installed right now stays dormant: [serialize] writes it back out, so
     * turning the addon on again brings it back. A node that is installed but cannot start is removed.
     */
    fun deserialize(tag: CompoundTag) {
        tag.allKeys.forEach { path ->
            val canonicalPath = canonicalNodePath(path)
            val nodeTag = tag.getCompound(path)
            if (nodes.containsKey(canonicalPath) || lifecycle.isPending(canonicalPath)) return@forEach

            val available = ScriptRegistry.source(ScriptRegistry.parse(path).namespace) != null
            val attached = runCatching { available && attachSaved(canonicalPath, nodeTag) }
                .onFailure { HollowEngine.LOGGER.error("Error while deserializing entity node '$path'", it) }
                .getOrDefault(false)

            if (attached || available) return@forEach
            dormant[canonicalPath] = nodeTag
        }
    }

    internal fun suspendNamespace(namespace: String) {
        lifecycle.pendingPaths().filter { ScriptRegistry.parse(it).namespace == namespace }
            .forEach(lifecycle::cancelStart)
        nodes.filterKeys { path -> ScriptRegistry.parse(path).namespace == namespace }
            .forEach { (path, node) ->
                runCatching { dormant[path] = node.persist(node.script.server) }
                    .onFailure { HollowEngine.LOGGER.error("Error while suspending entity node '$path'", it) }
                nodes.remove(path)
                lifecycle.stop(path, node.script.coroutineContext.job)
            }
    }

    internal fun resumeNamespace(namespace: String) {
        dormant.filterKeys { path -> ScriptRegistry.parse(path).namespace == namespace }
            .forEach { (path, nodeTag) ->
                if (path in nodes || lifecycle.isPending(path)) return@forEach
                runCatching { attachSaved(path, nodeTag) }
                    .onFailure { HollowEngine.LOGGER.error("Error while resuming entity node '$path'", it) }
            }
    }
}
