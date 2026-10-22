package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.serialization.KSerializer
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import kotlin.jvm.internal.CallableReference
import kotlin.jvm.internal.ClassBasedDeclarationContainer
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Starts the story [key] of the server with [block] as its first stage and returns its handle. A story already
 * running under [key] is returned instead, so a trigger may call this every time it fires.
 */
fun MinecraftServer.launchStory(key: String, block: @State suspend () -> Unit): StoryHandle<Unit> =
    StoryOwners.server(this).launch(key, block)

/** Starts the story [key] of this entity; it sleeps while the entity is unloaded and ends with it. */
fun Entity.launchStory(key: String, block: @State suspend () -> Unit): StoryHandle<Unit> =
    StoryOwners.entity(this).launch(key, block)

/** Starts the story [key] of this node; it sleeps while the node does not run. */
fun NodeScript.launchStory(key: String, block: @State suspend () -> Unit): StoryHandle<Unit> =
    StoryOwners.node(this).launch(key, block)

/** Stops the story [key] of the server; returns whether it was running. */
fun MinecraftServer.stopStory(key: String): Boolean = StoryOwners.server(this).stop(key)

fun Entity.stopStory(key: String): Boolean = StoryOwners.entity(this).stop(key)

fun NodeScript.stopStory(key: String): Boolean = StoryOwners.node(this).stop(key)

/** What the current stage knows about its story: whom it runs for. */
val context: StoryContext
    get() = (StoryRun.current() ?: throw StoryException("context is only available while a story runs")).context

/**
 * Starts the branch [name] of the current story and returns its handle. The branch is saved with the story and
 * ends when the stage that started it returns. After a restart the same call returns the branch already running.
 */
suspend fun <T> async(name: String, block: @State suspend () -> T): StoryHandle<T> {
    val run = currentRun("async(\"$name\")")
    val lambda = block.asStage("async(\"$name\")")
    return StoryHandle(run.branch(name, lambda), lambda.result)
}

/** A run of a story or of one of its branches. */
class StoryHandle<T> internal constructor(
    private val run: StoryRun,
    private val result: KSerializer<Any?>?,
) {
    val key: String get() = run.path

    val isActive: Boolean get() = !run.state.done

    /** Where the run is: its stages and their positions. */
    val state: String get() = run.describe()

    /** Waits for the run to end and returns its result; throws if it failed or was stopped. */
    @Suppress("UNCHECKED_CAST")
    suspend fun await(): T = when (run.awaitDone()) {
        StoryRun.Status.COMPLETED -> {
            val saved = run.result
            if (result == null || saved == null) Unit as T else StoryValues.decode(result, saved) as T
        }
        StoryRun.Status.FAILED -> throw StoryException("Story '$key' failed: ${run.failure}")
        else -> throw CancellationException("Story '$key' was stopped")
    }

    /** Waits for the run to end without looking at how it ended. */
    suspend fun join() {
        run.awaitDone()
    }

    fun cancel() = run.stop()
}

/** Waits for every one of [handles]. */
suspend fun all(vararg handles: StoryHandle<*>) = handles.forEach { it.join() }

/** Waits for the first of [handles] to end, stops the others and returns the one that ended. */
suspend fun race(vararg handles: StoryHandle<*>): StoryHandle<*> = coroutineScope {
    val waits = handles.map { handle -> async { handle.join(); handle } }
    val winner = select { waits.forEach { wait -> wait.onAwait { it } } }
    waits.forEach { it.cancel() }
    handles.filter { it !== winner }.forEach(StoryHandle<*>::cancel)
    winner
}

/** Waits for [count] of [handles] to end, stops the rest and returns those that ended. */
suspend fun any(count: Int, vararg handles: StoryHandle<*>): List<StoryHandle<*>> {
    require(count in 0..handles.size) { "Cannot wait for $count of ${handles.size} branches" }
    val ended = ArrayList<StoryHandle<*>>()
    while (ended.size < count) ended += race(*handles.filter { it !in ended && it.isActive }.toTypedArray())
    handles.filter { it !in ended }.forEach(StoryHandle<*>::cancel)
    return ended
}

/**
 * A variable of the story that reads it: every run keeps its own value and saves it, branches share their
 * story's. Declare it at the top level of a script: `var trust by saved(0)`.
 */
inline fun <reified T> saved(default: T): ReadWriteProperty<Any?, T> = SavedVariable(default, storySerializer<T>())

@PublishedApi
internal class SavedVariable<T>(private val default: T, private val serializer: KSerializer<Any?>) : ReadWriteProperty<Any?, T> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val tag = run(property).vars.get(name(property)) ?: return default
        return StoryValues.decode(serializer, tag) as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val run = run(property)
        StoryValues.put(run.vars, name(property), serializer, value)
        run.changed()
    }

    private fun run(property: KProperty<*>) =
        StoryRun.current() ?: throw StoryException("'${property.name}' is a story variable and only exists while a story runs")

    /** The property qualified by the script declaring it, which a script property reference knows as its owner. */
    private fun name(property: KProperty<*>): String {
        val owner = ((property as? CallableReference)?.owner as? ClassBasedDeclarationContainer)?.jClass
        val script = owner?.getAnnotation(StageScript::class.java)?.path ?: owner?.name
        return script?.let { "$it/${property.name}" } ?: property.name
    }
}

internal suspend fun currentRun(what: String): StoryRun =
    currentCoroutineContext()[StoryRun.Element]?.run ?: throw StoryException("$what is only available while a story runs")
