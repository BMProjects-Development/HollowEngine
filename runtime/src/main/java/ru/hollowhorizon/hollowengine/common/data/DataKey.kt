package ru.hollowhorizon.hollowengine.common.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat

/**
 * Who, besides the server, gets to see a key's value.
 *
 * There is no choice of *when*: a synced key goes out as soon as it changes, batched into the one
 * packet its entity sends at the end of the tick.
 */
enum class Sync {
    /** Server-side only, the default. */
    NEVER,

    /** Every client that tracks the entity. */
    TRACKING,

    /**
     * Only the entity itself, and only when it is a player. For a player's own state: quest progress,
     * stamina - which everyone standing nearby has no business receiving. On a non-player entity this
     * is the same as [NEVER]: there is nobody to send it to.
     */
    OWNER,
}

/**
 * A typed handle into an NBT document. A key names a slot and carries the serializer for its value,
 * so callers read and write real Kotlin types without repeating the name or the (de)serialization:
 *
 * ```kotlin
 * @Serializable data class Quest(val title: String, val progress: Int)
 * val CurrentQuest = dataKey<Quest>("mypack:quest")
 * store[CurrentQuest] = Quest("The Missing Cargo", 3)
 * ```
 *
 * The same key type backs every NBT-keyed store in the engine, the persistent data attached to any
 * entity or player ([ru.hollowhorizon.hollowengine.common.data.data]), persistent story state, and
 * the observable [ru.hollowhorizon.hollowengine.common.ui.UiData] a scripted UI binds to.
 *
 * [sync] applies to entity data only, where it decides which clients receive the value; the other
 * stores ignore it.
 */
class DataKey<T : Any>(
    val name: String,
    internal val serializer: KSerializer<T>,
    internal val defaultValue: (() -> T)?,
    val sync: Sync = Sync.NEVER,
) {
    init {
        require(name.isNotBlank()) { "Data key name cannot be blank" }
    }

    /** Returns a copy of this key with a (different) default value. */
    fun withDefault(default: () -> T): DataKey<T> = DataKey(name, serializer, default, sync)

    /** Returns a copy of this key with a (different) sync policy. */
    fun withSync(sync: Sync): DataKey<T> = DataKey(name, serializer, defaultValue, sync)
}

inline fun <reified T : Any> dataKey(name: String, sync: Sync = Sync.NEVER): DataKey<T> =
    DataKey(name, serializer(), null, sync)

inline fun <reified T : Any> dataKey(
    name: String,
    sync: Sync = Sync.NEVER,
    noinline defaultValue: () -> T,
): DataKey<T> = DataKey(name, serializer(), defaultValue, sync)

/** Reads and deserializes [key] from this compound, or null when absent. */
fun <T : Any> CompoundTag.read(key: DataKey<T>): T? =
    get(key.name)?.let { NBTFormat.deserialize(key.serializer, it) }

/** Serializes [value] and writes it under [key]. */
fun <T : Any> CompoundTag.write(key: DataKey<T>, value: T) {
    put(key.name, NBTFormat.serialize(key.serializer, value))
}

/** Serializes [value] with [key]'s serializer without writing it anywhere. */
fun <T : Any> DataKey<T>.encode(value: T): Tag = NBTFormat.serialize(serializer, value)

/** Deserializes [tag] with [key]'s serializer. */
fun <T : Any> DataKey<T>.decode(tag: Tag): T? = runCatching { NBTFormat.deserialize(serializer, tag) }
    .onFailure { HollowEngine.LOGGER.warn("Failed to decode $this", it) }.getOrNull()

internal fun <T : Any> DataKey<T>.default(): T? = defaultValue?.invoke()
