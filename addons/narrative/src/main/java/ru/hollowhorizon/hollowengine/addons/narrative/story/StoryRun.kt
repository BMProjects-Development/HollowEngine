package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
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
    private val ownBoxes: StoryBoxTable? = if (parent == null) StoryBoxTable(this) else null
    private val ownRecords = CompoundTag()
    private val ownJournal = StoryJournal()
    private val ownHistory: StoryHistory? = if (parent == null) StoryHistory(this) else null
    private var moving = false
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

    /** The vars stages share with their `@State` lambdas, saved with the tree like locals. */
    internal val boxes: StoryBoxTable get() = parent?.boxes ?: ownBoxes!!

    /** What [once] and [remember] keep: shared by the tree, and left as they are by a rewind. */
    internal val records: CompoundTag get() = parent?.records ?: ownRecords

    /** The actions the whole tree of runs performed. */
    internal val journal: StoryJournal get() = parent?.journal ?: ownJournal

    internal val history: StoryHistory get() = parent?.history ?: ownHistory!!

    val state: Status get() = status.value

    internal val isCancelled: Boolean get() = job?.isCancelled == true

    internal val isStopped: Boolean get() = stopping || state == Status.STOPPED

    /** Starts or wakes the run; does nothing while it runs or once it ended. */
    internal fun start() {
        if (state.done || job?.isActive == true || !isAttached || lineage.any { it.moving }) return
        val previous = job
        if (previous != null && !previous.isCompleted) {
            previous.invokeOnCompletion { start() }
            return
        }
        val missing = (frames + pending).map { it.instance }.firstOrNull { StoryInstances.find(it) == null }
        if (missing != null) {
            if (StoryInstances.isGone(missing)) discard("its script '$missing' no longer exists") else runs.waitFor(missing, this)
            return
        }
        if (parent != null && parent.state != Status.RUNNING) return
        stopping = false
        pending.addAll(0, frames)
        frames.clear()
        status.value = Status.RUNNING
        val parentJob = parent?.job ?: runs.owner.scope.coroutineContext.job
        val running = runs.owner.scope.launch(Element(this) + parentJob, start = CoroutineStart.LAZY) { drive() }
        job = running
        running.invokeOnCompletion {
            if (job === running && state == Status.RUNNING) {
                if (stopping) finish(Status.STOPPED, null, null) else pause()
            }
        }
        running.start()
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
    internal suspend fun branch(name: String, lambda: StageLambda): StoryRun {
        require('/' !in name && name.isNotBlank()) { "A branch name must be non-blank and contain no '/': '$name'" }
        val frame = frames.lastOrNull() ?: throw StoryException("async(\"$name\") must be called from a stage")
        children[name]?.let { previous ->
            if (!previous.isStopped) {
                if (name !in frame.children) throw StoryException(
                    "Branch '$path/$name' belongs to another active stage; use a distinct branch name"
                )
                return previous
            }
            previous.job?.join()
            frames.forEach { it.children.remove(name) }
        }
        val child = StoryRun(runs, name, this)
        child.pending += lambda.entry(child)
        children[name] = child
        frame.children += name
        history.add(name, child, emptyList())
        changed()
        child.start()
        return child
    }

    internal fun closeChild(name: String) {
        children.remove(name)?.stop()
    }

    /** Removes the whole story for good: a script it runs in will not come back, so it would wait forever. */
    internal fun discard(reason: String) {
        val root = root
        HollowEngine.LOGGER.error("Story '{}' is removed: {}", root.path, reason)
        if (!root.runs.stop(root.name)) root.stop()
    }

    /** Stops the run for good, along with its branches, and takes back their temporary actions at once. */
    internal fun stop() {
        if (state.done) {
            if (!stopping) {
                stopping = true
                changed()
            }
            return
        }
        stopping = true
        journal.close(path)
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

    /** This run and every branch under it. */
    internal fun tree(): Sequence<StoryRun> = sequenceOf(this) + children.values.asSequence().flatMap { it.tree() }

    /**
     * Takes the story back: with [name], the whole tree to the latest checkpoint so named; without it, this run and
     * its branches to the latest checkpoint this run passed itself. The runs stop, the journal undoes what they
     * performed since, and they start again from there.
     */
    internal fun rewind(name: String?) {
        if (name != null && !history.has(name)) throw StoryException("Story '${root.path}' has no checkpoint '$name'")
        if (name == null && !history.hasOwn(this)) throw StoryException("Story '$path' has no checkpoint left to rewind to")
        if (name == null) move(this) { history.rewindOwn(this) } else move(root) { history.rewind(name) }
    }

    /** Stops the run with its branches and waits until they have; [start] brings it back. */
    internal suspend fun halt(reason: String) {
        val running = job ?: return
        sleep(reason)
        running.join()
    }

    /** Puts the whole tree back to what [saveTree] wrote, ready to [start]. */
    internal fun restoreTree(tag: CompoundTag) {
        frames.clear()
        pending.clear()
        children.clear()
        tail = null
        readTree(tag)
        changed()
    }

    /** Runs [change] on the stopped story outside of it, then starts [restart] again. */
    private fun move(restart: StoryRun, change: suspend () -> Unit) {
        val root = root
        if (root.tree().any { it.moving }) throw StoryException("Story '${root.path}' is already being rewound")
        restart.moving = true
        runs.owner.scope.launch {
            try {
                change()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                HollowEngine.LOGGER.error("Story '{}' failed to rewind", root.path, failure)
            } finally {
                restart.moving = false
                root.changed()
                restart.start()
            }
        }
    }

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
        journal.close(path)
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

    internal val root: StoryRun get() = parent?.root ?: this

    /** This run and the runs it is a branch of. */
    private val lineage: Sequence<StoryRun> get() = generateSequence(this) { it.parent }

    /**
     * Whether the run is still part of its story. A rewind replaces the branches it restores, and a run that ended
     * leaves its owner, so a wait that wakes such a run afterwards does nothing.
     */
    private val isAttached: Boolean get() = if (parent == null) runs.holds(this) else parent.children[name] === this && parent.isAttached

    /** Everything to save; the root adds what the tree shares over time: records, journal, checkpoints. */
    internal fun save(): CompoundTag = saveTree().apply {
        if (parent != null) return@apply
        journal.prune(history.floor(tree().flatMap { (it.frames + it.pending).asSequence() }.map { it.segmentMark }))
        if (!ownRecords.isEmpty) put("records", ownRecords.copy())
        put("journal", journal.save())
        put("history", history.save())
    }

    /** The runs as they are: stacks, branches, variables. A checkpoint keeps this. */
    internal fun saveTree(): CompoundTag = CompoundTag().apply {
        putString("status", (if (state == Status.RUNNING) Status.SLEEPING else state).name)
        if (stopping) putBoolean("stopping", true)
        put("frames", ListTag().apply { (frames + pending).forEach { add(it.save()) } })
        if (children.isNotEmpty()) {
            put("children", CompoundTag().apply { children.forEach { (name, run) -> put(name, run.saveTree()) } })
            put("order", ListTag().apply { children.keys.forEach { add(StringTag.valueOf(it)) } })
        }
        if (parent == null && !ownVars.isEmpty) put("vars", ownVars.copy())
        if (parent == null) put("boxes", ownBoxes!!.save())
        result?.let { put("result", it.copy()) }
        failure?.let { putString("failure", it) }
    }

    private fun readTree(tag: CompoundTag) {
        status.value = runCatching { Status.valueOf(tag.getString("status")) }.getOrDefault(Status.SLEEPING)
        stopping = tag.getBoolean("stopping")
        tag.getList("frames", Tag.TAG_COMPOUND.toInt()).forEach { pending += StoryFrame.load(this, it as CompoundTag) }
        val saved = tag.getCompound("children")
        val order = tag.getList("order", Tag.TAG_STRING.toInt()).map { it.asString }.filter(saved::contains)
        (order + saved.allKeys.filter { it !in order }.sorted()).forEach { children[it] = load(runs, it, this, saved.getCompound(it)) }
        if (parent == null) {
            ownVars.allKeys.toList().forEach(ownVars::remove)
            ownVars.merge(tag.getCompound("vars").copy())
            ownBoxes!!.load(tag.getCompound("boxes"))
        }
        result = tag.get("result")?.copy()
        failure = tag.getString("failure").takeIf { tag.contains("failure") }
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

        /** A run a saved handle names that has ended and left its story: all a handle can still do is report so. */
        fun ended(runs: StoryRuns, name: String): StoryRun = StoryRun(runs, name, null).apply { status.value = Status.COMPLETED }

        fun load(runs: StoryRuns, name: String, parent: StoryRun?, tag: CompoundTag): StoryRun =
            StoryRun(runs, name, parent).apply {
                readTree(tag)
                if (parent == null) {
                    ownRecords.merge(tag.getCompound("records"))
                    ownJournal.load(tag.getCompound("journal"))
                    ownHistory!!.load(tag.getCompound("history"))
                }
            }
    }
}
