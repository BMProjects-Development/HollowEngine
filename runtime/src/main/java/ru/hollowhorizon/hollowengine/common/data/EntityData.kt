package ru.hollowhorizon.hollowengine.common.data

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import java.util.*

/**
 * The persistent typed NBT document attached to any entity, players included:
 *
 * ```kotlin
 * val Visits = dataKey<Int>("mypack:visits") { 0 }
 * player.data.update(Visits) { it + 1 }
 * ```
 *
 * The server owns it. A key declared with a [Sync] policy is also mirrored to the clients that policy
 * allows, so a UI or animation can read what a node script wrote:
 *
 * ```kotlin
 * val QuestGiver = dataKey<Boolean>("mypack:quest_giver", sync = Sync.TRACKING) { false }
 * npc.data[QuestGiver] = true          // server
 * if (entity.data[QuestGiver] == true) // client, next tick
 * ```
 */
@JvmInline
value class EntityData internal constructor(private val entity: Entity) {
    private val existing: NbtDataStore? get() = AttachmentRegistry.entityDataOrNull(entity)
    private val orCreate: NbtDataStore get() = AttachmentRegistry.entityData(entity)

    operator fun <T : Any> get(key: DataKey<T>): T? = existing?.get(key) ?: key.default()

    fun <T : Any> getOrPut(key: DataKey<T>, defaultValue: () -> T): T {
        warnIfOverwritten(key)
        return orCreate.getOrPut(key, defaultValue)
    }

    fun <T : Any> getOrPut(key: DataKey<T>): T {
        warnIfOverwritten(key)
        return orCreate.getOrPut(key)
    }

    operator fun <T : Any> set(key: DataKey<T>, value: T) {
        warnIfOverwritten(key)
        orCreate[key] = value
    }

    fun <T : Any> update(key: DataKey<T>, transform: (T) -> T): T {
        warnIfOverwritten(key)
        return orCreate.update(key, transform)
    }

    operator fun contains(key: DataKey<*>): Boolean = existing?.contains(key) == true

    fun remove(key: DataKey<*>): Boolean {
        warnIfOverwritten(key)
        return existing?.remove(key) == true
    }

    fun clear() {
        existing?.clear()
    }

    fun isEmpty(): Boolean = existing?.isEmpty() != false

    /**
     * A synced key written on the client survives exactly until the server's next batch overwrites it,
     * which is the kind of bug that looks like the value never arrived. Say so once per key.
     */
    private fun warnIfOverwritten(key: DataKey<*>) {
        if (!entity.level().isClientSide || key.sync == Sync.NEVER) return
        if (!warnedKeys.add(key.name)) return
        HollowEngine.LOGGER.warn(
            "Data key '{}' is written on the client, but it is Sync.{}: the server owns it and the " +
                    "next batch will overwrite this value. Write it on the server instead.",
            key.name,
            key.sync,
        )
    }

    private companion object {
        val warnedKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }
}

/** The persistent typed NBT document attached to this entity. See [EntityData]. */
val Entity.data: EntityData get() = EntityData(this)
