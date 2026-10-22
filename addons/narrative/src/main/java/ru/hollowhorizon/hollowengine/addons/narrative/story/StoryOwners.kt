package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.data.DataKey
import ru.hollowhorizon.hollowengine.common.data.data
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityLoadedEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent
import ru.hollowhorizon.hollowengine.common.events.server.ServerEvent
import ru.hollowhorizon.hollowengine.common.scripting.ScriptFailedEvent
import ru.hollowhorizon.hollowengine.common.scripting.ScriptStartedEvent
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeHost
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeSection
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import java.nio.file.Path

/**
 * The owners stories run for, and how each keeps its runs: the server in a file of the world, an entity in its
 * own data, a node in its save.
 */
object StoryOwners {
    private val servers = HashMap<MinecraftServer, StoryRuns>()
    private val entities = HashMap<Entity, StoryRuns>()
    private val nodes = HashMap<NodeScript, StoryRuns>()

    private val ENTITY_STORIES = DataKey("hollowengine-narrative:stories", ForCompoundNBT, null)

    fun server(server: MinecraftServer): StoryRuns = servers.getOrPut(server) { StoryRuns(ServerOwner(server)) }

    fun entity(entity: Entity): StoryRuns {
        check(!entity.level().isClientSide) { "Stories run on the server" }
        return entities.getOrPut(entity) { StoryRuns(EntityOwner(entity)) }
    }

    fun node(node: NodeScript): StoryRuns = nodes[node] ?: throw StoryException("Node '${node.path}' is not running")

    /** The runs of the node [path] the server runs itself, not one attached to an entity. */
    fun serverNode(path: String): StoryRuns? = nodes.entries.firstOrNull { (node, _) -> node.path == path && node.host is NodeHost.Server }?.value

    /** The paths of the nodes the server runs itself. */
    val serverNodes: List<String> get() = nodes.keys.filter { it.host is NodeHost.Server }.map { it.path }

    /** Makes every script that starts reachable to stories, and gives nodes their runs and their entry stage. */
    @SubscribeEvent
    fun onScriptStarted(event: ScriptStartedEvent) {
        StoryInstances.register(event.script, event.scope) // Каждый скрипт может содержать State-функции, поэтому их нужно зарегистрировать
        val node = event.script as? NodeScript ?: return
        val runs = StoryRuns(NodeOwner(node))
        nodes[node] = runs
        event.scope.coroutineContext.job.invokeOnCompletion { nodes.remove(node) }
        node.section(NODE_SECTION, object : NodeSection {
            override fun save(): Tag? = runs.save().takeIf { !it.isEmpty }

            override fun load(tag: Tag) {
                (tag as? CompoundTag)?.let(runs::load)
            }
        })
        val entry = event.entry ?: return
        runCatching {
            node(node).launch(
                NODE_ENTRY_KEY, StoryInstances.keyOf(node), entry
            )
        }.onFailure { HollowEngine.LOGGER.error("Cannot start node '{}' at stage '{}'", node.path, entry, it) }
    }

    @SubscribeEvent
    fun onScriptFailed(event: ScriptFailedEvent) = StoryWaits.failed(event.id)

    @SubscribeEvent
    fun onLevelLoad(event: LevelEvent.Load) {
        val server = event.level.server ?: return
        if (event.level.dimension() != Level.OVERWORLD) return
        StoryDeferredReplays.load(StoryStore(deferredFile(server)).read() ?: CompoundTag())
        StoryStore(serverFile(server)).read()?.let(server(server)::load)
    }

    @SubscribeEvent
    fun onLevelSave(event: LevelEvent.Save) {
        val server = event.level.server ?: return
        if (event.level.dimension() == Level.OVERWORLD) saveServer(server)
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerEvent.Stoping) {
        saveServer(event.server)
        servers.remove(event.server)
        StoryWaits.clear()
        StoryDeferredReplays.clear()
    }

    @SubscribeEvent
    fun onEntityLoaded(event: EntityLoadedEvent) {
        val entity = event.entity
        if (entity.level().isClientSide) return
        StoryWaits.entityLoaded(entity.uuid)
        StoryDeferredReplays.entityLoaded(entity)
        entities[entity]?.let { it.wake(); return }
        entity.data[ENTITY_STORIES]?.let(entity(entity)::load)
    }

    /**
     * A player is the same object after a dimension change and keeps its runs; any other entity leaving its level
     * takes them along. Only a destroyed entity ends its stories, and never a player, which is removed when it
     * respawns as well.
     */
    @SubscribeEvent
    fun onEntityRemoved(event: EntityEvent.Removed) {
        val entity = event.entity
        if (entity is Player && event.reason == Entity.RemovalReason.CHANGED_DIMENSION) return
        val runs = entities.remove(entity) ?: return
        if (event.reason.shouldDestroy() && entity !is Player) runs.keys.toList().forEach(runs::stop)
    }

    private fun saveServer(server: MinecraftServer) {
        if (StoryDeferredReplays.changed) StoryStore(deferredFile(server)).write(StoryDeferredReplays.save())
        val runs = servers[server] ?: return
        StoryStore(serverFile(server)).write(runs.save())
    }

    private fun deferredFile(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("hollowengine-narrative-deferred.dat")

    private fun serverFile(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("hollowengine-narrative-stories.dat")

    private class ServerOwner(override val server: MinecraftServer) : StoryOwner {
        override val scope: CoroutineScope get() = server.runtimeContext.scope
        override val entity: Entity? get() = null
        override fun changed(runs: StoryRuns) = Unit
    }

    private class EntityOwner(override val entity: Entity) : StoryOwner {
        override val scope: CoroutineScope get() = entity.coroutineScope
        override val server: MinecraftServer
            get() = entity.server ?: throw StoryException("Entity ${entity.uuid} has no server")

        override fun changed(runs: StoryRuns) {
            entity.data[ENTITY_STORIES] = runs.save()
        }
    }

    private class NodeOwner(private val node: NodeScript) : StoryOwner {
        override val scope: CoroutineScope get() = node
        override val server: MinecraftServer get() = node.server
        override val entity: Entity? get() = (node.host as? NodeHost.OfEntity)?.entity
        override fun changed(runs: StoryRuns) = Unit
    }

    private const val NODE_SECTION = "hollowengine-narrative:stories"

    /** The run a node started with an entry stage, by `/hollowengine scripting run` or the editor, is kept under. */
    const val NODE_ENTRY_KEY = "main"
}
