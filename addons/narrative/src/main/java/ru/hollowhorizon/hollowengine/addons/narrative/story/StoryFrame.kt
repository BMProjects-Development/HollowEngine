package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.KSerializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.HollowEngine

/**
 * One call of a stage on a story's stack.
 *
 * The compiler rewrites every `@State` function to drive its frame: the stage enters it, saves the locals it
 * still needs at each stage call and [checkpoint], and on a restart is led back to where it was ([resumesInto]).
 * A frame is saved at those boundaries only, so a stage that restarts repeats the segment it was in.
 *
 * The positions the generated code passes around are `site * 2` for "inside the call at site" and
 * `site * 2 + 1` for "after it"; the save names sites instead, so it survives the code being recompiled.
 */
class StoryFrame internal constructor(
    internal val run: StoryRun,
    val instance: String,
    val stage: String,
) {
    internal var hash = 0
    internal var position: String? = null
    internal var args = CompoundTag()
    internal var locals = CompoundTag()
    internal var result: Tag? = null
    internal var segment = CompoundTag()
        set(value) {
            field = value
            boxOrdinals.clear()
        }

    /** The boxes of shared vars this frame declared; they go when it returns. */
    internal val boxes = LinkedHashSet<String>()

    /** How many times each shared var was declared in this segment since the frame last started running it. */
    private val boxOrdinals = HashMap<String, Int>()
    internal val children = LinkedHashSet<String>()
    internal var trampolined = false

    /** The journal mark when the frame was entered: its temporary actions are those after it. */
    internal var entryMark = 0L

    /** The journal mark when the current segment started, which a repeat of the segment and `rewind()` go back to. */
    internal var segmentMark = 0L
    private var sites: Array<String> = emptyArray()
    private var resuming = false
    private var target = -1
    private var replaced = false
    private var failure: Throwable? = null

    /** Where the frame was before the call in progress: a call that fails returns the frame there. */
    private var previous: CompoundTag? = null

    /** Whether the stage is still finding its way back to the position it was saved at. */
    fun isResuming(): Boolean = resuming

    /** Whether code holding the positions [from]..[to] runs: always, unless the stage is resuming elsewhere. */
    fun enters(from: Int, to: Int): Boolean = !resuming || target in from..to

    /** Whether the stage is resuming to one of the positions [from]..[to]. */
    fun resumesInto(from: Int, to: Int): Boolean = resuming && target in from..to

    /** Whether the stage is resuming exactly to [position]. */
    fun isResumingAt(position: Int): Boolean = resuming && target == position

    /**
     * Remembers an argument, which the stage is started with again after a restart. A saved frame that code
     * enters with other arguments is not the one it was saved as, so the stage starts over.
     */
    fun saveArg(name: String, serializer: KSerializer<Any?>, value: Any?) {
        val encoded = StoryValues.encode(serializer, value)
        val saved = args.get(name)
        if (resuming && saved != null && saved != encoded) startOver("'$stage' was entered with another '$name'")
        args.put(name, encoded)
    }

    /**
     * The key [once] and [remember] keep [name] under: the stage with its arguments, the segment it is in, the name.
     * Long arguments are shortened to their hash.
     */
    internal fun recordKey(name: String): String {
        val arguments = args.toString().removeSurrounding("{", "}")
        val shown = if (arguments.length <= MAX_ARGUMENTS_IN_KEY) arguments else Integer.toHexString(arguments.hashCode())
        return "$instance/$stage($shown)/${position ?: "start"}/$name"
    }

    /** Reached only if a stage that returns a value ran off its end, which the compiler rules out. */
    fun lost(): Nothing = throw StoryException("Stage '$stage' ended without returning a value")

    /** Remembers a local that outlives the next boundary. */
    fun save(name: String, serializer: KSerializer<Any?>, value: Any?) {
        StoryValues.put(locals, name, serializer, value)
    }

    /**
     * The saved value of a local, or [current] when the boundary resumed to did not need it. A local that no
     * longer reads as its type, after its declaration changed, makes the stage start over.
     */
    fun restore(name: String, serializer: KSerializer<Any?>, current: Any?): Any? {
        val tag = locals.get(name) ?: return current
        return try {
            StoryValues.decode(serializer, tag)
        } catch (missing: StoryEntityMissing) {
            throw missing
        } catch (failure: Exception) {
            startOver("its local '$name' no longer reads as ${serializer.descriptor.serialName}: ${failure.message}")
            current
        }
    }

    /** The stage is about to call the stage at [site]; the locals the call needs are saved right after. */
    fun beginCall(site: Int) {
        run.discardPending("'$stage' moved on")
        previous = CompoundTag().apply {
            position?.let { putString("position", it) }
            put("locals", locals.copy())
            result?.let { put("result", it) }
        }
        position = CALL + sites[site]
        result = null
        run.lastResult = null
        run.enterTrampolined = true
        run.changed()
    }

    /** The call at [site] returned [value]; runs any tail call it left and returns the final value. */
    suspend fun completeCall(site: Int, value: Any?): Any? {
        var current = value
        while (true) {
            val tail = run.tail ?: break
            run.tail = null
            run.enterTrampolined = true
            current = StoryInstances.invoke(tail.instance, tail.stage, tail.args)
        }
        position = AFTER + sites[site]
        previous = null
        result = run.lastResult
        run.lastResult = null
        segment = CompoundTag()
        segmentMark = run.journal.next
        run.changed()
        return current
    }

    /** Resumes the call at [site] that was in progress when the story was saved. */
    suspend fun resumeCall(site: Int): Any? {
        resuming = false
        val next = run.pending.firstOrNull()
            ?: throw StoryException("The saved story lost the stage '$stage' was waiting for at '${sites[site]}'")
        run.enterTrampolined = true
        return completeCall(site, StoryInstances.invoke(next.instance, next.stage, next.args))
    }

    /** Resumes after the call at [site]; returns its saved result, or null when [serializer] is null. */
    fun resumeAfter(site: Int, serializer: KSerializer<Any?>?): Any? {
        resuming = false
        if (serializer == null) return null
        val saved = result ?: throw StoryException("The saved story lost the result of '${sites[site]}' in '$stage'")
        return try {
            StoryValues.decode(serializer, saved)
        } catch (missing: StoryEntityMissing) {
            throw missing
        } catch (failure: Exception) {
            throw StoryException("The result of '${sites[site]}' saved in '$stage' no longer reads as ${serializer.descriptor.serialName}", failure)
        }
    }

    /** Ends a segment at the checkpoint at [site], which the story can be rewound to. */
    fun checkpoint(site: Int) = pass(site, emptyList())

    /** Ends a segment at the checkpoint at [site] after [labels] captured what a rewind to it puts back. */
    suspend fun checkpoint(site: Int, labels: suspend CheckpointScope.() -> Unit) {
        val scope = CheckpointScope(run.path)
        scope.labels()
        pass(site, scope.labels)
    }

    private fun pass(site: Int, labels: List<JournalEntry>) {
        run.discardPending("'$stage' moved on")
        position = AFTER + sites[site]
        previous = null
        result = null
        segment = CompoundTag()
        segmentMark = run.journal.next
        run.history.add(sites[site], run, labels)
        run.changed()
    }

    /** Whether the caller runs a tail call this stage leaves, so the stage may give its place away. */
    fun canTailCall(): Boolean = trampolined

    /** Gives this frame's place to [stage] of [receiver], which the caller starts with [args] once this stage returns. */
    fun tailCall(receiver: Any, stage: String, args: CompoundTag) {
        close()
        replaced = true
        run.tail = StoryRun.Tail(StoryInstances.keyOf(receiver), stage, args)
        run.changed()
    }

    /** The stage returns [value]; the caller reads it back with [serializer] after a restart. */
    fun returning(value: Any?, serializer: KSerializer<Any?>): Any? {
        run.lastResult = StoryValues.encode(serializer, value)
        return value
    }

    /** The stage is leaving with [failure]. */
    fun abort(failure: Throwable) {
        this.failure = failure
    }

    /** The stage is leaving. Its frame stays when the whole story stopped, so it can be saved as it was. */
    fun leave() {
        if (replaced) return
        val reason = failure
        failure = null
        if (run.isCancelled || reason is StoryEntityMissing) return
        close()
        if (reason != null) run.frames.lastOrNull()?.callFailed()
        run.changed()
    }

    /**
     * The stage this frame was calling failed, and the stage goes on, catching it or not. Until its next boundary
     * it is back in the segment it was in before the call, which a restart then repeats.
     */
    private fun callFailed() {
        val before = previous ?: return
        if (position?.startsWith(CALL) != true) return
        position = before.getString("position").takeIf { before.contains("position") }
        locals = before.getCompound("locals")
        result = before.get("result")
        previous = null
    }

    private fun startOver(reason: String) {
        HollowEngine.LOGGER.warn("Stage '{}' of '{}' starts over: {}", stage, instance, reason)
        resuming = false
        target = -1
        position = null
        previous = null
        locals = CompoundTag()
        result = null
        segment = CompoundTag()
        run.journal.undoTemporary(run.path, entryMark)
        segmentMark = entryMark
        run.discardPending(reason)
    }

    internal fun adopt(hash: Int, sites: Array<String>): StoryFrame {
        this.sites = sites
        val saved = position
        target = saved?.let(::positionOf) ?: -1
        if (saved != null && target < 0) {
            startOver("it no longer has '$saved'")
        } else if (saved != null && hash != this.hash) {
            HollowEngine.LOGGER.debug(
                "Stage '{}' of '{}' changed since it was saved, resuming at '{}'",
                stage,
                instance,
                saved
            )
        }
        this.hash = hash
        resuming = target >= 0
        boxOrdinals.clear()
        if (target < 0 || target % 2 == 1) run.journal.undoTemporary(run.path, if (target < 0) entryMark else segmentMark)
        return this
    }

    internal fun fresh(hash: Int, sites: Array<String>): StoryFrame {
        this.hash = hash
        this.sites = sites
        entryMark = run.journal.next
        segmentMark = entryMark
        return this
    }

    /** Takes the frame off the stack together with the branches it started. */
    private fun close() {
        check(run.frames.lastOrNull() === this) {
            "Stages of story '${run.path}' ran side by side; start parallel branches with async(name) { }"
        }
        run.frames.removeAt(run.frames.lastIndex)
        children.forEach { run.closeChild(it) }
        children.clear()
        run.journal.undoTemporary(run.path, entryMark)
        run.root.boxes.release(boxes)
    }

    /** The next ordinal of a declaration of the shared var [name], which tells loop iterations apart. */
    internal fun nextBoxOrdinal(name: String): Int = boxOrdinals.merge(name, 1, Int::plus)!! - 1

    private fun positionOf(saved: String): Int {
        val call = saved.startsWith(CALL)
        val site = sites.indexOf(saved.removePrefix(if (call) CALL else AFTER))
        return if (site < 0) -1 else site * 2 + if (call) 0 else 1
    }

    internal fun save(): CompoundTag = CompoundTag().apply {
        putString("instance", instance)
        putString("stage", stage)
        putInt("hash", hash)
        position?.let { putString("position", it) }
        put("args", args)
        put("locals", locals)
        result?.let { put("result", it) }
        previous?.let { put("previous", it) }
        put("segment", segment)
        putLong("entryMark", entryMark)
        putLong("segmentMark", segmentMark)
        if (boxes.isNotEmpty()) put("boxes", ListTag().apply { boxes.forEach { add(StringTag.valueOf(it)) } })
        put("children", ListTag().apply { children.forEach { add(StringTag.valueOf(it)) } })
    }.copy()

    internal companion object {
        private const val CALL = "call:"
        private const val AFTER = "after:"
        private const val MAX_ARGUMENTS_IN_KEY = 48

        fun load(run: StoryRun, tag: CompoundTag) =
            StoryFrame(run, tag.getString("instance"), tag.getString("stage")).apply {
                hash = tag.getInt("hash")
                position = tag.getString("position").takeIf { tag.contains("position") }
                args = tag.getCompound("args").copy()
                locals = tag.getCompound("locals").copy()
                result = tag.get("result")?.copy()
                previous = tag.getCompound("previous").copy().takeIf { tag.contains("previous") }
                segment = tag.getCompound("segment").copy()
                entryMark = tag.getLong("entryMark")
                segmentMark = tag.getLong("segmentMark")
                tag.getList("boxes", Tag.TAG_STRING.toInt()).forEach { boxes += it.asString }
                tag.getList("children", Tag.TAG_STRING.toInt()).forEach { children += it.asString }
            }
    }
}

/** Where the code the compiler generates for stages reaches the running story. */
object StoryFrames {
    /** Enters [stage] of [receiver], resuming the saved frame when the story is being restored. */
    suspend fun enter(receiver: Any, stage: String, hash: Int, sites: Array<String>): StoryFrame {
        val run = currentCoroutineContext()[StoryRun.Element]?.run
            ?: throw StoryException("Stage '$stage' was called outside a story; start one with launchStory")
        return run.enter(receiver, stage, hash, sites)
    }
}
