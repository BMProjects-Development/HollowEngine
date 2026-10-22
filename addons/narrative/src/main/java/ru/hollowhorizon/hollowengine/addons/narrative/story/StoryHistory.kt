package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * The checkpoints a story passed, oldest first, and the rewinds it can redo.
 *
 * A checkpoint keeps the whole tree of runs as it was there and marks the journal, so a rewind to it undoes what was
 * performed since and puts the runs back. A rewind can be redone until the story performs a new action.
 */
internal class StoryHistory(private val root: StoryRun) {
    class Checkpoint(
        val name: String,
        val run: String,
        val mark: Long,
        val tree: CompoundTag,
        val labels: List<JournalEntry>,
    ) {
        fun save(): CompoundTag = CompoundTag().apply {
            putString("name", name)
            putString("run", run)
            putLong("mark", mark)
            put("tree", tree)
            if (labels.isNotEmpty()) put("labels", ListTag().apply { labels.forEach { add(it.save()) } })
        }

        companion object {
            fun load(tag: CompoundTag) = Checkpoint(
                tag.getString("name"),
                tag.getString("run"),
                tag.getLong("mark"),
                tag.getCompound("tree"),
                tag.getList("labels", Tag.TAG_COMPOUND.toInt()).map { JournalEntry.load(it as CompoundTag) },
            )
        }
    }

    /** What a rewind left behind: the tree, the journal and the checkpoints as they were before it. */
    private class Redo(val tree: CompoundTag, val journal: ListTag, val checkpoints: List<Checkpoint>) {
        fun save(): CompoundTag = CompoundTag().apply {
            put("tree", tree)
            put("journal", journal)
            put("checkpoints", ListTag().apply { checkpoints.forEach { add(it.save()) } })
        }

        companion object {
            fun load(tag: CompoundTag) = Redo(
                tag.getCompound("tree"),
                tag.getList("journal", Tag.TAG_COMPOUND.toInt()),
                tag.getList("checkpoints", Tag.TAG_COMPOUND.toInt()).map { Checkpoint.load(it as CompoundTag) },
            )
        }
    }

    private val checkpoints = ArrayList<Checkpoint>()
    private val redo = ArrayList<Redo>()

    val all: List<Checkpoint> get() = checkpoints

    val canRedo: Boolean get() = redo.isNotEmpty()

    fun has(name: String): Boolean = checkpoints.any { it.name == name }

    /** [run] passed the checkpoint [name]: keeps the tree as it is now with what [labels] captured. */
    fun add(name: String, run: StoryRun, labels: List<JournalEntry>) {
        checkpoints += Checkpoint(name, run.path, root.journal.next, root.saveTree(), labels)
        if (checkpoints.size > MAX_CHECKPOINTS) checkpoints.removeAt(0)
    }

    /** The story performed a new action, so what it rewound past cannot come back. */
    fun recorded() = redo.clear()

    /** The lowest mark a rewind can still go back to: the oldest checkpoint, or the segment a stage is in. */
    fun floor(segments: Sequence<Long>): Long =
        (checkpoints.asSequence().map { it.mark } + segments).minOrNull() ?: root.journal.next

    /** Goes back to the latest checkpoint named [name]: the whole tree as it was there, labels put back. */
    suspend fun rewind(name: String) {
        val index = checkpoints.indexOfLast { it.name == name }
        if (index < 0) throw StoryException("Story '${root.path}' has no checkpoint '$name'")
        val target = checkpoints[index]
        root.halt("it rewinds to '$name'")
        val before = Redo(root.saveTree(), root.journal.entriesTag(), checkpoints.toList())
        root.journal.rewind(target.mark)
        target.labels.asReversed().forEach(StoryActions::undo)
        checkpoints.subList(index + 1, checkpoints.size).clear()
        push(before)
        root.restoreTree(target.tree)
    }

    /** Takes [run] and its active branches each back to the start of the segment it is in. */
    suspend fun rewindSegments(run: StoryRun) {
        run.halt("it rewinds")
        val before = Redo(root.saveTree(), root.journal.entriesTag(), checkpoints.toList())
        run.tree().filter { !it.state.done }.forEach { branch ->
            val frame = branch.pending.lastOrNull() ?: return@forEach
            root.journal.rewind(frame.segmentMark) { it.run == branch.path }
        }
        push(before)
    }

    /** Returns to where the latest rewind started from, performing again what it undid. */
    suspend fun redo() {
        val record = redo.removeLastOrNull() ?: throw StoryException("Story '${root.path}' has nothing to redo")
        root.halt("it redoes a rewind")
        root.journal.restore(record.journal)
        checkpoints.clear()
        checkpoints += record.checkpoints
        root.restoreTree(record.tree)
    }

    private fun push(record: Redo) {
        redo += record
        if (redo.size > MAX_REDO) redo.removeAt(0)
    }

    fun save(): CompoundTag = CompoundTag().apply {
        put("checkpoints", ListTag().apply { checkpoints.forEach { add(it.save()) } })
        if (redo.isNotEmpty()) put("redo", ListTag().apply { redo.forEach { add(it.save()) } })
    }

    fun load(tag: CompoundTag) {
        checkpoints.clear()
        redo.clear()
        tag.getList("checkpoints", Tag.TAG_COMPOUND.toInt())
            .forEach { checkpoints += Checkpoint.load(it as CompoundTag) }
        tag.getList("redo", Tag.TAG_COMPOUND.toInt()).forEach { redo += Redo.load(it as CompoundTag) }
    }

    private companion object {
        /** Older checkpoints are forgotten, so a long story does not grow its save without end. */
        const val MAX_CHECKPOINTS = 64
        const val MAX_REDO = 8
    }
}

/**
 * What a checkpoint captures besides the story: labels such as [position] and [inventory] take the state of the world
 * when the checkpoint is passed and put it back on every rewind to it.
 */
@StoryDsl
class CheckpointScope internal constructor(private val run: String) {
    internal val labels = ArrayList<JournalEntry>()

    internal fun add(action: StoryAction, args: CompoundTag, snapshots: CompoundTag) {
        labels += JournalEntry(-1, action.id, run, Lifetime.RUN, args, snapshots, null)
    }

    internal companion object {
        /** The receiver of a label the journal calls again to put back what it captured. */
        val REPLAY = CheckpointScope("")
    }
}
