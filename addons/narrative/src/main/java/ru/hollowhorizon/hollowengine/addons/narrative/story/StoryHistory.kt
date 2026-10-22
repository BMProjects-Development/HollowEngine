package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * The checkpoints a story passed, oldest first.
 *
 * A checkpoint keeps the whole tree of runs as it was there and marks the journal, so a rewind to it undoes what was
 * performed since and puts the runs back.
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
            put("tree", tree.copy())
            if (labels.isNotEmpty()) put("labels", ListTag().apply { labels.forEach { add(it.save()) } })
        }

        companion object {
            fun load(tag: CompoundTag) = Checkpoint(
                tag.getString("name"),
                tag.getString("run"),
                tag.getLong("mark"),
                tag.getCompound("tree").copy(),
                tag.getList("labels", Tag.TAG_COMPOUND.toInt()).map { JournalEntry.load(it as CompoundTag) },
            )
        }
    }

    private val checkpoints = ArrayList<Checkpoint>()

    val all: List<Checkpoint> get() = checkpoints

    fun has(name: String): Boolean = checkpoints.any { it.name == name }

    /** Whether [run] passed a checkpoint of its own that is still kept: its entry at least, unless it was forgotten. */
    fun hasOwn(run: StoryRun): Boolean = checkpoints.any { it.run == run.path }

    /** [run] passed the checkpoint [name]: keeps the tree as it is now with what [labels] captured. */
    fun add(name: String, run: StoryRun, labels: List<JournalEntry>) {
        checkpoints += Checkpoint(name, run.path, root.journal.next, root.saveTree(), labels)
        if (checkpoints.size > MAX_CHECKPOINTS) checkpoints.removeAt(0)
    }

    /** The lowest mark a rewind can still go back to: the oldest checkpoint, or the segment a stage is in. */
    fun floor(segments: Sequence<Long>): Long =
        (checkpoints.asSequence().map { it.mark } + segments).minOrNull() ?: root.journal.next

    /** Goes back to the latest checkpoint named [name]: the whole tree as it was there, labels put back. */
    suspend fun rewind(name: String) {
        val index = checkpoints.indexOfLast { it.name == name }
        if (index < 0) throw StoryException("Story '${root.path}' has no checkpoint '$name'")
        val target = checkpoints[index]
        root.halt("it rewinds to '$name'")
        root.journal.rewind(target.mark)
        target.labels.asReversed().forEach(StoryActions::undo)
        checkpoints.subList(index + 1, checkpoints.size).clear()
        root.restoreTree(target.tree)
    }

    /**
     * Takes [run] back to the latest checkpoint it passed itself, its entry at the least: [run] and its branches
     * as they were there, what they performed since undone. Whatever runs beside it goes on.
     */
    suspend fun rewindOwn(run: StoryRun) {
        val index = checkpoints.indexOfLast { it.run == run.path }
        if (index < 0) throw StoryException("Story '${run.path}' has no checkpoint left to rewind to")
        val target = checkpoints[index]
        run.halt("it rewinds to '${target.name}'")
        val inside = { path: String -> path == run.path || path.startsWith("${run.path}/") }
        root.journal.rewind(target.mark) { inside(it.run) }
        target.labels.asReversed().forEach(StoryActions::undo)
        checkpoints.subList(index + 1, checkpoints.size).removeAll { inside(it.run) }
        run.restoreTree(subtree(target.tree, run.path))
    }

    /** What [tree], saved for the root, holds for the run at [path]. */
    private fun subtree(tree: CompoundTag, path: String): CompoundTag =
        path.split('/').drop(1).fold(tree) { tag, name -> tag.getCompound("children").getCompound(name) }

    fun save(): CompoundTag = CompoundTag().apply {
        put("checkpoints", ListTag().apply { checkpoints.forEach { add(it.save()) } })
    }

    fun load(tag: CompoundTag) {
        checkpoints.clear()
        tag.getList("checkpoints", Tag.TAG_COMPOUND.toInt())
            .forEach { checkpoints += Checkpoint.load(it as CompoundTag) }
    }

    private companion object {
        /** Older checkpoints are forgotten, so a long story does not grow its save without end. */
        const val MAX_CHECKPOINTS = 64
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
        labels += JournalEntry(-1, action.id, run, Lifetime.RUN, null, args, snapshots, null)
    }

    internal companion object {
        /** The receiver of a label the journal calls again to put back what it captured. */
        val REPLAY = CheckpointScope("")
    }
}
