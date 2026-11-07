package ru.hollowhorizon.hollowengine.addons.narrative.nbt

import com.mojang.brigadier.StringReader
import net.minecraft.commands.arguments.NbtPathArgument
import net.minecraft.nbt.*
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValue
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory

/** The SNBT [snbt], such as `{NoAI:1b}`, `[1, 2]` or `5`, as a tag. */
fun nbt(snbt: String): Tag =
    runCatching { TagParser(StringReader(snbt)).readValue() }.getOrElse { throw StoryException("'$snbt' is not SNBT: ${it.message}") }

/** A tag written as SNBT in its field. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt")
fun nbtValue(nbt: Tag): Tag = nbt

/** A compound with nothing in it, to put values into by key. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-compound")
fun nbtCompound(): CompoundTag = CompoundTag()

/** A list of [values]; NBT keeps lists of one kind of tag only. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-list")
fun nbtList(values: List<Tag> = emptyList()): ListTag = ListTag().apply {
    values.forEach { value ->
        if (!addTag(
                size,
                value.copy()
            )
        ) throw StoryException("A list of NBT holds tags of one kind, and $value is of another")
    }
}

/** What [nbt] holds under [key]: a key of a compound, or an index of a list; null when it holds nothing there. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-get")
fun nbtGet(nbt: Tag, key: String): Tag? = when (nbt) {
    is CompoundTag -> nbt.get(key)
    is CollectionTag<*> -> key.toIntOrNull()?.let { nbt.getOrNull(it) }
    else -> null
}

/** What the NBT path [path], such as `Inventory[0].id`, finds in [nbt]: the first of it, or null for nothing. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-find")
fun nbtFind(nbt: Tag, path: String): Tag? = runCatching { pathOf(path).get(nbt).firstOrNull() }.getOrNull()

/** Whether [nbt] holds something under [key]. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-has")
fun nbtHas(nbt: Tag, key: String): Boolean = nbtGet(nbt, key) != null

/** A copy of the compound [nbt] with [value] under [key]. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-put")
fun nbtPut(nbt: Tag, key: String, value: Tag): CompoundTag = compoundOf(nbt).copy().apply { put(key, value.copy()) }

/** A copy of [nbt] with [value] at the NBT path [path], which is made where it is missing. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-set")
fun nbtSet(nbt: Tag, path: String, value: Tag): Tag = nbt.copy().also { pathOf(path).set(it, value.copy()) }

/** A copy of the compound [nbt] without [key]. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-remove")
fun nbtRemove(nbt: Tag, key: String): CompoundTag = compoundOf(nbt).copy().apply { remove(key) }

/** A copy of the compound [nbt] with what [other] holds put over it, the way `/data merge` puts it. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-merge")
fun nbtMerge(nbt: Tag, other: Tag): CompoundTag = compoundOf(nbt).copy().merge(compoundOf(other))

/** The keys of the compound [nbt]. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-keys")
fun nbtKeys(nbt: Tag): List<String> = (nbt as? CompoundTag)?.allKeys?.sorted().orEmpty()

/** The tags of the list [nbt]. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-items")
fun nbtItems(nbt: Tag): List<Tag> = (nbt as? CollectionTag<*>)?.toList().orEmpty()

/** How many keys the compound or items the list [nbt] has. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-size")
fun nbtSize(nbt: Tag): Int = when (nbt) {
    is CompoundTag -> nbt.size()
    is CollectionTag<*> -> nbt.size
    else -> 0
}

/** [value] as a tag: a number, text, flag or vector, or a list of them. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("to-nbt")
fun toNbt(value: Any): Tag = tagOf(value)

/** [nbt] as a number: a number as it is, text that reads as one, 0 for anything else. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-number")
fun nbtNumber(nbt: Tag?): Double = (nbt as? NumericTag)?.asDouble ?: nbt?.asString?.toDoubleOrNull() ?: 0.0

/** [nbt] as text: text as it is, anything else in SNBT, nothing for no value. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-text")
fun nbtText(nbt: Tag?): String = when (nbt) {
    null -> ""
    is StringTag -> nbt.asString
    else -> nbt.toString()
}

/** [nbt] as a flag: a number other than 0, or the text `true`. */
@EditorCategory(StoryCategory.NBT)
@StoryValue("nbt-flag")
fun nbtFlag(nbt: Tag?): Boolean = when (nbt) {
    is NumericTag -> nbt.asDouble != 0.0
    is StringTag -> nbt.asString == "true"
    else -> false
}

/** How [value] is written as a tag. */
fun tagOf(value: Any): Tag = when (value) {
    is Tag -> value.copy()
    is Boolean -> ByteTag.valueOf(value)
    is Int -> IntTag.valueOf(value)
    is Long -> LongTag.valueOf(value)
    is Number -> DoubleTag.valueOf(value.toDouble())
    is String -> StringTag.valueOf(value)
    is Vec3 -> CompoundTag().apply {
        putDouble("x", value.x)
        putDouble("y", value.y)
        putDouble("z", value.z)
    }

    is Iterable<*> -> nbtList(value.map { tagOf(it ?: throw StoryException("NBT keeps no null")) })
    else -> throw StoryException("NBT keeps numbers, text, flags, vectors and lists of them, not ${value::class.simpleName}")
}

/** [nbt] when it is a compound. */
fun compoundOf(nbt: Tag): CompoundTag = nbt as? CompoundTag ?: throw StoryException("$nbt is not a compound")

private fun pathOf(path: String): NbtPathArgument.NbtPath =
    runCatching { NbtPathArgument().parse(StringReader(path)) }.getOrElse { throw StoryException("'$path' is not an NBT path: ${it.message}") }
