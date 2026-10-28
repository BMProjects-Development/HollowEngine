package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor

/**
 * Everything needed to continue a scene after a restart. Written after every statement, so the worst case a player
 * can lose is the statement that was running. The tracks of the scene are kept by the branches of its story.
 */
data class SceneCheckpoint(
    val address: String,
    val sourceHash: String,
    val locale: String?,
    val frames: List<CheckpointFrame>,
    val variables: Map<String, StoryValue>,
    val deadlines: Map<String, Long> = emptyMap(),
) {
    fun save(): CompoundTag = CompoundTag().apply {
        putString(KEY_ADDRESS, address)
        putString(KEY_HASH, sourceHash)
        locale?.let { putString(KEY_LOCALE, it) }
        put(KEY_FRAMES, ListTag().apply { frames.forEach { add(it.save()) } })
        put(KEY_VARIABLES, saveVariables(variables))
        if (deadlines.isNotEmpty()) put(KEY_DEADLINES, CompoundTag().apply { deadlines.forEach(::putLong) })
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_HASH = "hash"
        private const val KEY_LOCALE = "locale"
        private const val KEY_FRAMES = "frames"
        private const val KEY_VARIABLES = "variables"
        private const val KEY_DEADLINES = "deadlines"

        fun load(tag: CompoundTag): SceneCheckpoint? {
            if (!tag.contains(KEY_ADDRESS) || !tag.contains(KEY_FRAMES)) return null
            val frames = tag.getList(KEY_FRAMES, Tag.TAG_COMPOUND.toInt())
                .map { CheckpointFrame.load(it as CompoundTag) }
            if (frames.isEmpty()) return null
            return SceneCheckpoint(
                address = tag.getString(KEY_ADDRESS),
                sourceHash = tag.getString(KEY_HASH),
                locale = tag.getString(KEY_LOCALE).takeIf { it.isNotEmpty() },
                frames = frames,
                variables = loadVariables(tag.getCompound(KEY_VARIABLES)),
                deadlines = tag.getCompound(KEY_DEADLINES).let { saved -> saved.allKeys.associateWith(saved::getLong) },
            )
        }

        private const val TYPE_STRING = "s"
        private const val TYPE_NUMBER = "n"
        private const val TYPE_BOOL = "b"
        private const val TYPE_LIST = "l"
        private const val TYPE_ENTITY = "e"

        internal fun saveVariables(variables: Map<String, StoryValue>) = CompoundTag().apply {
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

                is StoryEntity -> {
                    entry.putString("t", TYPE_ENTITY)
                    entry.putUUID("v", value.uuid)
                    entry.putString("n", value.name)
                }

                is StoryActor -> return null
            }
            return entry
        }

        internal fun loadVariables(tag: CompoundTag): Map<String, StoryValue> =
            tag.allKeys.mapNotNull { name -> loadValue(tag.getCompound(name))?.let { name to it } }.toMap()

        private fun loadValue(entry: CompoundTag): StoryValue? = when (entry.getString("t")) {
            TYPE_STRING -> StoryString(entry.getString("v"))
            TYPE_NUMBER -> StoryNumber(entry.getFloat("v"))
            TYPE_BOOL -> StoryBool(entry.getBoolean("v"))
            TYPE_LIST -> StoryList(
                entry.getList("v", Tag.TAG_COMPOUND.toInt()).mapNotNull { loadValue(it as CompoundTag) },
            )

            TYPE_ENTITY -> StoryEntity(entry.getUUID("v"), entry.getString("n"))

            else -> null
        }
    }
}

/**
 * One entry of the call stack: which story it is in, and where. A frame of a `@define` also keeps its [locals] and
 * the variable of its caller its value goes to, [result].
 */
data class CheckpointFrame(
    val address: String,
    val anchor: StoryAnchor,
    val locals: Map<String, StoryValue> = emptyMap(),
    val result: String? = null,
) {
    fun save(): CompoundTag = CompoundTag().apply {
        putString("address", address)
        anchor.label?.let { putString("label", it) }
        putInt("offset", anchor.offset)
        putInt("line", anchor.line)
        if (locals.isNotEmpty()) put("locals", SceneCheckpoint.saveVariables(locals))
        result?.let { putString("result", it) }
    }

    companion object {
        fun load(tag: CompoundTag) = CheckpointFrame(
            address = tag.getString("address"),
            anchor = StoryAnchor(
                label = tag.getString("label").takeIf { it.isNotEmpty() },
                offset = tag.getInt("offset"),
                line = tag.getInt("line"),
            ),
            locals = SceneCheckpoint.loadVariables(tag.getCompound("locals")),
            result = tag.getString("result").takeIf { tag.contains("result") },
        )
    }
}
