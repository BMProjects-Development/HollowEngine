package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryAnchor

/**
 * Everything needed to continue a dialogue after a restart. Written after every statement, so the
 * worst case a player can lose is the statement that was running.
 *
 * The position is stored as [StoryAnchor]s, label plus offset inside its section, rather than raw
 * instruction indices, which is what lets a resumed dialogue survive an edited story: an exact
 * [sourceHash] match resumes precisely, a surviving label rolls back to that label, and neither means
 * the story restarts.
 */
data class StoryCheckpoint(
    val address: String,
    val sourceHash: String,
    val locale: String?,
    /** Call stack, outermost first; the last frame is where execution is. */
    val frames: List<CheckpointFrame>,
    val variables: Map<String, StoryValue>,
    /** Async tracks that had not finished; each restarts from its `@async` on resume. */
    val tracks: List<CheckpointTrack> = emptyList(),
    /** When the `@timeout` blocks entered give up, in epoch milliseconds, by `address#pc`; kept on an exact resume only. */
    val deadlines: Map<String, Long> = emptyMap(),
) {
    fun save(): CompoundTag = CompoundTag().apply {
        putString(KEY_ADDRESS, address)
        putString(KEY_HASH, sourceHash)
        locale?.let { putString(KEY_LOCALE, it) }
        put(KEY_FRAMES, ListTag().apply { frames.forEach { add(it.save()) } })
        put(KEY_VARIABLES, saveVariables(variables))
        if (tracks.isNotEmpty()) put(KEY_TRACKS, ListTag().apply { tracks.forEach { add(it.save()) } })
        if (deadlines.isNotEmpty()) put(KEY_DEADLINES, CompoundTag().apply { deadlines.forEach(::putLong) })
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_HASH = "hash"
        private const val KEY_LOCALE = "locale"
        private const val KEY_FRAMES = "frames"
        private const val KEY_VARIABLES = "variables"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_DEADLINES = "deadlines"

        fun load(tag: CompoundTag): StoryCheckpoint? {
            if (!tag.contains(KEY_ADDRESS) || !tag.contains(KEY_FRAMES)) return null
            val frames = tag.getList(KEY_FRAMES, Tag.TAG_COMPOUND.toInt())
                .map { CheckpointFrame.load(it as CompoundTag) }
            if (frames.isEmpty()) return null
            val tracks = tag.getList(KEY_TRACKS, Tag.TAG_COMPOUND.toInt())
                .map { CheckpointTrack.load(it as CompoundTag) }
            return StoryCheckpoint(
                address = tag.getString(KEY_ADDRESS),
                sourceHash = tag.getString(KEY_HASH),
                locale = tag.getString(KEY_LOCALE).takeIf { it.isNotEmpty() },
                frames = frames,
                variables = loadVariables(tag.getCompound(KEY_VARIABLES)),
                tracks = tracks,
                deadlines = tag.getCompound(KEY_DEADLINES).let { saved -> saved.allKeys.associateWith(saved::getLong) },
            )
        }

        private const val TYPE_STRING = "s"
        private const val TYPE_NUMBER = "n"
        private const val TYPE_BOOL = "b"
        private const val TYPE_LIST = "l"

        /**
         * Variables are written with an explicit type tag. NBT would happily coerce a `1` written as
         * a number into an int on the way back, and a story that compares `money == 1` must not start
         * failing because of a round trip.
         */
        private fun saveVariables(variables: Map<String, StoryValue>) = CompoundTag().apply {
            for ((name, value) in variables) {
                put(name, saveValue(value) ?: continue)
            }
        }

        private fun saveValue(value: StoryValue): CompoundTag? {
            val entry = CompoundTag()
            when (value) {
                is StoryString -> {
                    entry.putString("t", TYPE_STRING)
                    entry.putString("v", value.value)
                }

                is StoryNumber -> {
                    entry.putString("t", TYPE_NUMBER)
                    entry.putFloat("v", value.value)
                }

                is StoryBool -> {
                    entry.putString("t", TYPE_BOOL)
                    entry.putBoolean("v", value.value)
                }

                is StoryList -> {
                    entry.putString("t", TYPE_LIST)
                    entry.put("v", ListTag().apply { value.values.forEach { item -> saveValue(item)?.let(::add) } })
                }

                is StoryActor -> return null
            }
            return entry
        }

        private fun loadVariables(tag: CompoundTag): Map<String, StoryValue> =
            tag.allKeys.mapNotNull { name -> loadValue(tag.getCompound(name))?.let { name to it } }.toMap()

        private fun loadValue(entry: CompoundTag): StoryValue? = when (entry.getString("t")) {
            TYPE_STRING -> StoryString(entry.getString("v"))
            TYPE_NUMBER -> StoryNumber(entry.getFloat("v"))
            TYPE_BOOL -> StoryBool(entry.getBoolean("v"))
            TYPE_LIST -> StoryList(
                entry.getList("v", Tag.TAG_COMPOUND.toInt()).mapNotNull { loadValue(it as CompoundTag) },
            )

            else -> null
        }
    }
}

/** One entry of the call stack: which story it is in, and where. */
data class CheckpointFrame(val address: String, val anchor: StoryAnchor) {
    fun save(): CompoundTag = CompoundTag().apply {
        putString("address", address)
        anchor.label?.let { putString("label", it) }
        putInt("offset", anchor.offset)
        putInt("line", anchor.line)
    }

    companion object {
        fun load(tag: CompoundTag) = CheckpointFrame(
            address = tag.getString("address"),
            anchor = StoryAnchor(
                label = tag.getString("label").takeIf { it.isNotEmpty() },
                offset = tag.getInt("offset"),
                line = tag.getInt("line"),
            ),
        )
    }
}

/** An unfinished async track. It is restarted whole, so only its `@async` position is stored. */
data class CheckpointTrack(val name: String?, val anchor: StoryAnchor) {
    fun save(): CompoundTag = CompoundTag().apply {
        name?.let { putString("name", it) }
        anchor.label?.let { putString("label", it) }
        putInt("offset", anchor.offset)
        putInt("line", anchor.line)
    }

    companion object {
        fun load(tag: CompoundTag) = CheckpointTrack(
            name = tag.getString("name").takeIf { it.isNotEmpty() },
            anchor = StoryAnchor(
                label = tag.getString("label").takeIf { it.isNotEmpty() },
                offset = tag.getInt("offset"),
                line = tag.getInt("line"),
            ),
        )
    }
}
