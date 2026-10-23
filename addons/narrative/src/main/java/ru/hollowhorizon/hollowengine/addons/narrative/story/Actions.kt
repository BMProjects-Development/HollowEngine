package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A reversible action. Its body is `action { }`: within a story every call is written to the journal, which can undo
 * it on a rewind, after a restart too. The journal keeps the receivers and parameters
 * of the call, so they must be serializable; entities are kept as references.
 *
 * @param id the id the journal and editors know the action by, in the namespace of its script or addon; the
 * function name when empty. Overloads need ids of their own.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Action(val id: String = "")

/**
 * A parameter of an [Action] the journal does not keep, such as options holding a lambda. Undoing or fast-forwarding
 * the call gets its default value instead, so it must have one, and the blocks must not depend on it.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unsaved

/**
 * A shortcut that performs exactly one [Action], such as an overload taking other arguments. A stage calls it as it
 * would call the action itself: the call is a boundary that a restart does not repeat once it is done.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ActionAlias

/** An action that is not undone: an ordinary function editors and `.story` can call by [id]. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SimpleAction(val id: String = "")

@DslMarker
annotation class StoryDsl

/** How long an action stands in the world, see [temporary]. */
enum class Lifetime {
    /** Taken back when the story passes its checkpoint or ends, and before the segment that performed it repeats. */
    TEMPORARY,

    /** Undone only by a rewind; left in the world when the story ends. */
    RUN,
}

/**
 * The body of an [Action]. [block] declares how to [capture][CaptureScope.capture] the state the action changes,
 * how to [undo][CaptureScope.undo] and [fast-forward][ActionScope.fastForward] it, and ends with
 * [perform][ActionScope.perform], which does it.
 *
 * The journal runs [block] again to undo or fast-forward the call, with the saved snapshots in place of every
 * capture, so before `perform` it must only declare blocks and compute values.
 *
 * @param call the receivers and parameters of the action, in order; in scripts the compiler passes them.
 */
suspend fun <R> action(vararg call: Any?, block: suspend ActionScope<R>.() -> Performed<R>): R {
    val action = StoryActions.enclosing(block, "action { }")
    ActionReplay.take(action)?.let { replay ->
        replay.call = call
        return ActionScope<R>(null, replay).also { replay.scope = it }.block().value
    }
    val context = currentCoroutineContext()
    val run = context[StoryRun.Element]?.run?.takeIf { context[Replaying] == null }
    val args = action.encode(call)
    // A stage calling the action directly keeps its record, to finish the action instead of repeating it after a restart.
    val site = run?.frames?.lastOrNull()?.takeIf { context[ActiveAction] == null && it.claimsAction() }
    val record = run?.let {
        val temporary = context[Temporary]
        ActionRecord(it, action, args, if (temporary != null) Lifetime.TEMPORARY else Lifetime.RUN, temporary?.until, context[ActiveAction]?.seq, site)
    }
    return try {
        ActionScope<R>(record, null).block().value
    } catch (failure: Throwable) {
        val unloading = failure is CancellationException && !currentCoroutineContext().isActive || failure is StoryEntityMissing
        val missing = if (failure is CancellationException && !unloading) run?.let { unloaded(call) } else null
        if (!unloading && missing == null) site?.actionFailed()
        throw missing ?: failure
    }
}

/**
 * An entity of the call that left the world without being destroyed, like the body of an NPC whose chunk unloaded
 * in the middle of the action, which cancels what the body was doing. The story then waits for the entity to come back.
 */
internal fun unloaded(call: Array<out Any?>): StoryEntityMissing? = call.firstNotNullOfOrNull { value ->
    (value as? Entity)?.takeIf { it.removalReason?.shouldDestroy() == false }
}?.let { StoryEntityMissing(it.uuid) }

/**
 * The body of a checkpoint label such as [position]: like [action] without `perform`. A label captures a state
 * when its checkpoint is passed and puts it back on every rewind to that checkpoint.
 */
suspend fun label(vararg call: Any?, block: suspend LabelScope.() -> Unit) {
    val action = StoryActions.enclosing(block, "label { }")
    ActionReplay.take(action)?.let { replay -> LabelScope(replay).also { replay.scope = it }.block(); return }
    val checkpoint = call.firstNotNullOfOrNull { it as? CheckpointScope }
        ?: throw StoryException("A label is only placed inside checkpoint(name) { }")
    val scope = LabelScope(null).also { it.block() }
    checkpoint.add(action, action.encode(call), scope.snapshots)
}

