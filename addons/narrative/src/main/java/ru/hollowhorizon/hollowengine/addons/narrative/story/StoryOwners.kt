package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
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
import ru.hollowhorizon.hollowengine.common.scripting.ScriptStartedEvent
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeHost
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeSection
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * The owners stories run for, and how each keeps its runs: the server in a file of the world, an entity in its
 * own data, a node in its save.
 */
object StoryOwners {
    private val servers = WeakHashMap<MinecraftServer, StoryRuns>()
    private val entities = WeakHashMap<Entity, StoryRuns>()
    private val nodes = WeakHashMap<NodeScript, StoryRuns>()

    private val ENTITY_STORIES = DataKey("hollowengine-narrative:stories", ForCompoundNBT, null)

    fun server(server: MinecraftServer): StoryRuns = servers.getOrPut(server) { StoryRuns(ServerOwner(server)) }

    fun entity(entity: Entity): StoryRuns {
        check(!entity.level().isClientSide) { "Stories run on the server" }
        return entities.getOrPut(entity) { StoryRuns(EntityOwner(entity)) }
    }

    fun node(node: NodeScript): StoryRuns = nodes.getOrPut(node) { StoryRuns(NodeOwner(node)) }

    /** Makes every script that starts reachable to stories, and gives nodes their runs and their entry stage. */
    @SubscribeEvent
    fun onScriptStarted(event: ScriptStartedEvent) {
        StoryInstances.register(event.script, event.scope) // Каждый скрипт может содержать State-функции, поэтому их нужно зарегистрировать
        val node = event.script as? NodeScript ?: return
        node.section(NODE_SECTION, object : NodeSection {
            override fun save(): Tag? = nodes[node]?.save()?.takeIf { !it.isEmpty }

            override fun load(tag: Tag) {
                (tag as? CompoundTag)?.let(node(node)::load)
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
    fun onLevelLoad(event: LevelEvent.Load) {
        val server = event.level.server ?: return
        if (event.level.dimension() != Level.OVERWORLD) return
        val file = serverFile(server)
        if (!Files.exists(file)) return
        runCatching {
            Files.newInputStream(file).use { it.loadAsNBT() } as? CompoundTag
        }.onFailure { HollowEngine.LOGGER.error("Cannot read the stories of the server from {}", file, it) }.getOrNull()
            ?.let(server(server)::load)
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
    }

    @SubscribeEvent
    fun onEntityLoaded(event: EntityLoadedEvent) {
        val entity = event.entity
        if (entity.level().isClientSide) return
        StoryWaits.entityLoaded(entity.uuid)
        entities[entity]?.let { it.wake(); return }
        entity.data[ENTITY_STORIES]?.let(entity(entity)::load)
    }

    @SubscribeEvent
    fun onEntityRemoved(event: EntityEvent.Removed) {
        if (!event.reason.shouldDestroy()) return
        entities.remove(event.entity)?.let { runs -> runs.keys.toList().forEach(runs::stop) }
    }

    private fun saveServer(server: MinecraftServer) {
        val runs = servers[server] ?: return
        val file = serverFile(server)
        runCatching {
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { runs.save().save(it) }
        }.onFailure { HollowEngine.LOGGER.error("Cannot save the stories of the server to {}", file, it) }
    }

    private fun serverFile(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("hollowengine-narrative-stories.dat")

    private class ServerOwner(override val server: MinecraftServer) : StoryOwner {
        override val scope: CoroutineScope get() = server.runtimeContext.scope
        override val entity: Entity? get() = null
        override fun changed() = Unit
    }

    private class EntityOwner(override val entity: Entity) : StoryOwner {
        override val scope: CoroutineScope get() = entity.coroutineScope
        override val server: MinecraftServer
            get() = entity.server ?: throw StoryException("Entity ${entity.uuid} has no server")

        override fun changed() {
            entity.data[ENTITY_STORIES] = entity(entity).save()
        }
    }

    private class NodeOwner(private val node: NodeScript) : StoryOwner {
        override val scope: CoroutineScope get() = node
        override val server: MinecraftServer get() = node.server
        override val entity: Entity? get() = (node.host as? NodeHost.OfEntity)?.entity
        override fun changed() = Unit
    }

    private const val NODE_SECTION = "hollowengine-narrative:stories"

    /** The run a node started with an entry stage, by `/hollowengine scripting run` or the editor, is kept under. */
    const val NODE_ENTRY_KEY = "main"
}
