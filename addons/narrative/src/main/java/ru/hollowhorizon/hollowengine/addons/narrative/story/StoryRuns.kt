package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import java.util.UUID

/**
 * Whoever a story runs for: the server, an entity or player, or a node. The owner keeps its runs and
 * decides how long they live: a run of a player sleeps while the player is offline, a run of a node while
 * the node does not run.
 */
interface StoryOwner {
    /** Where the runs of this owner execute; it ends when the owner goes away. */
    val scope: CoroutineScope

    val server: MinecraftServer

    /** The entity the stories are about, if the owner has one. */
    val entity: Entity?

    /** The context a stage of the run [path] sees. */
    fun context(path: String): StoryContext = StoryContext(this, path)
}

/** What a stage knows about the run it is part of; see [context]. */
class StoryContext internal constructor(val owner: StoryOwner, val run: String) {
    val server: MinecraftServer get() = owner.server

    val entity: Entity get() = owner.entity ?: throw StoryException("Story '$run' does not belong to an entity")

    val player: ServerPlayer get() = entity as? ServerPlayer ?: throw StoryException("Story '$run' does not belong to a player")
}

/** The runs of one [owner], by key. */
class StoryRuns(val owner: StoryOwner) {
    private val runs = LinkedHashMap<String, StoryRun>()

    /**
     * Starts [block] as the run [key] and returns its handle; a run already going under [key] is returned as it
     * is. [block] must be a lambda written at the call, which the compiler turns into a stage.
     */
    fun launch(key: String, block: @State suspend () -> Unit): StoryHandle<Unit> {
        val lambda = block as? StageLambda
            ?: throw StoryException("launchStory(\"$key\") takes a lambda written in place, not a function value")
        return start(key) { run -> lambda.entry(run) }
    }

    /** Starts [stage] of the running script [instance] as the run [key], replacing a run already under [key]. */
    fun launch(key: String, instance: String, stage: String): StoryHandle<Unit> {
        if (!StoryInstances.hasStage(instance, stage)) throw StoryException("Script '$instance' has no stage '$stage'")
        stop(key)
        return start(key) { run -> StoryFrame(run, instance, stage) }
    }

    /** Starts the `.story` [address] played to [with] as the run [key], replacing a run already under [key]. */
    fun launchScene(key: String, address: String, with: List<ServerPlayer>): StoryHandle<Unit> {
        stop(key)
        return start(key) { run -> SceneStage.lambda(address, with).entry(run) }
    }

    /** Stops the run [key]; returns whether there was one. */
    fun stop(key: String): Boolean {
        val run = runs.remove(key) ?: return false
        run.stop()
        return true
    }

    operator fun get(key: String): StoryHandle<Unit>? = runs[key]?.let { StoryHandle(it, null) }

    val keys: Set<String> get() = runs.keys

    /** Everything to save. */
    fun save(): CompoundTag = CompoundTag().apply { runs.forEach { (key, run) -> put(key, run.save()) } }

    /**
     * Restores what [save] wrote and starts it. A story that runs in a script which no longer exists is removed:
     * nothing would ever wake it.
     */
    fun load(tag: CompoundTag) {
        tag.allKeys.filter { it !in runs }.forEach { key ->
            val run = StoryRun.load(this, key, null, tag.getCompound(key))
            if (run.state.done) return@forEach
            runs[key] = run
            val gone = run.tree().flatMap { (it.frames + it.pending).asSequence() }.map { it.instance }.firstOrNull(StoryInstances::isGone)
            if (gone != null) run.discard("its script '$gone' no longer exists") else run.start()
        }
    }

    /** Wakes every sleeping run. */
    fun wake() = runs.values.toList().forEach(StoryRun::start)

    internal fun holds(run: StoryRun): Boolean = runs[run.name] === run

    /** The run at [path]: a story under its key, then its branches by name. */
    internal fun find(path: String): StoryRun? {
        val names = path.split('/')
        return names.drop(1).fold(runs[names.first()] ?: return null) { run, name -> run.children[name] ?: return null }
    }

    internal fun ended(run: StoryRun) {
        if (runs[run.name] === run) runs.remove(run.name)
    }

    internal fun waitFor(instance: String, run: StoryRun) = StoryWaits.instance(instance, run)

    internal fun waitFor(entity: UUID, run: StoryRun) = StoryWaits.entity(entity, run)

    private fun start(key: String, entry: (StoryRun) -> StoryFrame): StoryHandle<Unit> {
        require('/' !in key && key.isNotBlank()) { "A story key must be non-blank and contain no '/': '$key'" }
        runs[key]?.let { return StoryHandle(it, null) }
        val run = StoryRun(this, key, null)
        run.pending += entry(run)
        run.history.add(key, run, emptyList())
        runs[key] = run
        run.start()
        return StoryHandle(run, null)
    }
}

/** Runs that sleep until a script or an entity they need is back. */
internal object StoryWaits {
    private val instances = HashMap<String, MutableSet<StoryRun>>()
    private val entities = HashMap<UUID, MutableSet<StoryRun>>()

    init {
        StoryInstances.onAvailable { key -> instances.remove(key)?.forEach(StoryRun::start) }
    }

    fun instance(key: String, run: StoryRun) {
        instances.getOrPut(key, ::LinkedHashSet) += run
    }

    fun entity(uuid: UUID, run: StoryRun) {
        entities.getOrPut(uuid, ::LinkedHashSet) += run
    }

    /** The script [id] could not start: the stories waiting for an instance of it go. */
    fun failed(id: ScriptId) {
        instances.keys.filter { StoryInstances.scriptOf(it) == id }.forEach { key ->
            instances.remove(key)?.forEach { it.discard("its script '$key' could not start") }
        }
    }

    fun entityLoaded(uuid: UUID) {
        entities.remove(uuid)?.forEach(StoryRun::start)
    }

    fun clear() {
        instances.clear()
        entities.clear()
    }
}