/**
 * Runs [block] with every action it performs kept until a checkpoint, like a barricade that only stands during a
 * fight: they are taken back when the story passes the checkpoint [until], or the next one of this run when it is
 * null, and when the story ends. A segment that repeats after a restart takes them back before it performs them again.
 */
suspend fun <T> temporary(until: String? = null, block: suspend () -> T): T = withContext(Temporary(until)) { block() }

/** What a capture block can declare: snapshots taken before the action and how to put them back. */
@StoryDsl
open class CaptureScope internal constructor(internal val replay: ActionReplay?) {
    internal val snapshots = CompoundTag()
    internal var undo: (UndoScope.() -> Unit)? = null
    private var captured = 0
    internal var closed = false

    /**
     * Takes a snapshot with [block] before the action changes anything. When the journal undoes the action, it
     * returns the saved snapshot instead, so [undo] can use it after a restart too.
     */
    inline fun <reified S> capture(noinline block: () -> S): S = capture(storySerializer<S>(), block)

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <S> capture(serializer: KSerializer<Any?>, block: () -> S): S {
        check(!closed) { "capture { } must come before perform { }" }
        val index = captured++
        replay?.let { return it.snapshot(index, serializer) as S }
        val value = block()
        snapshots.put(index.toString(), StoryValues.encode(serializer, value))
        return value
    }

    /**
     * Puts back what the action changed, as the absolute state of its snapshots rather than a reverse delta, so it
     * works wherever the action stopped. Call [UndoScope.conflict] when someone else changed it since.
     */
    fun undo(block: UndoScope.() -> Unit) {
        check(!closed) { "undo { } must come before perform { }" }
        undo = block
    }
}

/** The blocks of an [action]. */
class ActionScope<R> internal constructor(
    private val record: ActionRecord?,
    replay: ActionReplay?,
) : CaptureScope(replay) {
    internal var fastForward: (suspend () -> R)? = null
    internal var perform: (suspend () -> R)? = null

    /** Brings the world to where the action ends, at once; without it the action is performed again. */
    fun fastForward(block: suspend () -> R) {
        check(!closed) { "fastForward { } must come before perform { }" }
        fastForward = block
    }

    /** Does the action and ends the body; the journal records the call before [block] starts. */
    suspend fun perform(block: suspend () -> R): Performed<R> {
        check(!closed) { "An action performs once" }
        closed = true
        perform = block
        @Suppress("UNCHECKED_CAST")
        if (replay != null) return Performed(null as R)
        val entry = record?.begin(snapshots) ?: return Performed(block())
        val value = withContext(ActiveAction(entry.seq)) { block() }
        record.done(entry)
        return Performed(value)
    }
}

/** The blocks of a [label]. */
class LabelScope internal constructor(replay: ActionReplay?) : CaptureScope(replay)

/** What an [undo][CaptureScope.undo] block can report. */
@StoryDsl
class UndoScope internal constructor() {
    /** Gives up undoing because someone else changed the state since; the journal logs [reason] as a warning. */
    fun conflict(reason: String): Nothing = throw ActionConflict(reason)
}

/** The value an action ends with; only [ActionScope.perform] makes one, so the body has to end with it. */
class Performed<R> internal constructor(internal val value: R)

internal class ActionConflict(reason: String) : RuntimeException(reason)

/**
 * Writes a call of [action] made in [run] to the journal around its perform; [site] is the frame of the stage that
 * calls it directly, which keeps the record to finish the action after a restart.
 */
internal class ActionRecord(
    private val run: StoryRun,
    private val action: StoryAction,
    private val args: CompoundTag,
    private val lifetime: Lifetime,
    private val until: String?,
    private val outer: Long?,
    private val site: StoryFrame?,
) {
    fun begin(snapshots: CompoundTag): JournalEntry =
        run.journal.record(action.id, run.path, lifetime, until, args, snapshots, outer).also { site?.action = it.seq }

    fun done(entry: JournalEntry) {
        entry.done = true
    }
}

/** Marks actions performed inside [temporary]. */
internal class Temporary(val until: String?) : AbstractCoroutineContextElement(Temporary) {
    companion object Key : CoroutineContext.Key<Temporary>
}

/** The action whose perform is running: actions inside it are its parts, which a fast-forward of it repeats. */
internal class ActiveAction(val seq: Long) : AbstractCoroutineContextElement(ActiveAction) {
    companion object Key : CoroutineContext.Key<ActiveAction>
}

/** Marks a fast-forward of the journal: the actions it performs again are not recorded twice. */
internal class Replaying : AbstractCoroutineContextElement(Replaying) {
    companion object Key : CoroutineContext.Key<Replaying>
}
