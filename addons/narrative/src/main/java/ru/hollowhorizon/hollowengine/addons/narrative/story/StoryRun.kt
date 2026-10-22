package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryRun.Companion.current
import kotlin.coroutines.CoroutineContext

/**
 * A running story: the stack of stage frames plus the branches it started with [async].
 *
 * A run belongs to an owner ([StoryRuns]) under a key. It is saved whole: frames, branches, variables.
 * A run that cannot go on right now (its owner or a script it runs in is gone) sleeps with that state kept and starts again from it.
 */
class StoryRun internal constructor(
    internal val runs: StoryRuns,
    val name: String,
    internal val parent: StoryRun?,
) {
    enum class Status(val done: Boolean) { SLEEPING(false), RUNNING(false), COMPLETED(true), FAILED(true), STOPPED(true) }

    internal class Tail(val instance: String, val stage: String, val args: CompoundTag)

    internal val frames = ArrayList<StoryFrame>()
    internal val pending = ArrayDeque<StoryFrame>()
    internal val children = LinkedHashMap<String, StoryRun>()
    private val ownVars = CompoundTag()
    private val status = MutableStateFlow(Status.SLEEPING)
    private var job: Job? = null
    private val watched = HashMap<String, DisposableHandle>()
    internal var tail: Tail? = null
    internal var lastResult: Tag? = null
    internal var enterTrampolined = false
    internal var result: Tag? = null
    internal var failure: String? = null
    private var stopping = false

    /** Where the run is kept: its key under the owner, or `parent/name` for a branch. */
    val path: String get() = parent?.let { "${it.path}/$name" } ?: name

    val context: StoryContext get() = runs.owner.context(path)

    /** Variables declared with [saved], shared by the whole tree of runs. */
    internal val vars: CompoundTag get() = parent?.vars ?: ownVars

    val state: Status get() = status.value

    internal val isCancelled: Boolean get() = job?.isCancelled == true

    /** Starts or wakes the run; does nothing while it runs or once it ended. */
    internal fun start() {
        if (state.done || job?.isActive == true) return
        val previous = job
        if (previous != null && !previous.isCompleted) {
            previous.invokeOnCompletion { start() }
            return
        }
        val missing = (frames + pending).map { it.instance }.firstOrNull { StoryInstances.find(it) == null }
        if (missing != null) {
            runs.waitFor(missing, this)
            return
        }
        if (parent != null && parent.state != Status.RUNNING) return
        stopping = false
        pending.addAll(0, frames)
        frames.clear()
        status.value = Status.RUNNING
        val parentJob = parent?.job ?: runs.owner.scope.coroutineContext.job
        job = runs.owner.scope.launch(Element(this) + parentJob) { drive() }
        children.values.forEach { it.start() }
    }

    private suspend fun drive() {
        try {
            val root = pending.firstOrNull() ?: throw StoryException("Story '$path' has nothing to run")
            enterTrampolined = true
            StoryInstances.invoke(root.instance, root.stage, root.args)
            while (true) {
                val next = tail ?: break
                tail = null
                enterTrampolined = true
                StoryInstances.invoke(next.instance, next.stage, next.args)
            }
            finish(Status.COMPLETED, lastResult, null)
        } catch (missing: StoryEntityMissing) {
            HollowEngine.LOGGER.debug("Story '{}' waits for entity {}", path, missing.uuid)
            pause()
            runs.waitFor(missing.uuid, this)
        } catch (cancelled: CancellationException) {
            if (stopping) finish(Status.STOPPED, null, null) else pause()
            throw cancelled
        } catch (failure: Throwable) {
            HollowEngine.LOGGER.error("Story '{}' failed", path, failure)
            finish(Status.FAILED, null, failure.message ?: failure.javaClass.simpleName)
        } finally {
            unwatch()
        }
    }

    internal fun enter(receiver: Any, stage: String, hash: Int, sites: Array<String>): StoryFrame {
        val key = StoryInstances.keyOf(receiver)
        val trampolined = enterTrampolined
        enterTrampolined = false
        val saved = pending.firstOrNull()
        val frame = if (saved != null && saved.instance == key && saved.stage == stage) {
            pending.removeFirst().adopt(hash, sites)
        } else {
            saved?.let { discardPending("'$stage' was entered where '${it.stage}' was saved") }
            StoryFrame(this, key, stage).fresh(hash, sites)
        }
        if (frames.size >= MAX_DEPTH) {
            throw StoryException("Story '$path' nests more than $MAX_DEPTH stage calls; end a stage with a tail call")
        }
        frame.trampolined = trampolined
        frames += frame
        watch(key)
        changed()
        return frame
    }

    /** Drops saved frames nobody adopted: the code no longer reaches them. */
    internal fun discardPending(reason: String) {
        if (pending.isEmpty()) return
        HollowEngine.LOGGER.warn("Story '{}' drops its saved stages {}: {}", path, pending.map { it.stage }, reason)
        pending.clear()
    }

    /** The branch [name] that the frame on top of the stack starts, or the one it started before a restart. */
    internal fun branch(name: String, lambda: StageLambda): StoryRun {
        require('/' !in name && name.isNotBlank()) { "A branch name must be non-blank and contain no '/': '$name'" }
        val frame = frames.lastOrNull() ?: throw StoryException("async(\"$name\") must be called from a stage")
        children[name]?.let { return it }
        val child = StoryRun(runs, name, this)
        child.pending += lambda.entry(child)
        children[name] = child
        frame.children += name
        changed()
        child.start()
        return child
    }

    internal fun closeChild(name: String) {
        children.remove(name)?.stop()
    }

    /** Stops the run for good, along with its branches. */
    internal fun stop() {
        if (state.done) return
        stopping = true
        val running = job
        if (running?.isActive == true) running.cancel(CancellationException("Story '$path' was stopped"))
        else finish(Status.STOPPED, null, null)
    }

    /** Puts the run to sleep, keeping where it is; [start] wakes it. */
    internal fun sleep(reason: String) {
        if (state != Status.RUNNING) return
        HollowEngine.LOGGER.debug("Story '{}' sleeps: {}", path, reason)
        job?.cancel(CancellationException("Story '$path' sleeps: $reason"))
    }

    internal suspend fun awaitDone(): Status = status.first { it.done }

    internal fun changed() = runs.changed()

    private fun pause() {
        status.value = Status.SLEEPING
        pending.addAll(0, frames)
        frames.clear()
        tail = null
        changed()
    }

    private fun finish(status: Status, result: Tag?, failure: String?) {
        this.result = result
        this.failure = failure
        frames.clear()
        pending.clear()
        this.status.value = status
        if (parent == null) runs.ended(this) else changed()
    }

    private fun watch(key: String) {
        if (key in watched) return
        val instanceJob = StoryInstances.jobOf(key) ?: return
        watched[key] = instanceJob.invokeOnCompletion {
            runs.waitFor(key, root)
            root.sleep("script '$key' stopped")
        }
    }

    private fun unwatch() {
        watched.values.forEach(DisposableHandle::dispose)
        watched.clear()
    }

    private val root: StoryRun get() = parent?.root ?: this

    internal fun save(): CompoundTag = CompoundTag().apply {
        putString("status", (if (state == Status.RUNNING) Status.SLEEPING else state).name)
        put("frames", ListTag().apply { (frames + pending).forEach { add(it.save()) } })
        if (children.isNotEmpty()) put(
            "children",
            CompoundTag().apply { children.forEach { (name, run) -> put(name, run.save()) } })
        if (parent == null && !ownVars.isEmpty) put("vars", ownVars.copy())
        result?.let { put("result", it) }
        failure?.let { putString("failure", it) }
    }

    /** What the run is doing, for logs and the inspector. */
    fun describe(): String = (frames + pending).joinToString(" > ") { frame ->
        frame.position?.let { "${frame.stage}@$it" } ?: frame.stage
    }.ifEmpty { state.name.lowercase() }

    /** Makes the run reachable from the stages it runs, and from [current] in code that is not suspending. */
    internal class Element(val run: StoryRun) : ThreadContextElement<StoryRun?> {
        companion object Key : CoroutineContext.Key<Element>

        override val key: CoroutineContext.Key<*> get() = Key

        override fun updateThreadContext(context: CoroutineContext): StoryRun? = CURRENT.get().also { CURRENT.set(run) }

        override fun restoreThreadContext(context: CoroutineContext, oldState: StoryRun?) = CURRENT.set(oldState)
    }

    internal companion object {
        const val MAX_DEPTH = 32
        private val CURRENT = ThreadLocal<StoryRun?>()

        /** The run whose code is executing on this thread. */
        fun current(): StoryRun? = CURRENT.get()

        fun load(runs: StoryRuns, name: String, parent: StoryRun?, tag: CompoundTag): StoryRun =
            StoryRun(runs, name, parent).apply {
                status.value = runCatching { Status.valueOf(tag.getString("status")) }.getOrDefault(Status.SLEEPING)
                tag.getList("frames", Tag.TAG_COMPOUND.toInt())
                    .forEach { pending += StoryFrame.load(this, it as CompoundTag) }
                val saved = tag.getCompound("children")
                saved.allKeys.forEach { children[it] = load(runs, it, this, saved.getCompound(it)) }
                ownVars.merge(tag.getCompound("vars"))
                result = tag.get("result")
                failure = tag.getString("failure").takeIf { tag.contains("failure") }
            }
    }
}
