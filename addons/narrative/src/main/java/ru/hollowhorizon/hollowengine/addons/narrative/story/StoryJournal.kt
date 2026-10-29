package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * One call of an action a story made: what to call again to undo or fast-forward it.
 *
 * @param seq the place of the call in the journal, which only grows; checkpoints and segments mark it.
 * @param until the checkpoint that takes a temporary action back; null for the next one its run passes.
 * @param outer the call this one was made from, whose fast-forward repeats it too.
 */
internal class JournalEntry(
    val seq: Long,
    val action: String,
    val run: String,
    val lifetime: Lifetime,
    val until: String?,
    val args: CompoundTag,
    val snapshots: CompoundTag,
    val outer: Long?,
) {
    var done = false

    /** When a temporary effect was taken back, by its checkpoint or its run ending. Null while it stands. */
    var undoneAt: Long? = null

    val active: Boolean get() = undoneAt == null

    fun save(): CompoundTag = CompoundTag().apply {
        putLong("seq", seq)
        putString("action", action)
        putString("run", run)
        putString("lifetime", lifetime.name)
        until?.let { putString("until", it) }
        put("args", args.copy())
        put("snapshots", snapshots.copy())
        outer?.let { putLong("outer", it) }
        putBoolean("done", done)
        undoneAt?.let { putLong("undone", it) }
    }

    companion object {
        fun load(tag: CompoundTag) = JournalEntry(
            tag.getLong("seq"),
            tag.getString("action"),
            tag.getString("run"),
            runCatching { Lifetime.valueOf(tag.getString("lifetime")) }.getOrDefault(Lifetime.RUN),
            tag.getString("until").takeIf { tag.contains("until") },
            tag.getCompound("args").copy(),
            tag.getCompound("snapshots").copy(),
            tag.getLong("outer").takeIf { tag.contains("outer") },
        ).apply {
            done = tag.getBoolean("done")
            undoneAt = tag.getLong("undone").takeIf { tag.contains("undone") }
        }
    }
}

/**
 * The actions of a story and its branches, in the order they were performed. An entry is written before its action
 * runs, so a save made in the middle of it still knows how to undo it.
 *
 * Entries are kept as long as a rewind may need them: a checkpoint or segment a stage is in marks the journal,
 * and going back to a mark undoes what was done after it and brings back what was taken back after it.
 */
internal class StoryJournal {
    private val entries = ArrayList<JournalEntry>()

    /** The place the next entry gets: a mark taken now precedes everything performed from here on. */
    var next = 0L
        private set

    val isEmpty: Boolean get() = entries.isEmpty()

    fun record(
        action: String,
        run: String,
        lifetime: Lifetime,
        until: String?,
        args: CompoundTag,
        snapshots: CompoundTag,
        outer: Long?,
    ) = JournalEntry(next++, action, run, lifetime, until, args, snapshots, outer).also { entries += it }

    /**
     * Takes back the temporary actions [run] performed since [from], in reverse: the segment that performed them
     * repeats after a restart, or the run ends. With [branches] the branches of [run] count too.
     */
    fun undoTemporary(run: String, from: Long, branches: Boolean = false) = takeBack {
        it.seq >= from && (it.run == run || branches && it.run.startsWith("$run/"))
    }

    /**
     * [run] passed the checkpoint [name]: takes back the temporary actions that last until it, those [run] performed
     * without naming a checkpoint and those of any run that named this one.
     */
    fun checkpointPassed(run: String, name: String) = takeBack { it.until == name || it.until == null && it.run == run }

    private fun takeBack(which: (JournalEntry) -> Boolean) {
        entries.filter { it.active && it.lifetime == Lifetime.TEMPORARY && which(it) }.asReversed().forEach { entry ->
            StoryActions.undo(entry)
            entry.undoneAt = next++
        }
    }

    /**
     * Brings the world covered by [scope] back to how it was at [mark] and forgets what was performed after it:
     * undoes the actions performed since, and fast-forwards the temporary ones taken back since, latest first.
     */
    suspend fun rewind(mark: Long, scope: (JournalEntry) -> Boolean = { true }) {
        val covered = entries.filter(scope)
        val undo = covered.filter { it.active && it.seq >= mark }.map { it.seq to it }
        val restore =
            covered.filter { !it.active && it.seq < mark && it.undoneAt!! >= mark }.map { it.undoneAt!! to it }
        val restored = restore.map { it.second.seq }.toSet()
        (undo + restore).sortedByDescending { it.first }.forEach { (_, entry) ->
            if (entry.active) StoryActions.undo(entry) else {
                if (entry.outer !in restored) StoryActions.fastForward(entry)
                entry.undoneAt = null
            }
        }
        entries.removeAll { scope(it) && it.seq >= mark }
    }

    /** Takes back every temporary action left, for a run that ends; permanent ones stay in the world. */
    fun close(run: String) = undoTemporary(run, 0, branches = true)

    /**
     * Forgets what no rewind can reach any more: nothing marks the journal before [floor], so an action taken back
     * before it stays so and a permanent one performed before it stays performed.
     */
    fun prune(floor: Long) {
        entries.removeAll { entry ->
            val undoneAt = entry.undoneAt
            if (undoneAt != null) undoneAt < floor else entry.lifetime == Lifetime.RUN && entry.seq < floor
        }
    }

    fun entry(seq: Long): JournalEntry? = entries.firstOrNull { it.seq == seq }

    /** What [run] performed, for the inspector and commands. */
    fun entriesOf(run: String): List<JournalEntry> = entries.filter { it.run == run }

    fun save(): CompoundTag = CompoundTag().apply {
        putLong("next", next)
        put("entries", ListTag().apply { entries.forEach { add(it.save()) } })
    }

    fun load(tag: CompoundTag) {
        entries.clear()
        tag.getList("entries", Tag.TAG_COMPOUND.toInt()).forEach { entries += JournalEntry.load(it as CompoundTag) }
        next = maxOf(tag.getLong("next"), (entries.maxOfOrNull { it.seq } ?: -1) + 1)
    }
}
